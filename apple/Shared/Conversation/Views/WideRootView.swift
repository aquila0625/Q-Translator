import SwiftUI
import Translation
import UniformTypeIdentifiers

/// 根界面弹出的面板：设置、新建会话、新建或编辑场景、生词本
enum RootSheet: Identifiable {
    case settings, newSession, newScene, editScene(UUID), starred

    var id: String {
        switch self {
        case .settings: "settings"
        case .newSession: "newSession"
        case .newScene: "newScene"
        case .editScene(let id): "scene-" + id.uuidString
        case .starred: "starred"
        }
    }

    init(_ action: DrawerAction) {
        switch action {
        case .newSession: self = .newSession
        case .newScene: self = .newScene
        case .editScene(let id): self = .editScene(id)
        case .starred: self = .starred
        case .settings: self = .settings
        }
    }
}

struct RootSheetContent: View {
    let sheet: RootSheet
    @ObservedObject var controller: ConversationController

    var body: some View {
        switch sheet {
        case .settings: SettingsView()
        case .newSession: NewSessionView(controller: controller, store: controller.store)
        case .newScene: SceneEditorView(store: controller.store, sceneID: nil)
        case .editScene(let id): SceneEditorView(store: controller.store, sceneID: id)
        case .starred: StarredWordsView { await controller.quickTranslate($0) }
        }
    }
}

extension Notification.Name {
    /// 菜单里的“设置…”
    static let openSettings = Notification.Name("QTranslator.openSettings")
}

/// 宽屏布局（Mac、iPad）：左边常驻场景和会话，中间是会话，右边是输入记录
struct WideRootView: View {
    @ObservedObject var controller: ConversationController
    @State private var sheet: RootSheet?
    @State private var dropTargeted = false
    @ObservedObject private var router = ModuleRouter.shared

    var body: some View {
        GeometryReader { geometry in
            HStack(spacing: 0) {
                DrawerView(controller: controller, store: controller.store, onSelect: {}) { sheet = RootSheet($0) }
                    .frame(width: 300)
                    .background(Color.lxBackground.ignoresSafeArea())
                Divider().ignoresSafeArea()
                ConversationView(controller: controller, store: controller.store, screenHeight: geometry.size.height,
                                 onMenu: {}, onNewSession: { sheet = .newSession }, onSettings: { sheet = .settings },
                                 wide: true, railAllowed: geometry.size.width - 300 >= 520 + 270)
                    .background { WashBackground() }
                    .overlay {
                        if dropTargeted {
                            RoundedRectangle(cornerRadius: 18)
                                .strokeBorder(Color.lxAccent, style: StrokeStyle(lineWidth: 2, dash: [8, 6]))
                                .background(Color.lxAccentSoft.opacity(0.35), in: .rect(cornerRadius: 18))
                                .overlay { Label("松手加入图片", systemImage: "photo.on.rectangle").font(.headline).foregroundStyle(Color.lxAccent) }
                                .padding(12)
                                .allowsHitTesting(false)
                        }
                    }
                    // 把图片拖进会话：作为一轮翻译
                    .onDrop(of: [.image, .fileURL], isTargeted: $dropTargeted) { providers in
                        Task {
                            let images = await ImageDrop.load(providers)
                            if !images.isEmpty { controller.attachImages(images, source: "drop") }
                        }
                        return true
                    }
                    // 三个模块：显示在右边，盖在翻译上；点左边的会话回到翻译
                    .overlay {
                        if let module = router.module {
                            ModulePage(module: module, controller: controller, wide: true)
                                .id(module)
                                .background(Color.lxBackground.ignoresSafeArea())
                        }
                    }
            }
        }
        .tint(.lxAccent)
        .translationTask(controller.translator.configuration) { session in
            await controller.translator.run(session)
        }
        // 同声传译的翻译通道挂在根界面上：离开传译页、回到桌面都继续翻译
        .modifier(InterpretTranslationTask())
        .sheet(item: $sheet) { RootSheetContent(sheet: $0, controller: controller) }
        .appAppearance()
        #if os(macOS)
        .analyticsConsentPrompt()
        #endif
        .onReceive(NotificationCenter.default.publisher(for: .openSettings)) { _ in sheet = .settings }
    }
}

/// 从拖进来的内容里取出图片：图片数据或图片文件
enum ImageDrop {
    static func load(_ providers: [NSItemProvider]) async -> [PlatformImage] {
        var images: [PlatformImage] = []
        for provider in providers {
            if provider.hasItemConformingToTypeIdentifier(UTType.image.identifier),
               let data = await data(provider, type: .image), let image = PlatformImage(data: data) {
                images.append(image)
            } else if provider.hasItemConformingToTypeIdentifier(UTType.fileURL.identifier),
                      let url = await fileURL(provider), let image = PlatformImage(contentsOfFile: url.path) {
                images.append(image)
            }
        }
        return images
    }

    private static func data(_ provider: NSItemProvider, type: UTType) async -> Data? {
        await withCheckedContinuation { continuation in
            provider.loadDataRepresentation(forTypeIdentifier: type.identifier) { data, _ in
                continuation.resume(returning: data)
            }
        }
    }

    private static func fileURL(_ provider: NSItemProvider) async -> URL? {
        await withCheckedContinuation { continuation in
            _ = provider.loadObject(ofClass: URL.self) { url, _ in
                continuation.resume(returning: url)
            }
        }
    }
}
