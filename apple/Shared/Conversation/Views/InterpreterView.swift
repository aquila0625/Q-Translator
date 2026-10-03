import SwiftUI

/// 同声传译：听讲座、开会时用。持续收音，滚动显示双语字幕，上面原文、下面译文，正在说的那句是蓝底。
/// 结束时整段字幕存成一条“传译记录”。
struct InterpreterView: View {
    @ObservedObject var controller: ConversationController
    @StateObject private var interpreter: Interpreter
    @StateObject private var translator: LiveTranslator
    @Environment(\.dismiss) private var dismiss
    @AppStorage("interpreter.sourceIsChinese") private var sourceIsChinese = false
    /// 已经保存过（点了结束）；用其他方式关掉时也要保存
    @State private var saved = false

    /// 接着录的那条记录，以及它已经有的字幕和时长
    private let continuing: UUID?
    private let previous: [TranscriptLine]
    private let previousDuration: TimeInterval

    init(controller: ConversationController) {
        self.controller = controller
        let turn = controller.interpreterContinue.flatMap { controller.store.turn(controller.currentID, $0) }
        continuing = turn?.id
        previous = turn?.transcript ?? []
        previousDuration = turn?.transcriptDuration ?? 0
        let translator = LiveTranslator { text, chinese in await controller.translate(text, fromChinese: chinese) }
        _translator = StateObject(wrappedValue: translator)
        _interpreter = StateObject(wrappedValue: Interpreter { text, chinese, live in
            await translator.translate(text, fromChinese: chinese, live: live)
        })
    }

    var body: some View {
        VStack(spacing: 0) {
            topBar
            subtitles
            bottomBar
        }
        .background { WashBackground().ignoresSafeArea() }
        // 专用的本机翻译通道，一直开着
        .translationTask(translator.configuration) { session in await translator.run(session) }
        .task {
            // 继续录时沿用那条记录的方向（不改设置里的默认方向）
            let chinese = continuing.flatMap { controller.store.turn(controller.currentID, $0)?.sourceIsChinese } ?? sourceIsChinese
            await translator.prepare(fromChinese: chinese)
            await interpreter.start(sourceIsChinese: chinese)
        }
        .onDisappear {
            guard !saved else { return }
            saved = true
            Task {
                await interpreter.stop()
                save()
            }
        }
        #if os(macOS)
        .frame(minWidth: 560, minHeight: 720)
        #endif
    }

    // MARK: 顶栏

    private var topBar: some View {
        HStack(spacing: 10) {
            GlassIconButton(systemName: "xmark", label: "结束并保存") { finish() }
            Button {
                Task {
                    await translator.prepare(fromChinese: !interpreter.sourceIsChinese)
                    await interpreter.switchDirection()
                    if continuing == nil { sourceIsChinese = interpreter.sourceIsChinese }
                }
            } label: {
                HStack(spacing: 8) {
                    if interpreter.state == .running {
                        Circle().fill(Color.red).frame(width: 8, height: 8)
                    }
                    Text("同声传译 · " + (interpreter.sourceIsChinese ? "中 → 英" : "英 → 中"))
                        .font(.callout.weight(.semibold))
                    Image(systemName: "arrow.left.arrow.right").font(.caption.weight(.bold)).foregroundStyle(.secondary)
                    TimelineView(.periodic(from: .now, by: 1)) { _ in
                        Text(AudioReplayButton.format(previousDuration + interpreter.elapsed))
                            .font(.callout.monospacedDigit())
                            .foregroundStyle(.secondary)
                    }
                }
                .padding(.horizontal, 14)
                .frame(maxWidth: .infinity, minHeight: 44)
                .contentShape(.capsule)
            }
            .buttonStyle(.plain)
            .glassEffect(.regular.interactive(), in: .capsule)
            .accessibilityLabel("翻译方向，点按切换")
            GlassIconButton(systemName: interpreter.speakTranslations ? "headphones" : "speaker.slash",
                            label: interpreter.speakTranslations ? "关闭耳机朗读" : "用耳机朗读译文",
                            tint: interpreter.speakTranslations ? .lxAccent : .primary) {
                interpreter.speakTranslations.toggle()
                if !interpreter.speakTranslations { Speaker.shared.stop() }
            }
        }
        .padding(.horizontal, 12)
        .padding(.top, 8)
    }

    // MARK: 字幕

    private var subtitles: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 10) {
                    // 继续录：先显示之前录过的字幕，中间用一条分隔线隔开
                    ForEach(previous) { line in
                        VStack(alignment: .leading, spacing: 3) {
                            Text(line.original).font(.callout).foregroundStyle(.tertiary)
                            Text(line.translation).font(.system(size: 17, weight: .medium)).foregroundStyle(.secondary)
                        }
                        .padding(.horizontal, 12)
                        .padding(.vertical, 6)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    if !previous.isEmpty {
                        HStack {
                            VStack { Divider() }
                            Text("继续 · " + Date().formatted(date: .omitted, time: .shortened))
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(Color.lxTranscriptInk)
                            VStack { Divider() }
                        }
                        .padding(.vertical, 6)
                    }
                    ForEach(interpreter.segments) { segment in
                        VStack(alignment: .leading, spacing: 3) {
                            Text(segment.original)
                                .font(.callout)
                                .foregroundStyle(.secondary)
                            if let translation = segment.translation {
                                Text(translation)
                                    .font(.system(size: 19, weight: .semibold))
                            } else {
                                ProgressView().controlSize(.small)
                            }
                        }
                        .textSelection(.enabled)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 8)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .id(segment.id)
                    }
                    if !interpreter.live.isEmpty {
                        // 还没说完的那句：原文和边说边翻的译文一起往下长
                        VStack(alignment: .leading, spacing: 3) {
                            Text(interpreter.live + "…")
                                .font(.callout)
                                .foregroundStyle(.secondary)
                            if !interpreter.liveTranslation.isEmpty {
                                Text(interpreter.liveTranslation + "…")
                                    .font(.system(size: 19, weight: .semibold))
                            }
                        }
                        .padding(12)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(Color.lxAccentSoft, in: .rect(cornerRadius: 14))
                        .animation(.snappy, value: interpreter.liveTranslation)
                        .id("live")
                    }
                    Color.clear.frame(height: 1).id("bottom")
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 12)
            }
            .overlay { statusOverlay }
            .onChange(of: interpreter.segments.count) { withAnimation { proxy.scrollTo("bottom", anchor: .bottom) } }
            .onChange(of: interpreter.live) { proxy.scrollTo("bottom", anchor: .bottom) }
            .onChange(of: interpreter.liveTranslation) { proxy.scrollTo("bottom", anchor: .bottom) }
        }
    }

    @ViewBuilder
    private var statusOverlay: some View {
        switch interpreter.state {
        case .preparing(let message):
            ProgressView(message).padding(16).background(.regularMaterial, in: .rect(cornerRadius: 14))
        case .failed(let message):
            VStack(spacing: 12) {
                Label(message, systemImage: "exclamationmark.triangle").multilineTextAlignment(.center)
                Button("重试") { Task { await interpreter.start(sourceIsChinese: sourceIsChinese) } }
                    .buttonStyle(.glassProminent)
            }
            .padding(20)
        default:
            if previous.isEmpty, interpreter.segments.isEmpty, interpreter.live.isEmpty, interpreter.state == .running {
                VStack(spacing: 8) {
                    Image(systemName: "waveform").font(.largeTitle).foregroundStyle(Color.lxAccent)
                    Text(interpreter.sourceIsChinese ? "正在听中文，说完一句就会翻译" : "正在听英语，说完一句就会翻译")
                        .foregroundStyle(.secondary)
                    Text((interpreter.engineName.isEmpty ? "" : interpreter.engineName + " · ")
                         + (translator.onDevice ? "本机翻译" : "在线翻译") + " · 录音不上传")
                        .font(.footnote).foregroundStyle(.tertiary)
                }
            }
        }
    }

    // MARK: 底栏

    private var bottomBar: some View {
        HStack(spacing: 14) {
            VoiceWave(levels: interpreter.levels)
            Spacer(minLength: 0)
            if interpreter.state == .running || interpreter.state == .paused {
                GlassIconButton(systemName: interpreter.state == .paused ? "play.fill" : "pause.fill",
                                label: interpreter.state == .paused ? "继续" : "暂停") {
                    interpreter.state == .paused ? interpreter.resume() : interpreter.pause()
                }
            }
            Button(action: finish) {
                Image(systemName: "stop.fill")
                    .font(.system(size: 22, weight: .bold))
                    .foregroundStyle(.white)
                    .frame(width: 64, height: 64)
                    .background(Color.red, in: .circle)
                    .shadow(color: .red.opacity(0.25), radius: 8)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("结束并保存")
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
    }

    private func finish() {
        guard !saved else { return }
        saved = true
        Task {
            await interpreter.stop()
            save()
            dismiss()
        }
    }

    private func save() {
        controller.saveTranscript(interpreter.segments, duration: interpreter.elapsed,
                                  sourceIsChinese: interpreter.sourceIsChinese, into: continuing)
        Speaker.shared.stop()
    }
}

/// 会话里保存的同传记录：先显示前几句，可以展开全部；可以复制全部字幕
struct TranscriptCard: View {
    let lines: [TranscriptLine]
    let duration: Double?
    let sourceIsChinese: Bool
    let date: Date
    let expanded: Bool
    let onToggleExpand: () -> Void
    /// 接着往这条记录里录（比如讲座中间休息完）
    var onContinue: () -> Void = {}

    /// 收起时显示最后三句：继续录的时候最新的内容在下面
    private var shown: [TranscriptLine] { expanded ? lines : Array(lines.suffix(3)) }

    private var allText: String {
        lines.map { $0.original + "\n" + $0.translation }.joined(separator: "\n\n")
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 6) {
                Label("同声传译 · \(lines.count) 句" + (duration.map { " · " + AudioReplayButton.format($0) } ?? "")
                      + " · " + (sourceIsChinese ? "中 → 英" : "英 → 中"), systemImage: "captions.bubble.fill")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(Color.lxTranscriptInk)
                Spacer(minLength: 0)
                CopyButton(text: allText, label: "复制全部字幕", title: "复制全部")
            }
            if !expanded, lines.count > 3 {
                Text("… 前面还有 \(lines.count - 3) 句").font(.caption).foregroundStyle(.tertiary)
            }
            ForEach(shown) { line in
                VStack(alignment: .leading, spacing: 2) {
                    Text(line.original).font(.footnote).foregroundStyle(.secondary)
                    Text(line.translation).font(.system(size: 16, weight: .medium))
                }
                .textSelection(.enabled)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            HStack {
                if lines.count > 3 {
                    Button(action: onToggleExpand) {
                        Label(expanded ? "收起" : "展开全部 \(lines.count) 句", systemImage: expanded ? "chevron.up" : "chevron.down")
                            .font(.footnote.weight(.semibold))
                            .frame(minHeight: 36)
                            .contentShape(.rect)
                    }
                    .buttonStyle(.plain)
                    .foregroundStyle(Color.lxTranscriptInk)
                }
                Spacer(minLength: 0)
                Button(action: onContinue) {
                    Label("继续传译", systemImage: "mic.fill")
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(Color.lxBackground)
                        .padding(.horizontal, 14)
                        .frame(height: 32)
                        .background(Color.lxTranscriptInk, in: .capsule)
                        .frame(minHeight: 44)
                        .contentShape(.rect)
                }
                .buttonStyle(.plain)
                .accessibilityHint("接着往这条记录里录")
            }
        }
        .padding(12)
        .background(Color.lxTranscriptCard, in: .rect(cornerRadius: 18))
    }
}
