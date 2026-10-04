import AppKit
import Carbon.HIToolbox
import SwiftUI
import UniformTypeIdentifiers

@MainActor
final class AppDelegate: NSObject, NSApplicationDelegate {
    private let controller = ConversationController()
    private var window: NSWindow!
    private var statusBar: StatusBarController!
    private var escapeMonitor: Any?

    func applicationDidFinishLaunching(_ notification: Notification) {
        NSApp.mainMenu = makeMenu()

        window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 1100, height: 720),
                          styleMask: [.titled, .closable, .miniaturizable, .resizable, .fullSizeContentView],
                          backing: .buffered, defer: false)
        window.title = "Q-Translator 快译"
        window.titlebarAppearsTransparent = true
        window.titleVisibility = .hidden
        window.isReleasedWhenClosed = false   // 关窗只是隐藏，再次呼出不用重建
        window.minSize = NSSize(width: 760, height: 480)
        window.isMovableByWindowBackground = true
        window.contentView = NSHostingView(rootView: WideRootView(controller: controller))
        window.center()
        window.setFrameAutosaveName("QTranslatorMainWindow")

        // 菜单栏图标和全局快捷键（⌥D 主窗口、⌥F 选中文字、⌥R 翻译并替换、⌥V 剪贴板、⌥S 截图、⌥A 输入）
        statusBar = StatusBarController(controller: controller,
                                        toggleMainWindow: { [weak self] in self?.toggle() },
                                        showMainWindow: { [weak self] in self?.show() },
                                        newSession: { [weak self] in self?.newSession(nil) },
                                        openSettings: { [weak self] in self?.openSettings(nil) })

        // Esc 隐藏；输入法正在组字时把 Esc 留给输入法，弹出的面板里的 Esc 也不拦
        escapeMonitor = NSEvent.addLocalMonitorForEvents(matching: .keyDown) { [weak self] event in
            guard event.keyCode == UInt16(kVK_Escape), let self, event.window === self.window,
                  self.window.attachedSheet == nil else { return event }
            if (self.window.firstResponder as? NSTextView)?.hasMarkedText() == true { return event }
            NSApp.hide(nil)
            return nil
        }

        // 系统“服务”：在任意 app 里选中文字 → 右键 → 用快译翻译（端口名要和 Info.plist 的 NSPortName 一致）
        NSRegisterServicesProvider(self, "QTranslator")

        // 选了“只在菜单栏显示”时，启动后不弹主窗口
        if !UserDefaults.standard.bool(forKey: MenuBarKey.hideDockIcon) { show() }

        // 支持带参数启动直接查询：open -a Q-Translator --args hello，或传一张图片的路径
        let query = CommandLine.arguments.dropFirst().filter { !$0.hasPrefix("-") }.joined(separator: " ")
        if let image = NSImage(contentsOfFile: query) {
            // 图片先放在输入框上方等用户确认（可以再加一句要求），由输入栏发送
            controller.attachImages([image])
        } else if !query.isEmpty {
            send(query)
        }
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { false }

    func applicationShouldHandleReopen(_ sender: NSApplication, hasVisibleWindows flag: Bool) -> Bool {
        show()
        return true
    }

    /// 右键服务的入口，方法名对应 Info.plist 里的 NSMessage
    @objc func translateSelection(_ pasteboard: NSPasteboard, userData: String,
                                  error: AutoreleasingUnsafeMutablePointer<NSString>) {
        guard let text = pasteboard.string(forType: .string)?.trimmed, !text.isEmpty else { return }
        log.info("service: received \(text.count, privacy: .public) characters")
        // 在鼠标旁边的小窗里直接显示译文，不打断手上的事
        statusBar.translate(text: text)
    }

    /// 放进当前会话翻译
    private func send(_ text: String) {
        controller.draft = text
        controller.send()
    }

    /// ⌘V：剪贴板里是图片就放到输入框上方等待发送，否则按普通文字粘贴
    @objc func smartPaste(_ sender: Any?) {
        // 设置、写回复等面板打开时，只做普通粘贴
        if window.attachedSheet == nil, let image = StatusBarController.image(from: .general) {
            controller.attachImages([image])
        } else {
            NSApp.sendAction(#selector(NSText.paste(_:)), to: nil, from: sender)
        }
    }

    @objc func newSession(_ sender: Any?) {
        show()
        controller.newSession()
        NotificationCenter.default.post(name: .focusInput, object: nil)
    }

    @objc func openModule(_ sender: NSMenuItem) {
        guard let module = AppModule(rawValue: sender.representedObject as? String ?? "") else { return }
        show()
        ModuleRouter.shared.open(module, start: sender.tag == 1, from: "menu")
    }

    @objc func openSettings(_ sender: Any?) {
        show()
        NotificationCenter.default.post(name: .openSettings, object: nil)
    }

    private func toggle() {
        if NSApp.isActive, window.isKeyWindow {
            NSApp.hide(nil)
        } else {
            show()
        }
    }

    private func show() {
        NSApp.activate(ignoringOtherApps: true)
        window.makeKeyAndOrderFront(nil)
        NotificationCenter.default.post(name: .focusInput, object: nil)
    }

    /// 纯 AppKit 启动没有默认菜单，补上最基本的，否则 ⌘C / ⌘V / ⌘Q 不生效
    private func makeMenu() -> NSMenu {
        let main = NSMenu()

        let app = NSMenu()
        app.addItem(withTitle: "设置…", action: #selector(openSettings(_:)), keyEquivalent: ",").target = self
        app.addItem(.separator())
        app.addItem(withTitle: "隐藏 Q-Translator", action: #selector(NSApplication.hide(_:)), keyEquivalent: "h")
        app.addItem(.separator())
        app.addItem(withTitle: "退出 Q-Translator", action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q")
        main.addItem(submenu(app, title: "Q-Translator"))

        let file = NSMenu(title: "文件")
        file.addItem(withTitle: "新建会话", action: #selector(newSession(_:)), keyEquivalent: "n").target = self
        main.addItem(submenu(file, title: "文件"))

        let features = NSMenu(title: "功能")
        for (module, key, startTitle) in [(AppModule.interpret, "i", "开始同声传译"), (.face, "f", "开始面对面对话"), (.practice, "p", "")] {
            let open = features.addItem(withTitle: module.fullTitle, action: #selector(openModule(_:)), keyEquivalent: key)
            open.keyEquivalentModifierMask = [.command, .shift]
            open.target = self
            open.representedObject = module.rawValue
            if !startTitle.isEmpty {
                let start = features.addItem(withTitle: startTitle, action: #selector(openModule(_:)), keyEquivalent: key)
                start.keyEquivalentModifierMask = [.command, .shift, .option]
                start.isAlternate = true
                start.target = self
                start.representedObject = module.rawValue
                start.tag = 1
            }
        }
        main.addItem(submenu(features, title: "功能"))

        let edit = NSMenu(title: "编辑")
        edit.addItem(withTitle: "撤销", action: Selector(("undo:")), keyEquivalent: "z")
        edit.addItem(withTitle: "重做", action: Selector(("redo:")), keyEquivalent: "Z")
        edit.addItem(.separator())
        edit.addItem(withTitle: "剪切", action: #selector(NSText.cut(_:)), keyEquivalent: "x")
        edit.addItem(withTitle: "拷贝", action: #selector(NSText.copy(_:)), keyEquivalent: "c")
        edit.addItem(withTitle: "粘贴", action: #selector(smartPaste(_:)), keyEquivalent: "v").target = self
        edit.addItem(withTitle: "全选", action: #selector(NSText.selectAll(_:)), keyEquivalent: "a")
        main.addItem(submenu(edit, title: "编辑"))

        let windowMenu = NSMenu(title: "窗口")
        windowMenu.addItem(withTitle: "关闭", action: #selector(NSWindow.performClose(_:)), keyEquivalent: "w")
        windowMenu.addItem(withTitle: "最小化", action: #selector(NSWindow.performMiniaturize(_:)), keyEquivalent: "m")
        main.addItem(submenu(windowMenu, title: "窗口"))

        return main
    }

    private func submenu(_ menu: NSMenu, title: String) -> NSMenuItem {
        let item = NSMenuItem(title: title, action: nil, keyEquivalent: "")
        item.submenu = menu
        return item
    }
}
