import SwiftUI
#if canImport(UMCommon)
import UMCommon
#endif
#if canImport(UIKit)
import UIKit
#endif

/// 匿名使用统计（友盟+）。只统计用了哪些功能，不上报输入的文字、图片和录音。
/// 用户同意后才初始化、才上报；没有 AppKey、或者这个平台没有友盟 SDK（目前是 Mac）时什么都不做。
@MainActor
enum Analytics {
    /// 自定义事件。事件 ID 要先在友盟后台“自定义事件”里添加，名称用 title
    enum Event: String, CaseIterable {
        case wordLookup = "word_lookup"
        case textTranslate = "text_translate"
        case imageTranslate = "image_translate"
        case imageRotate = "image_rotate"
        case imageRecognizeAgain = "image_recognize_again"
        case voiceInput = "voice_input"
        case aiOptimize = "ai_optimize"
        case replyWrite = "reply_write"
        case speak = "speak"
        case wordStar = "word_star"
        case sessionNew = "session_new"
        case sceneNew = "scene_new"
        case moduleOpen = "module_open"
        case quickAction = "quick_action"
        case interpretStart = "interpret_start"
        case interpretFinish = "interpret_finish"
        case interpretSwitch = "interpret_switch"
        case interpretMinimize = "interpret_minimize"
        case interpretSummary = "interpret_summary"
        case faceStart = "face_start"
        case faceFinish = "face_finish"
        case practiceStart = "practice_start"
        case practiceReply = "practice_reply"
        case practiceFinish = "practice_finish"
        case practiceHandsFree = "practice_hands_free"
        case settingsAIProvider = "settings_ai_provider"
        case settingsVoice = "settings_voice"
        case settingsSpeed = "settings_speed"

        var title: String {
            switch self {
            case .wordLookup: "查单词"
            case .textTranslate: "翻译句子"
            case .imageTranslate: "图片翻译"
            case .imageRotate: "旋转图片"
            case .imageRecognizeAgain: "图片重新识别"
            case .voiceInput: "语音输入"
            case .aiOptimize: "AI 优化"
            case .replyWrite: "写回复"
            case .speak: "朗读"
            case .wordStar: "加入生词本"
            case .sessionNew: "新建会话"
            case .sceneNew: "新建场景"
            case .moduleOpen: "打开模块"
            case .quickAction: "主屏快捷操作"
            case .interpretStart: "开始同声传译"
            case .interpretFinish: "结束同声传译"
            case .interpretSwitch: "传译切换语言"
            case .interpretMinimize: "传译收起到后台"
            case .interpretSummary: "传译 AI 要点"
            case .faceStart: "开始面对面对话"
            case .faceFinish: "结束面对面对话"
            case .practiceStart: "开始场景练习"
            case .practiceReply: "练习中回答一句"
            case .practiceFinish: "结束场景练习"
            case .practiceHandsFree: "切换语音聊天"
            case .settingsAIProvider: "设置 AI 服务商"
            case .settingsVoice: "选择音色"
            case .settingsSpeed: "调整朗读速度"
            }
        }
    }

    private static let consentKey = "analytics.consent"
    private static var started = false

    /// 这台设备用的 AppKey：iPad 和 iPhone 分开统计；没填就是 nil
    private static var appKey: String? {
        #if os(iOS)
        let name = UIDevice.current.userInterfaceIdiom == .pad ? "UMengAppKeyPad" : "UMengAppKeyPhone"
        #else
        let name = "UMengAppKeyMac"
        #endif
        let key = (Bundle.main.object(forInfoDictionaryKey: name) as? String)?.trimmingCharacters(in: .whitespaces) ?? ""
        return key.isEmpty || key.hasPrefix("$(") ? nil : key
    }

    /// 这个版本能发统计（有 AppKey，也有友盟 SDK）
    static var isAvailable: Bool {
        #if canImport(UMCommon)
        appKey != nil
        #else
        false
        #endif
    }

    /// 用户的选择：nil 还没问过，true 同意，false 不同意
    static var consent: Bool? {
        get { UserDefaults.standard.object(forKey: consentKey) as? Bool }
        set {
            UserDefaults.standard.set(newValue, forKey: consentKey)
            if newValue == true { start() } else { stop() }
        }
    }

    /// 启动时调用：用户同意过才初始化
    static func startIfAllowed() {
        if consent == true { start() }
    }

    private static func start() {
        #if canImport(UMCommon)
        guard !started, let appKey else { return }
        started = true
        #if DEBUG
        UMConfigure.setLogEnabled(true)
        #endif
        UMConfigure.setAnalyticsEnabled(true)
        UMConfigure.initWithAppkey(appKey, channel: "App Store")
        #endif
    }

    private static func stop() {
        #if canImport(UMCommon)
        guard started else { return }
        UMConfigure.setAnalyticsEnabled(false)
        #endif
    }

    private static var enabled: Bool { started && consent == true }

    /// 记录一次事件。属性只放功能相关的分类（方向、来源、档位），不放用户输入的内容
    static func track(_ event: Event, _ attributes: [String: String] = [:]) {
        #if canImport(UMCommon)
        guard enabled else { return }
        if attributes.isEmpty {
            MobClick.event(event.rawValue)
        } else {
            MobClick.event(event.rawValue, attributes: attributes)
        }
        #endif
    }

    static func beginPage(_ name: String) {
        #if canImport(UMCommon)
        guard enabled else { return }
        MobClick.beginLogPageView(name)
        #endif
    }

    static func endPage(_ name: String) {
        #if canImport(UMCommon)
        guard enabled else { return }
        MobClick.endLogPageView(name)
        #endif
    }

    /// 把数字分成几档，统计里看分布，不记具体数字
    static func bucket(_ value: Int, _ edges: [Int]) -> String {
        for (i, edge) in edges.enumerated() where value <= edge {
            return i == 0 ? "≤\(edge)" : "\(edges[i - 1] + 1)-\(edge)"
        }
        return ">\(edges.last ?? 0)"
    }

    static func direction(fromChinese: Bool) -> String { fromChinese ? "zh2en" : "en2zh" }
}

extension View {
    /// 页面统计：显示时开始计时，离开时结束
    func analyticsPage(_ name: String) -> some View {
        onAppear { Analytics.beginPage(name) }
            .onDisappear { Analytics.endPage(name) }
    }

    /// 第一次打开时问一次要不要发送匿名使用统计（没有统计功能的版本不问）
    func analyticsConsentPrompt() -> some View {
        modifier(AnalyticsConsentPrompt())
    }
}

private struct AnalyticsConsentPrompt: ViewModifier {
    @State private var asking = false

    func body(content: Content) -> some View {
        content
            .task {
                Analytics.startIfAllowed()
                guard Analytics.isAvailable, Analytics.consent == nil else { return }
                try? await Task.sleep(for: .seconds(1))
                asking = true
            }
            .alert("帮助改进快译", isPresented: $asking) {
                Button("不允许", role: .cancel) { Analytics.consent = false }
                Button("允许") { Analytics.consent = true }
            } message: {
                Text("允许发送匿名使用统计吗？只统计用了哪些功能（比如查词、拍照翻译、同声传译），不包含你输入的文字、图片和录音。统计由友盟+处理，随时可以在设置里关闭。")
            }
    }
}
