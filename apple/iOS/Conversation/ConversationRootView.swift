import SwiftUI
import Translation

/// 根界面。iPhone（和 iPad 分屏的窄窗口）用抽屉：拉开时会话整体被推到右边；
/// iPad 全屏时用和 Mac 一样的宽屏布局，左边常驻会话列表。
struct ConversationRootView: View {
    @StateObject private var controller = ConversationController()
    @Environment(\.horizontalSizeClass) private var sizeClass

    var body: some View {
        Group {
            if sizeClass == .regular {
                WideRootView(controller: controller)
            } else {
                PhoneRootView(controller: controller)
            }
        }
        .appAppearance()
        .privacyGate()
    }
}

struct PhoneRootView: View {
    @ObservedObject var controller: ConversationController
    @State private var drawerOpen = false
    @State private var dragOffset: CGFloat = 0
    @State private var sheet: RootSheet?
    @ObservedObject private var router = ModuleRouter.shared

    var body: some View {
        GeometryReader { geometry in
            let drawerWidth = min(320, geometry.size.width * 0.82)
            let fullHeight = geometry.size.height + geometry.safeAreaInsets.top + geometry.safeAreaInsets.bottom
            let offset = drawerOpen ? max(0, drawerWidth + dragOffset) : max(0, dragOffset)

            ZStack(alignment: .topLeading) {
                DrawerView(controller: controller, store: controller.store,
                           onSelect: { setDrawer(false) },
                           onAction: { sheet = RootSheet($0) })
                    .frame(width: drawerWidth)

                ConversationView(controller: controller, store: controller.store, screenHeight: fullHeight,
                                 onMenu: { setDrawer(true) },
                                 onNewSession: { sheet = .newSession },
                                 onSettings: { sheet = .settings })
                    .frame(width: geometry.size.width, height: geometry.size.height)
                    .background { WashBackground() }
                    // 遮罩要盖住状态栏和底部安全区，背景才能一直铺满
                    .mask { RoundedRectangle(cornerRadius: offset > 0 ? 36 : 0).ignoresSafeArea() }
                    .shadow(color: .black.opacity(offset > 0 ? 0.18 : 0), radius: 24, x: -6)
                    .overlay {
                        if drawerOpen {
                            Color.black.opacity(0.06)
                                .clipShape(.rect(cornerRadius: 36))
                                .contentShape(.rect)
                                .onTapGesture { setDrawer(false) }
                                .accessibilityLabel("关闭会话列表")
                        }
                    }
                    .offset(x: offset)
                    .simultaneousGesture(dragGesture(drawerWidth: drawerWidth))
            }
        }
        .tint(.lxAccent)
        .translationTask(controller.translator.configuration) { session in
            await controller.translator.run(session)
        }
        // 同声传译的翻译通道挂在根界面上：离开传译页、回到桌面都继续翻译
        .modifier(InterpretTranslationTask())
        .sheet(item: $sheet) { RootSheetContent(sheet: $0, controller: controller) }
        // 三个模块：全屏盖在翻译上，左上角返回
        .fullScreenCover(item: $router.module) { ModulePage(module: $0, controller: controller) }
        // 打开模块页时收起会话输入框的键盘，免得它盖在模块页上
        .onChange(of: router.module) { _, module in
            if module != nil {
                UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
            }
        }
        .onReceive(NotificationCenter.default.publisher(for: .openSettings)) { _ in sheet = .settings }
        // 主屏快捷操作“新建翻译”
        .onChange(of: router.newSessionRequested) { _, requested in
            guard requested else { return }
            router.newSessionRequested = false
            setDrawer(false)
            controller.newSession()
        }
    }

    private func setDrawer(_ open: Bool) {
        if open {
            UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
        }
        withAnimation(.snappy(duration: 0.3)) {
            drawerOpen = open
            dragOffset = 0
        }
    }

    /// 从屏幕左边缘向右滑拉开；拉开后向左滑收起
    private func dragGesture(drawerWidth: CGFloat) -> some Gesture {
        DragGesture(minimumDistance: 14, coordinateSpace: .global)
            .onChanged { value in
                guard abs(value.translation.width) > abs(value.translation.height) else { return }
                if drawerOpen {
                    dragOffset = min(0, value.translation.width)
                } else if value.startLocation.x < 32 {
                    dragOffset = min(drawerWidth, max(0, value.translation.width))
                }
            }
            .onEnded { value in
                if drawerOpen {
                    setDrawer(value.translation.width > -60)
                } else if value.startLocation.x < 32 {
                    setDrawer(value.translation.width > 60)
                } else {
                    setDrawer(false)
                }
            }
    }
}
