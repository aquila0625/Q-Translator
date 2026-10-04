import SwiftUI
import Translation

/// 正在进行的同声传译。它不属于某个页面：离开传译页、切到别的模块，甚至回到手机桌面，都继续收音、转录、翻译和朗读。
/// 结束时整段字幕存成同声传译模块里的一条记录。
@MainActor
final class InterpretSession: ObservableObject {
    static let shared = InterpretSession()

    /// 正在传译（包括暂停中）
    @Published private(set) var interpreter: Interpreter?
    @Published private(set) var translator: LiveTranslator?
    /// 传译页正在显示；false 时在别的页面显示小提示条
    @Published var presented = false

    /// 接着录的那条记录，以及它已经有的字幕和时长
    private(set) var continuing: UUID?
    private(set) var previous: [TranscriptLine] = []
    private(set) var previousDuration: TimeInterval = 0

    var isActive: Bool { interpreter != nil }

    /// 这段传译中途收起过或者回到过桌面（统计用）
    var wentToBackground = false

    /// 开始一段新的传译，或者接着某条记录录（continuing）。已经在传译时只是回到传译页
    func begin(continuing id: UUID?, controller: ConversationController) {
        presented = true
        guard interpreter == nil else { return }
        let record = id.flatMap { ModuleStore.shared.interpretation($0) }
        continuing = record?.id
        previous = record?.lines ?? []
        previousDuration = record?.duration ?? 0
        let translator = LiveTranslator { text, chinese in await controller.translate(text, fromChinese: chinese) }
        let interpreter = Interpreter { text, chinese, live in
            await translator.translate(text, fromChinese: chinese, live: live)
        }
        self.translator = translator
        self.interpreter = interpreter
        Speaker.recordingActive = true
        // 继续录时沿用那条记录的方向（不改设置里的默认方向）
        let chinese = record?.sourceIsChinese ?? UserDefaults.standard.bool(forKey: "interpreter.sourceIsChinese")
        wentToBackground = false
        Analytics.track(.interpretStart, ["direction": Analytics.direction(fromChinese: chinese), "continue": record == nil ? "no" : "yes",
                                          "speak": interpreter.speakTranslations ? "on" : "off"])
        Task {
            await translator.prepare(fromChinese: chinese)
            await interpreter.start(sourceIsChinese: chinese)
            // 后台把另一种语言的识别模型也准备好，中途切换不用等
            await interpreter.prepareOtherLanguage()
        }
    }

    /// 换方向：前面的字幕保留，后面按新语言识别
    func switchDirection(toChinese: Bool) async {
        guard let interpreter, let translator, interpreter.sourceIsChinese != toChinese else { return }
        Analytics.track(.interpretSwitch, ["to": Analytics.direction(fromChinese: toChinese)])
        await interpreter.switchDirection()
        await translator.prepare(fromChinese: toChinese)
        if continuing == nil { UserDefaults.standard.set(interpreter.sourceIsChinese, forKey: "interpreter.sourceIsChinese") }
    }

    /// 结束并保存
    func finish() async {
        guard let interpreter else { return }
        await interpreter.stop()
        let lines = interpreter.segments.map { TranscriptLine(original: $0.original, translation: $0.translation ?? "") }
        Analytics.track(.interpretFinish, ["minutes": Analytics.bucket(Int(interpreter.elapsed / 60), [1, 10, 30, 60]),
                                           "sentences": Analytics.bucket(lines.count, [0, 10, 50, 200]),
                                           "background": wentToBackground ? "yes" : "no"])
        ModuleStore.shared.saveInterpretation(lines, duration: interpreter.elapsed,
                                              sourceIsChinese: interpreter.sourceIsChinese, into: continuing)
        Speaker.shared.stop()
        Speaker.recordingActive = false
        self.interpreter = nil
        translator = nil
        presented = false
        continuing = nil
        previous = []
        previousDuration = 0
    }
}

/// 离开传译页时，在页面上方显示的小提示条：正在传译、时长、最新一句；点一下回到传译页
struct InterpretMiniBar: View {
    @ObservedObject private var session = InterpretSession.shared

    var body: some View {
        if let interpreter = session.interpreter, !session.presented {
            MiniBarContent(interpreter: interpreter, previousDuration: session.previousDuration) {
                ModuleRouter.shared.open(.interpret, from: "mini_bar")
                ModuleRouter.shared.launch = .interpret(continuing: nil)
            }
            .transition(.move(edge: .top).combined(with: .opacity))
        }
    }
}

private struct MiniBarContent: View {
    @ObservedObject var interpreter: Interpreter
    let previousDuration: TimeInterval
    let onOpen: () -> Void

    private var latest: String {
        if !interpreter.live.isEmpty { return interpreter.live }
        guard let last = interpreter.segments.last else { return interpreter.state == .paused ? "已暂停" : "正在听…" }
        return last.translation ?? last.original
    }

    var body: some View {
        Button(action: onOpen) {
            HStack(spacing: 10) {
                Circle()
                    .fill(interpreter.state == .paused ? Color.orange : Color.red)
                    .frame(width: 9, height: 9)
                VStack(alignment: .leading, spacing: 1) {
                    HStack(spacing: 6) {
                        Text(interpreter.state == .paused ? "同声传译 · 已暂停" : "同声传译中")
                            .font(.caption.weight(.bold))
                        TimelineView(.periodic(from: .now, by: 1)) { _ in
                            Text(AudioReplayButton.format(previousDuration + interpreter.elapsed))
                                .font(.caption.monospacedDigit())
                                .foregroundStyle(.secondary)
                        }
                    }
                    Text(latest).font(.footnote).lineLimit(1).foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                Image(systemName: "chevron.up").font(.caption.weight(.bold)).foregroundStyle(.secondary)
            }
            .padding(.horizontal, 14)
            .frame(minHeight: 50)
            .contentShape(.capsule)
        }
        .buttonStyle(.plain)
        .glassEffect(.regular.interactive(), in: .capsule)
        .padding(.horizontal, 16)
        .accessibilityLabel("同声传译正在进行，点按回到传译")
    }
}

/// 把正在进行的传译的本机翻译通道挂在根界面上
struct InterpretTranslationTask: ViewModifier {
    @ObservedObject private var session = InterpretSession.shared

    func body(content: Content) -> some View {
        if let translator = session.translator {
            content.modifier(TranslatorTask(translator: translator))
        } else {
            content
        }
    }
}

private struct TranslatorTask: ViewModifier {
    @ObservedObject var translator: LiveTranslator

    func body(content: Content) -> some View {
        content.translationTask(translator.configuration) { session in await translator.run(session) }
    }
}
