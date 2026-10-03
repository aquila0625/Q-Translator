import SwiftUI

/// 配色：白底、亮蓝主色，橙色只用于 AI 相关的内容。
extension Color {
    static let lxAccent = Color(light: 0x0B6BF0, dark: 0x62A8FF)
    static let lxOnAccent = Color(light: 0xFFFFFF, dark: 0x04142B)
    static let lxAccentSoft = Color(light: 0xDCEBFF, dark: 0x12305A)
    static let lxAI = Color(light: 0xB8430A, dark: 0xFFAE78)
    static let lxAISoft = Color(light: 0xFFEEDD, dark: 0x3A2210)
    static let lxBackground = Color(light: 0xFFFFFF, dark: 0x0B0E13)
    static let lxWash = Color(light: 0xE4F0FF, dark: 0x0F1B2E)
    static let lxSurface = Color(light: 0xF1F7FF, dark: 0x161B23)
    /// 会话里句子译文卡片的底色（浅薄荷绿），和蓝色的单词卡片、橙色的 AI 分开
    static let lxSentenceCard = Color(light: 0xEEF7F2, dark: 0x15221C)
    /// 会话里图片译文卡片的底色（中性浅灰）
    static let lxImageCard = Color(light: 0xF3F4F6, dark: 0x1A1D22)
    /// 和卡片底色配套的深色：用于筛选按钮的文字和选中时的底色
    static let lxSentenceInk = Color(light: 0x1E7A45, dark: 0x7FD6A3)
    static let lxImageInk = Color(light: 0x4A5565, dark: 0xAEB8C4)
    /// 面对面对话的卡片（浅青色）
    static let lxDialogCard = Color(light: 0xE6F5F4, dark: 0x0F2726)
    static let lxDialogInk = Color(light: 0x0B6B66, dark: 0x6FD3CC)
    /// 同声传译记录的卡片（浅琥珀色）
    static let lxTranscriptCard = Color(light: 0xFFF6E3, dark: 0x2A2210)
    static let lxTranscriptInk = Color(light: 0x8A5A00, dark: 0xF2C14E)
    /// 场景练习记录的卡片（浅玫瑰色）
    static let lxPracticeCard = Color(light: 0xFDEFF3, dark: 0x2A1620)
    static let lxPracticeInk = Color(light: 0xB0305C, dark: 0xF59AB8)

    init(light: UInt32, dark: UInt32) {
        #if os(macOS)
        self.init(nsColor: NSColor(name: nil) { appearance in
            NSColor(hex: appearance.bestMatch(from: [.aqua, .darkAqua]) == .darkAqua ? dark : light)
        })
        #else
        self.init(uiColor: UIColor { traits in
            UIColor(hex: traits.userInterfaceStyle == .dark ? dark : light)
        })
        #endif
    }
}

#if os(macOS)
private extension NSColor {
    convenience init(hex: UInt32) {
        self.init(srgbRed: CGFloat((hex >> 16) & 0xFF) / 255, green: CGFloat((hex >> 8) & 0xFF) / 255,
                  blue: CGFloat(hex & 0xFF) / 255, alpha: 1)
    }
}
#else
private extension UIColor {
    convenience init(hex: UInt32) {
        self.init(red: CGFloat((hex >> 16) & 0xFF) / 255, green: CGFloat((hex >> 8) & 0xFF) / 255,
                  blue: CGFloat(hex & 0xFF) / 255, alpha: 1)
    }
}
#endif

/// 页面背景：顶部一点很浅的蓝色，向下过渡到纯色
struct WashBackground: View {
    var body: some View {
        LinearGradient(colors: [.lxWash, .lxBackground], startPoint: .top, endPoint: UnitPoint(x: 0.5, y: 0.35))
            .ignoresSafeArea()
    }
}
