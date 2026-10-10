import SwiftUI
#if canImport(UMCommon)
import UMCommon
#endif
#if canImport(UIKit)
import UIKit
#endif
#if canImport(AppKit)
import AppKit
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
        case interpretRefine = "interpret_refine"
        case interpretExport = "interpret_export"
        case interpretEdit = "interpret_edit"
        case faceStart = "face_start"
        case faceFinish = "face_finish"
        case practiceStart = "practice_start"
        case practiceReply = "practice_reply"
        case practiceFinish = "practice_finish"
        case practiceHandsFree = "practice_hands_free"
        case settingsAIProvider = "settings_ai_provider"
        case settingsVoice = "settings_voice"
        case settingsSpeed = "settings_speed"
        case updatePrompt = "update_prompt"
        case updateAccept = "update_accept"
        case updateSkip = "update_skip"
        case cameraLiveOpen = "camera_live_open"
        case cameraLiveShutter = "camera_live_shutter"
        case cameraLiveAI = "camera_live_ai"
        case cameraLiveFlip = "camera_live_flip"
        case cameraLiveDirection = "camera_live_direction"
        case cameraLiveTranslateSwitch = "camera_live_translate_switch"

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
            case .interpretRefine: "传译 AI 精校"
            case .interpretExport: "导出传译"
            case .interpretEdit: "修改传译字幕"
            case .faceStart: "开始面对面对话"
            case .faceFinish: "结束面对面对话"
            case .practiceStart: "开始场景练习"
            case .practiceReply: "练习中回答一句"
            case .practiceFinish: "结束场景练习"
            case .practiceHandsFree: "切换语音聊天"
            case .settingsAIProvider: "设置 AI 服务商"
            case .settingsVoice: "选择音色"
            case .settingsSpeed: "调整朗读速度"
            case .updatePrompt: "弹出更新提示"
            case .updateAccept: "点击更新"
            case .updateSkip: "跳过此版本"
            case .cameraLiveOpen: "打开扫描翻译"
            case .cameraLiveShutter: "扫描翻译拍照"
            case .cameraLiveAI: "扫描翻译 AI 翻译"
            case .cameraLiveFlip: "扫描翻译切换前后摄像头"
            case .cameraLiveDirection: "扫描翻译切换方向"
            case .cameraLiveTranslateSwitch: "扫描翻译开关自动扫描"
            }
        }
    }

    private static let offKey = "privacy.statsOff.v1"
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

    /// 这个版本带统计（有 AppKey，也有友盟 SDK）。没有的版本（比如从源码编译的）不收集任何数据，也不用弹隐私协议
    static var isAvailable: Bool {
        #if canImport(UMCommon)
        appKey != nil
        #else
        false
        #endif
    }

    /// 统计默认开启；用户可以在“设置 → 关于 → 隐私协议”里自己关闭
    static var accepted: Bool {
        get { !UserDefaults.standard.bool(forKey: offKey) }
        set {
            UserDefaults.standard.set(!newValue, forKey: offKey)
            if newValue { start() } else { stop() }
        }
    }

    private static func stop() {
        #if canImport(UMCommon)
        guard started else { return }
        UMConfigure.setAnalyticsEnabled(false)
        #endif
    }

    /// 启动时调用：同意过隐私协议才初始化
    static func startIfAllowed() {
        if accepted { start() }
    }

    private static func start() {
        #if canImport(UMCommon)
        guard let appKey else { return }
        if started {
            UMConfigure.setAnalyticsEnabled(true)
            return
        }
        started = true
        #if DEBUG
        UMConfigure.setLogEnabled(true)
        #endif
        UMConfigure.setAnalyticsEnabled(true)
        UMConfigure.initWithAppkey(appKey, channel: "App Store")
        #endif
    }

    private static var enabled: Bool { started && accepted }

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

    /// 启动时开始统计（用户没关闭的话）
    func analyticsStart() -> some View {
        task { Analytics.startIfAllowed() }
    }
}

/// 隐私协议全文。同样的内容在 docs/PRIVACY.md 里
enum PrivacyPolicy {
    static let title = "隐私协议"

    static let sections: [(String, String)] = [
        ("我们收集什么", "为了了解哪些功能有用、把快译做得更好，快译会收集匿名的使用数据：你使用了哪些功能（例如查词、翻译、拍照翻译、同声传译、面对面对话、场景练习）、功能的使用时长和次数、你的设备型号、系统版本和 App 版本。这些数据不能识别你是谁。"),
        ("我们不收集什么", "你输入或说出的内容、翻译的文字、拍摄或选择的图片、录音、会话和记录的内容、生词本、你的 API Key，都不会上传给我们或统计服务商。"),
        ("数据交给谁处理", "使用数据由友盟+（Umeng）提供的统计服务处理，详见友盟+的隐私政策。快译没有自己的服务器。"),
        ("你的内容在哪里", "会话、图片、录音和各种记录只保存在你的设备上，删除 App 就会一起删除。翻译和 AI 功能会按你的选择，把需要翻译的文字发给翻译服务或你自己填写的 AI 服务商。同声传译的录音只有在你点“精校”并确认后，才会用你自己的 OpenAI API Key 从设备直接发给 OpenAI 做语音转写，不经过我们的服务器。"),
        ("你的选择", "快译默认统计匿名使用数据。你可以随时在下面关闭，关闭后快译的全部功能照常使用，只是不再统计。"),
    ]
}

/// 隐私协议页面：首次打开时要同意才能继续；设置里也能随时查看
struct PrivacyPolicyView: View {
    @State private var accepted = Analytics.accepted

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                ForEach(PrivacyPolicy.sections, id: \.0) { section in
                    VStack(alignment: .leading, spacing: 6) {
                        Text(section.0).font(.headline)
                        Text(section.1).font(.subheadline).foregroundStyle(.secondary)
                    }
                }
                if Analytics.isAvailable {
                    Toggle("发送匿名使用统计", isOn: $accepted)
                        .onChange(of: accepted) { _, on in Analytics.accepted = on }
                }
            }
            .padding(20)
            .frame(maxWidth: 560, alignment: .leading)
            .frame(maxWidth: .infinity)
        }
        .navigationTitle(PrivacyPolicy.title)
        .inlineNavigationTitle()
    }
}
