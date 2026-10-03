import SwiftUI

/// 面对面对话：手机平放在桌上，上半屏倒过来给对方看。
/// 各自按自己的大按钮说话，说完自动翻译成另一种语言，大字显示并朗读。退出时整段对话保存到面对面模块的记录里。
struct FaceToFaceView: View {
    @ObservedObject var controller: ConversationController
    @ObservedObject private var voice = VoiceInput.shared
    /// 专用的本机翻译通道：两个方向各一个，一直开着
    @StateObject private var toEnglish: LiveTranslator
    @StateObject private var toChinese: LiveTranslator

    init(controller: ConversationController) {
        self.controller = controller
        let fallback: (String, Bool) async -> String? = { text, chinese in await controller.translate(text, fromChinese: chinese) }
        _toEnglish = StateObject(wrappedValue: LiveTranslator(fallback: fallback))
        _toChinese = StateObject(wrappedValue: LiveTranslator(fallback: fallback))
    }
    @ObservedObject private var speaker = Speaker.shared
    @Environment(\.dismiss) private var dismiss

    @State private var lines: [DialogLine] = []
    /// 正在说话的一方：true 是我（中文），false 是对方（英语）
    @State private var speakingMine: Bool?
    @State private var translating = false
    @AppStorage(SettingsKey.dialogSpeak) private var speakTranslation = true

    var body: some View {
        VStack(spacing: 0) {
            half(mine: false)
                #if os(iOS)
                .rotationEffect(.degrees(180))
                #endif
            half(mine: true)
        }
        .overlay { controls }
        .background(Color.lxBackground)
        .translationTask(toEnglish.configuration) { session in await toEnglish.run(session) }
        .translationTask(toChinese.configuration) { session in await toChinese.run(session) }
        .task {
            await toEnglish.prepare(fromChinese: true)
            await toChinese.prepare(fromChinese: false)
        }
        .onDisappear {
            if voice.isListening { voice.cancel() }
            Speaker.shared.stop()
            ModuleStore.shared.addDialog(lines)
        }
        #if os(macOS)
        .frame(minWidth: 520, minHeight: 760)
        #endif
    }

    // MARK: 半屏

    /// 一方的半屏：显示最近一句，用这一方的语言大字显示（自己说的显示原话，对方说的显示译文）
    private func half(mine: Bool) -> some View {
        let last = lines.last
        let listening = speakingMine == mine && voice.isListening
        return VStack(alignment: .leading, spacing: 12) {
            Spacer(minLength: 0)
            if listening {
                Text(mine ? "正在听你说…" : "Listening…").font(.subheadline).foregroundStyle(.secondary)
                Text(voice.text.isEmpty ? (mine ? "请说中文" : "Please speak English") : voice.text)
                    .font(.system(size: 26, weight: .semibold))
                    .foregroundStyle(voice.text.isEmpty ? .tertiary : .primary)
                    .lineLimit(5)
                    .minimumScaleFactor(0.6)
            } else if translating, speakingMine == !mine {
                ProgressView(mine ? "正在翻译…" : "Translating…")
            } else if let last {
                let ownWords = last.isMine == mine
                Text(ownWords ? (mine ? "我说" : "You said") : (mine ? "对方说" : "They said"))
                    .font(.subheadline).foregroundStyle(.secondary)
                Text(ownWords ? last.original : last.translation)
                    .font(.system(size: 26, weight: .semibold))
                    .lineLimit(6)
                    .minimumScaleFactor(0.6)
                    .textSelection(.enabled)
                Text(ownWords ? last.translation : last.original)
                    .font(.callout)
                    .foregroundStyle(.secondary)
                    .lineLimit(3)
            } else {
                Text(mine ? "按下面的按钮说中文" : "Tap the button below to speak English")
                    .font(.title3.weight(.semibold))
                    .foregroundStyle(.secondary)
            }
            Spacer(minLength: 0)
            talkButton(mine: mine)
        }
        .padding(.horizontal, 22)
        .padding(.vertical, 20)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
        .background { (mine ? Color.lxSurface : Color.lxSentenceCard).ignoresSafeArea() }
    }

    /// 大按钮：点一下开始说，再点一下说完
    private func talkButton(mine: Bool) -> some View {
        let listening = speakingMine == mine && voice.isListening
        let tint = mine ? Color.lxSentenceInk : Color.lxAccent
        return Button {
            if listening {
                finish()
            } else {
                start(mine: mine)
            }
        } label: {
            HStack(spacing: 10) {
                Image(systemName: listening ? "stop.fill" : "mic.fill")
                Text(listening ? (mine ? "说完了" : "Done") : (mine ? "我说中文" : "Speak English"))
                if listening { VoiceWave(levels: Array(voice.levels.suffix(10))).frame(width: 50) }
            }
            .font(.system(size: 18, weight: .bold))
            .foregroundStyle(.white)
            .frame(maxWidth: .infinity, minHeight: 58)
            .background(listening ? Color.red : tint, in: .capsule)
            .contentShape(.capsule)
        }
        .buttonStyle(.plain)
        .disabled(translating || (voice.isListening && !listening))
        .opacity(voice.isListening && !listening ? 0.4 : 1)
    }

    /// 中间：退出、朗读开关
    private var controls: some View {
        HStack(spacing: 14) {
            roundButton("xmark", label: "结束对话") { dismiss() }
            roundButton(speakTranslation ? "speaker.wave.2.fill" : "speaker.slash.fill",
                        label: speakTranslation ? "关闭朗读" : "打开朗读") {
                speakTranslation.toggle()
                if !speakTranslation { Speaker.shared.stop() }
            }
        }
    }

    private func roundButton(_ systemName: String, label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: systemName)
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(Color.lxBackground)
                .frame(width: 48, height: 48)
                .background(Color.primary, in: .circle)
                .shadow(color: .black.opacity(0.2), radius: 8, y: 3)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }

    // MARK: 说话和翻译

    private func start(mine: Bool) {
        Speaker.shared.stop()
        speakingMine = mine
        Task { await voice.start(preferred: mine ? .chinese : .english) }
    }

    private func finish() {
        guard let mine = speakingMine else { return }
        let result = voice.stop()
        if let audio = result.audio { ConversationStore.deleteMediaFile(audio) }
        let text = result.text
        guard !text.isEmpty else {
            speakingMine = nil
            return
        }
        translating = true
        Task {
            let translator = mine ? toEnglish : toChinese
            let translation = await translator.translate(text, fromChinese: mine) ?? "（翻译失败）"
            translating = false
            withAnimation(.snappy) {
                lines.append(DialogLine(isMine: mine, original: text, translation: translation, originalIsChinese: mine))
            }
            speakingMine = nil
            if speakTranslation {
                Speaker.shared.play(.text(translation, isChinese: !mine))
            }
        }
    }
}

/// 一段面对面对话的全部句子：对方在左、我在右，译文在上、原话在下
struct DialogBubbles: View {
    let lines: [DialogLine]

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            ForEach(lines) { line in
                bubble(line)
                    .frame(maxWidth: .infinity, alignment: line.isMine ? .trailing : .leading)
            }
        }
    }

    private func bubble(_ line: DialogLine) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(line.translation)
                .font(.system(size: 16, weight: .medium))
                .foregroundStyle(line.isMine ? Color.white : Color.primary)
            Text(line.original)
                .font(.footnote)
                .foregroundStyle(line.isMine ? Color.white.opacity(0.8) : Color.secondary)
        }
        .textSelection(.enabled)
        .padding(.horizontal, 12)
        .padding(.vertical, 9)
        .background(line.isMine ? Color.lxAccent : Color.lxBackground,
                    in: UnevenRoundedRectangle(topLeadingRadius: 16, bottomLeadingRadius: line.isMine ? 16 : 4,
                                               bottomTrailingRadius: line.isMine ? 4 : 16, topTrailingRadius: 16))
        .padding(line.isMine ? .leading : .trailing, 40)
        .contextMenu {
            Button("复制译文", systemImage: "doc.on.doc") { Clipboard.copy(line.translation) }
            Button("复制原话", systemImage: "doc.on.doc") { Clipboard.copy(line.original) }
            Button("朗读译文", systemImage: "speaker.wave.2") {
                Speaker.shared.toggle(.text(line.translation, isChinese: !line.originalIsChinese))
            }
        }
    }
}
