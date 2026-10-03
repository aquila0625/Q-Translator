import SwiftUI
import UIKit

/// 主屏幕长按图标的快捷操作：同声传译、面对面对话、场景练习、新建翻译
final class AppDelegate: NSObject, UIApplicationDelegate {
    /// 冷启动时带进来的快捷操作，等界面起来后再处理
    nonisolated(unsafe) static var pendingShortcut: String?

    func application(_ application: UIApplication, configurationForConnecting connectingSceneSession: UISceneSession,
                     options: UIScene.ConnectionOptions) -> UISceneConfiguration {
        if let item = options.shortcutItem { Self.pendingShortcut = item.type }
        let configuration = UISceneConfiguration(name: nil, sessionRole: connectingSceneSession.role)
        configuration.delegateClass = SceneDelegate.self
        return configuration
    }
}

final class SceneDelegate: NSObject, UIWindowSceneDelegate {
    /// App 已经在后台时点了快捷操作
    func windowScene(_ windowScene: UIWindowScene, performActionFor shortcutItem: UIApplicationShortcutItem,
                     completionHandler: @escaping (Bool) -> Void) {
        let type = shortcutItem.type
        Task { @MainActor in ModuleRouter.shared.handleShortcut(type) }
        completionHandler(true)
    }
}

@main
struct QTranslatorApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var delegate

    var body: some Scene {
        WindowGroup {
            ConversationRootView()
                .task {
                    guard let type = AppDelegate.pendingShortcut else { return }
                    AppDelegate.pendingShortcut = nil
                    try? await Task.sleep(for: .milliseconds(600))
                    ModuleRouter.shared.handleShortcut(type)
                }
        }
    }
}
