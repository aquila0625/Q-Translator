import SwiftUI

/// 同声传译：听讲座、开会时用。持续收音，滚动显示双语字幕，上面原文、下面译文，正在说的那句是蓝底。
/// 结束时整段字幕存成同声传译模块里的一条记录。
struct InterpreterView: View {
    @ObservedObject private var session = InterpretSession.shared

    var body: some View {
        if let interpreter = session.interpreter, let translator = session.translator {
            InterpreterScreen(session: session, interpreter: interpreter, translator: translator)
        } else {
            InterpreterClosed()
        }
    }
}

/// 传译已经结束（比如在别处点了结束）：把这一页关掉
private struct InterpreterClosed: View {
    @Environment(\.dismiss) private var dismiss
    var body: some View { Color.clear.onAppear { dismiss() } }
}

private struct InterpreterScreen: View {
    @ObservedObject var session: InterpretSession
    @ObservedObject var interpreter: Interpreter
    @ObservedObject var translator: LiveTranslator
    @Environment(\.dismiss) private var dismiss
    /// 显示方式：0 对照，1 只看原文，2 只看译文
    @AppStorage("interpreter.display") private var display = 0
    @State private var finishing = false

    private var previous: [TranscriptLine] { session.previous }
    private var previousDuration: TimeInterval { session.previousDuration }

    var body: some View {
        VStack(spacing: 0) {
            topBar
            Picker("显示", selection: $display) {
                Text("对照").tag(0)
                Text("原文").tag(1)
                Text("译文").tag(2)
            }
            .pickerStyle(.segmented)
            .padding(.horizontal, 16)
            .padding(.top, 10)
            .padding(.bottom, 4)
            subtitles
            bottomBar
        }
        .background { WashBackground().ignoresSafeArea() }
        .onAppear { session.presented = true }
        // 关掉这一页不等于结束：传译在后台继续，别的页面上方显示小提示条
        .onDisappear { session.presented = false }
        #if os(macOS)
        .frame(minWidth: 560, minHeight: 720)
        #endif
    }

    // MARK: 顶栏

    private var topBar: some View {
        HStack(spacing: 10) {
            GlassIconButton(systemName: "chevron.down", label: "收起，传译在后台继续") { dismiss() }
            // 点开下拉选语言，不会一碰就切换
            Menu {
                directionOption(fromChinese: false)
                directionOption(fromChinese: true)
            } label: {
                HStack(spacing: 8) {
                    if interpreter.state == .running {
                        Circle().fill(Color.red).frame(width: 8, height: 8)
                    }
                    Text("同声传译 · " + (interpreter.sourceIsChinese ? "中 → 英" : "英 → 中"))
                        .font(.callout.weight(.semibold))
                    Image(systemName: "chevron.down").font(.caption.weight(.bold)).foregroundStyle(.secondary)
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
            .menuIndicator(.hidden)
            .glassEffect(.regular.interactive(), in: .capsule)
            .accessibilityLabel("听哪种语言：点开选择")
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

    /// 下拉里的一项：选了和现在不同的语言，就中途换方向（前面的字幕保留，后面按新语言识别）
    private func directionOption(fromChinese: Bool) -> some View {
        Button {
            Task { await session.switchDirection(toChinese: fromChinese) }
        } label: {
            if interpreter.sourceIsChinese == fromChinese {
                Label(fromChinese ? "听中文，译成英语" : "听英语，译成中文", systemImage: "checkmark")
            } else {
                Text(fromChinese ? "听中文，译成英语" : "听英语，译成中文")
            }
        }
    }

    // MARK: 字幕

    private var subtitles: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 4) {
                    // 继续录：先显示之前录过的字幕，中间用一条分隔线隔开
                    ForEach(previous) { line in
                        subtitle(original: line.original, translation: line.translation)
                            .opacity(0.55)
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
                        subtitle(original: segment.original, translation: segment.translation)
                            .id(segment.id)
                    }
                    if !interpreter.live.isEmpty {
                        // 还没说完的那句：原文和边说边翻的译文一起往下长
                        subtitle(original: interpreter.live + "…",
                                 translation: interpreter.liveTranslation.isEmpty ? (display == 2 ? "…" : nil) : interpreter.liveTranslation + "…",
                                 pending: false)
                        .padding(.vertical, 4)
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
                Button("重试") { Task { await interpreter.start(sourceIsChinese: interpreter.sourceIsChinese) } }
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

    /// 一条字幕：按显示方式只显示原文、只显示译文，或者对照（原文小字在上、译文在下）
    @ViewBuilder
    private func subtitle(original: String, translation: String?, pending: Bool = true) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            if display != 2 {
                Text(original)
                    .font(display == 1 ? .system(size: 17) : .system(size: 14))
                    .foregroundStyle(display == 1 ? .primary : .secondary)
                    .lineSpacing(2)
            }
            if display != 1 {
                if let translation {
                    Text(translation)
                        .font(.system(size: 16))
                        .lineSpacing(3)
                } else if pending {
                    ProgressView().controlSize(.mini)
                }
            }
        }
        .textSelection(.enabled)
        .padding(.horizontal, 12)
        .padding(.vertical, 7)
        .frame(maxWidth: .infinity, alignment: .leading)
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
        guard !finishing else { return }
        finishing = true
        Task {
            await session.finish()
            dismiss()
        }
    }
}
