import SwiftUI

/// 英语场景练习（需要 AI）：选一个场景，AI 扮演对方（房东、服务员、面试官……），用户用英语回答，可以说也可以打字。
/// 说得不地道的地方，在那句下面给出更地道的说法和原因，不打断对话。结束时给一个小结，新说法可以加入生词本。
struct PracticeView: View {
    @ObservedObject var controller: ConversationController
    @ObservedObject private var voice = VoiceInput.shared
    @ObservedObject private var speaker = Speaker.shared
    @Environment(\.dismiss) private var dismiss
    @AppStorage("practice.level") private var level = 1
    /// 自动朗读 AI 说的话
    @AppStorage("practice.speak") private var speak = true
    /// 语音聊天：像语音通话一样，对方说完自动开始听，我说完停一下自动发送
    @AppStorage("practice.handsFree") private var handsFree = false
    /// 一键显示所有中文意思
    @AppStorage("practice.showChinese") private var showChinese = false
    @AppStorage(SettingsKey.playbackSpeed) private var speed = 1.0

    @State private var record: PracticeRecord?
    @State private var custom = ""
    @State private var input = ""
    @State private var thinking = false
    @State private var error: String?
    @State private var showSummary = false
    /// 点开看中文意思的那几句
    @State private var revealed: Set<UUID> = []
    /// 这次练习存在会话里的那一轮
    @State private var savedTurn: UUID?
    @FocusState private var focused: Bool

    /// seed：直接练这个场景（模块首页点了某个场景，或者“再练一次”）；nil 时先选场景
    init(controller: ConversationController, seed: PracticeRecord? = nil) {
        self.controller = controller
        _record = State(initialValue: seed.map { PracticeRecord(scenario: $0.scenario, role: $0.role, level: $0.level) })
    }

    var body: some View {
        Group {
            if let record {
                chat(record)
            } else {
                setup
            }
        }
        .background { WashBackground().ignoresSafeArea() }
        .task {
            if record?.lines.isEmpty == true { await respond() }
        }
        .onDisappear {
            if voice.isListening { voice.cancel() }
            Speaker.shared.stop()
        }
        .sheet(isPresented: $showSummary) {
            if let record {
                PracticeSummary(record: record, onAgain: again, onDone: {
                    showSummary = false
                    dismiss()
                })
                .presentationDetents([.large])
            }
        }
        #if os(macOS)
        .frame(minWidth: 560, minHeight: 720)
        #endif
    }

    // MARK: 选场景

    private var setup: some View {
        VStack(spacing: 0) {
            HStack {
                GlassIconButton(systemName: "xmark", label: "关闭") { dismiss() }
                Spacer()
            }
            .padding(.horizontal, 16)
            .padding(.top, 8)
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("英语场景练习").font(.title2.weight(.bold))
                        Text("选一个场景，AI 扮演对方，你用英语回答，可以说也可以打字。说得不地道的地方会在下面给出更好的说法，不打断对话。")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    }
                    Picker("难度", selection: $level) {
                        ForEach(AITasks.practiceLevels.indices, id: \.self) { Text(AITasks.practiceLevels[$0]).tag($0) }
                    }
                    .pickerStyle(.segmented)

                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 150), spacing: 10)], spacing: 10) {
                        ForEach(PracticePreset.all) { preset in
                            Button { begin(scenario: preset.title + "：" + preset.detail, role: preset.role) } label: {
                                presetTile(preset)
                            }
                            .buttonStyle(.plain)
                        }
                    }

                    VStack(alignment: .leading, spacing: 8) {
                        Text("自己描述一个场景").font(.footnote.weight(.semibold)).foregroundStyle(.secondary)
                        HStack(spacing: 8) {
                            TextField("例如：在咖啡店和咖啡师聊天", text: $custom, axis: .vertical)
                                .lineLimit(1...3)
                                .padding(.horizontal, 12)
                                .padding(.vertical, 10)
                                .background(Color.lxBackground, in: .rect(cornerRadius: 14))
                                .onSubmit(beginCustom)
                            Button(action: beginCustom) {
                                Image(systemName: "arrow.right")
                                    .font(.system(size: 16, weight: .bold))
                                    .foregroundStyle(Color.lxOnAccent)
                                    .frame(width: 40, height: 40)
                                    .background(custom.trimmed.isEmpty ? Color.secondary.opacity(0.4) : Color.lxPracticeInk, in: .circle)
                            }
                            .buttonStyle(.plain)
                            .disabled(custom.trimmed.isEmpty)
                            .accessibilityLabel("开始练习")
                        }
                    }
                }
                .padding(16)
                .frame(maxWidth: 640)
                .frame(maxWidth: .infinity)
            }
        }
    }

    private func presetTile(_ preset: PracticePreset) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 6) {
                Image(systemName: preset.symbol).foregroundStyle(Color.lxPracticeInk)
                Text(preset.title).font(.subheadline.weight(.semibold))
            }
            Text("AI 是\(preset.role) · \(preset.detail)")
                .font(.caption)
                .foregroundStyle(.secondary)
                .lineLimit(2, reservesSpace: true)
                .multilineTextAlignment(.leading)
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.lxPracticeCard, in: .rect(cornerRadius: 16))
        .contentShape(.rect)
    }

    private func beginCustom() {
        let text = custom.trimmed
        guard !text.isEmpty else { return }
        begin(scenario: text, role: "")
    }

    private func begin(scenario: String, role: String) {
        record = PracticeRecord(scenario: scenario, role: role, level: level)
        savedTurn = nil
        Task { await respond() }
    }

    // MARK: 对话

    private func chat(_ record: PracticeRecord) -> some View {
        VStack(spacing: 0) {
            HStack(spacing: 10) {
                GlassIconButton(systemName: "xmark", label: "结束练习") { finish() }
                VStack(spacing: 1) {
                    Text("练习：" + (record.scenario.components(separatedBy: "：").first ?? record.scenario))
                        .font(.headline)
                        .lineLimit(1)
                    Text((record.role.isEmpty ? "AI 正在进入角色" : "AI 是\(record.role)") + " · " + AITasks.practiceLevels[min(record.level, 2)])
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity)
                GlassIconButton(systemName: showChinese ? "character.bubble.fill" : "character.bubble",
                                label: showChinese ? "隐藏中文意思" : "显示中文意思",
                                tint: showChinese ? .lxPracticeInk : .primary) {
                    showChinese.toggle()
                    revealed = []
                }
                Menu {
                    Toggle("自动朗读对方的话", systemImage: "speaker.wave.2", isOn: $speak)
                    SpeechSpeedPicker()
                        .pickerStyle(.inline)
                } label: {
                    Image(systemName: speak ? "speaker.wave.2.fill" : "speaker.slash")
                        .font(.system(size: 17, weight: .medium))
                        .foregroundStyle(Color.primary)
                        .frame(width: 44, height: 44)
                        .contentShape(.circle)
                }
                .buttonStyle(.plain)
                .glassEffect(.regular.interactive(), in: .circle)
                .accessibilityLabel("朗读和语速")
            }
            .padding(.horizontal, 16)
            .padding(.top, 8)
            .padding(.bottom, 6)

            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 10) {
                        ForEach(record.lines) { line in
                            if line.isMine { mine(line) } else { partner(line) }
                        }
                        if thinking {
                            HStack(spacing: 8) {
                                ProgressView().controlSize(.small)
                                Text(record.lines.isEmpty ? "AI 正在准备开场…" : "对方正在回复…")
                                    .font(.footnote).foregroundStyle(.secondary)
                            }
                            .padding(.leading, 4)
                        }
                        if let error {
                            Button { Task { await respond() } } label: {
                                Label(error + " 点这里重试", systemImage: "arrow.clockwise")
                                    .font(.footnote)
                                    .foregroundStyle(Color.lxAI)
                            }
                            .buttonStyle(.plain)
                        }
                        Color.clear.frame(height: 1).id("bottom")
                    }
                    .padding(16)
                    .frame(maxWidth: 680)
                    .frame(maxWidth: .infinity)
                }
                .scrollDismissesKeyboard(.interactively)
                .onChange(of: record.lines.count) { withAnimation { proxy.scrollTo("bottom", anchor: .bottom) } }
                .onChange(of: thinking) { withAnimation { proxy.scrollTo("bottom", anchor: .bottom) } }
            }
            .onChange(of: speak) { if !speak { Speaker.shared.stop() } }
            // 语音聊天：轮到我说时自动开始听
            .onChange(of: shouldListen, initial: true) { _, listen in
                guard listen else { return }
                Task {
                    try? await Task.sleep(for: .milliseconds(350))
                    if shouldListen { await voice.start(preferred: .english) }
                }
            }
            // 语音聊天：说完停顿一会儿自动发送
            .task(id: handsFree && voice.isListening) {
                guard handsFree, voice.isListening else { return }
                var last = voice.text
                var since = Date()
                while !Task.isCancelled, voice.isListening {
                    try? await Task.sleep(for: .milliseconds(250))
                    if voice.text != last {
                        last = voice.text
                        since = Date()
                    } else if !last.isEmpty, Date().timeIntervalSince(since) > 1.6 {
                        finishVoice()
                        break
                    }
                }
            }

            composer
        }
    }

    private func partner(_ line: PracticeLine) -> some View {
        HStack(alignment: .bottom, spacing: 6) {
            Button {
                if revealed.contains(line.id) { revealed.remove(line.id) } else { revealed.insert(line.id) }
            } label: {
                VStack(alignment: .leading, spacing: 4) {
                    Text(line.text).font(.system(size: 17))
                    if showChinese != revealed.contains(line.id), let chinese = line.chinese, !chinese.isEmpty {
                        Text(chinese).font(.subheadline).foregroundStyle(.secondary)
                    }
                }
                .multilineTextAlignment(.leading)
                .padding(.horizontal, 14)
                .padding(.vertical, 10)
                .background(Color.lxSurface, in: .rect(cornerRadius: 18))
                .contentShape(.rect)
            }
            .buttonStyle(.plain)
            .accessibilityHint(showChinese != revealed.contains(line.id) ? "收起中文意思" : "显示中文意思")
            let speech = Speech.english(line.text)
            Button { Speaker.shared.toggle(speech) } label: {
                Image(systemName: Speaker.shared.playing == speech ? "stop.fill" : "speaker.wave.2")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .frame(width: 32, height: 32)
                    .contentShape(.rect)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("朗读")
            Spacer(minLength: 40)
        }
    }

    private func mine(_ line: PracticeLine) -> some View {
        VStack(alignment: .trailing, spacing: 6) {
            Text(line.text)
                .font(.system(size: 17))
                .foregroundStyle(Color.lxOnAccent)
                .padding(.horizontal, 14)
                .padding(.vertical, 10)
                .background(Color.lxAccent, in: .rect(cornerRadius: 18))
                .textSelection(.enabled)
            if let better = line.better {
                CorrectionNote(better: better, reason: line.reason)
            }
        }
        .padding(.leading, 48)
        .frame(maxWidth: .infinity, alignment: .trailing)
    }

    /// 语音聊天时，现在是不是该轮到我说
    private var shouldListen: Bool {
        guard handsFree, let record, !showSummary, !thinking, error == nil, speaker.playing == nil, !voice.isListening,
              let last = record.lines.last, !last.isMine else { return false }
        if case .failed = voice.state { return false }
        return true
    }

    private var composer: some View {
        VStack(spacing: 8) {
            VoiceErrorBanner()
            if handsFree {
                handsFreePanel
            } else if voice.isListening {
                HStack(spacing: 10) {
                    Button { voice.cancel() } label: {
                        Image(systemName: "xmark").font(.system(size: 15, weight: .semibold))
                            .frame(width: 40, height: 40)
                            .background(Color.secondary.opacity(0.15), in: .circle)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("取消")
                    VStack(alignment: .leading, spacing: 4) {
                        VoiceWave(levels: Array(voice.levels.suffix(16)))
                        Text(voice.text.isEmpty ? "请说英语…" : voice.text)
                            .font(.subheadline)
                            .foregroundStyle(voice.text.isEmpty ? .tertiary : .primary)
                            .lineLimit(3)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    Button(action: finishVoice) {
                        Image(systemName: "arrow.up").font(.system(size: 16, weight: .bold))
                            .foregroundStyle(Color.lxOnAccent)
                            .frame(width: 40, height: 40)
                            .background(Color.lxAccent, in: .circle)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("说完了，发送")
                }
            } else {
                HStack(alignment: .bottom, spacing: 8) {
                    Button {
                        focused = false
                        handsFree = true
                    } label: {
                        Image(systemName: "waveform")
                            .font(.system(size: 17, weight: .semibold))
                            .foregroundStyle(Color.lxPracticeInk)
                            .frame(width: 40, height: 40)
                            .background(Color.lxPracticeCard, in: .circle)
                            .frame(minWidth: 44, minHeight: 44)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("切换到语音聊天")
                    .accessibilityHint("对方说完自动开始听，你说完停一下自动发送")
                    TextField("用英语回答，也可以打中文", text: $input, axis: .vertical)
                        .lineLimit(1...4)
                        .focused($focused)
                        #if os(iOS)
                        .textInputAutocapitalization(.sentences)
                        #endif
                        .padding(.horizontal, 12)
                        .padding(.vertical, 10)
                        .onSubmit(sendTyped)
                    if input.trimmed.isEmpty {
                        MicButton(size: 40) {
                            focused = false
                            Speaker.shared.stop()
                            Task { await voice.start(preferred: .english) }
                        }
                        .disabled(thinking)
                    } else {
                        Button(action: sendTyped) {
                            Image(systemName: "arrow.up").font(.system(size: 16, weight: .bold))
                                .foregroundStyle(Color.lxOnAccent)
                                .frame(width: 40, height: 40)
                                .background(thinking ? Color.secondary.opacity(0.4) : Color.lxAccent, in: .circle)
                                .frame(minWidth: 44, minHeight: 44)
                        }
                        .buttonStyle(.plain)
                        .disabled(thinking)
                        .accessibilityLabel("发送")
                    }
                }
            }
        }
        .padding(10)
        .glassEffect(.regular, in: .rect(cornerRadius: 24))
        .padding(.horizontal, 12)
        .padding(.bottom, 8)
        .frame(maxWidth: 680)
    }

    /// 语音聊天：不用点发送，显示现在轮到谁
    private var handsFreePanel: some View {
        HStack(spacing: 12) {
            Button {
                handsFree = false
                if voice.isListening { voice.cancel() }
            } label: {
                Image(systemName: "keyboard")
                    .font(.system(size: 16, weight: .medium))
                    .frame(width: 40, height: 40)
                    .background(Color.secondary.opacity(0.15), in: .circle)
                    .frame(minWidth: 44, minHeight: 44)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("换回打字")

            VStack(alignment: .leading, spacing: 4) {
                if voice.isListening {
                    HStack(spacing: 8) {
                        Text("轮到你说了").font(.footnote.weight(.semibold)).foregroundStyle(Color.lxPracticeInk)
                        VoiceWave(levels: Array(voice.levels.suffix(12)))
                    }
                    Text(voice.text.isEmpty ? "说英语，说完停一下会自动发送" : voice.text)
                        .font(.subheadline)
                        .foregroundStyle(voice.text.isEmpty ? .tertiary : .primary)
                        .lineLimit(3)
                } else if thinking {
                    Text("对方在想…").font(.subheadline).foregroundStyle(.secondary)
                } else if speaker.playing != nil {
                    Text("对方正在说…").font(.subheadline).foregroundStyle(.secondary)
                } else if case .failed = voice.state {
                    Text("没法开始听，换回打字试试").font(.subheadline).foregroundStyle(Color.lxAI)
                } else {
                    Text("语音聊天").font(.subheadline).foregroundStyle(.secondary)
                }
            }
            .frame(maxWidth: .infinity, minHeight: 52, alignment: .leading)

            if voice.isListening {
                Button(action: finishVoice) {
                    Image(systemName: "arrow.up").font(.system(size: 16, weight: .bold))
                        .foregroundStyle(Color.lxOnAccent)
                        .frame(width: 40, height: 40)
                        .background(voice.text.isEmpty ? Color.secondary.opacity(0.4) : Color.lxAccent, in: .circle)
                        .frame(minWidth: 44, minHeight: 44)
                }
                .buttonStyle(.plain)
                .disabled(voice.text.isEmpty)
                .accessibilityLabel("现在发送")
            } else if speaker.playing != nil {
                Button { Speaker.shared.stop() } label: {
                    Image(systemName: "forward.end.fill").font(.system(size: 15, weight: .semibold))
                        .frame(width: 40, height: 40)
                        .background(Color.secondary.opacity(0.15), in: .circle)
                        .frame(minWidth: 44, minHeight: 44)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("跳过，直接轮到我说")
            }
        }
    }

    // MARK: 发送和 AI 回复

    private func sendTyped() {
        let text = input.trimmed
        guard !text.isEmpty, !thinking else { return }
        input = ""
        send(text)
    }

    private func finishVoice() {
        let result = voice.stop()
        if let audio = result.audio { ConversationStore.deleteMediaFile(audio) }
        guard !result.text.isEmpty else { return }
        send(result.text)
    }

    private func send(_ text: String) {
        guard record != nil else { return }
        error = nil
        withAnimation(.snappy) { record?.lines.append(PracticeLine(isMine: true, text: text)) }
        Task { await respond() }
    }

    /// 让 AI 接着说：没有内容时开场，否则回应最后一句我说的话
    private func respond() async {
        guard let current = record, !thinking else { return }
        guard current.lines.last?.isMine != false else { return }
        thinking = true
        error = nil
        defer { thinking = false }
        do {
            let reply = try await AITasks.practiceTurn(scenario: current.scenario, role: current.role, level: current.level,
                                                       lines: current.lines, config: AIClient.currentConfig)
            // 换了场景或者已经重来过：丢掉这次结果
            guard var updated = record, updated.scenario == current.scenario, updated.lines.count == current.lines.count else { return }
            if let last = updated.lines.indices.last, updated.lines[last].isMine {
                updated.lines[last].better = reply.better
                updated.lines[last].reason = reply.better == nil ? nil : reply.reason
            }
            if updated.role.isEmpty { updated.role = reply.role ?? "对方" }
            for phrase in reply.phrases where !updated.phrases.contains(where: { $0.key.lowercased() == phrase.key.lowercased() }) {
                updated.phrases.append(phrase)
            }
            updated.lines.append(PracticeLine(isMine: false, text: reply.reply, chinese: reply.chinese))
            withAnimation(.snappy) { record = updated }
            savedTurn = ModuleStore.shared.savePractice(updated, into: savedTurn)
            if speak { Speaker.shared.play(.english(reply.reply)) }
        } catch {
            self.error = error.localizedDescription
        }
    }

    // MARK: 结束

    private func finish() {
        if voice.isListening { voice.cancel() }
        Speaker.shared.stop()
        if let record, record.lines.contains(where: \.isMine) {
            savedTurn = ModuleStore.shared.savePractice(record, into: savedTurn)
            showSummary = true
        } else {
            dismiss()
        }
    }

    /// 同一个场景重新来一次：这次的记录已经存好了，新开一条
    private func again() {
        showSummary = false
        guard let old = record else { return }
        record = PracticeRecord(scenario: old.scenario, role: old.role, level: old.level)
        savedTurn = nil
        revealed = []
        error = nil
        Task { await respond() }
    }
}

/// 我说的那句下面：更地道的说法和原因
struct CorrectionNote: View {
    let better: String
    let reason: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("更地道：").font(.caption.weight(.semibold)).foregroundStyle(Color.lxTranscriptInk)
            Text(better).font(.subheadline).textSelection(.enabled)
            if let reason, !reason.isEmpty {
                Text(reason).font(.caption).foregroundStyle(.secondary)
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.lxTranscriptCard, in: .rect(cornerRadius: 14))
    }
}

/// 练习小结：说了几句、几处可以更地道、学到几个新说法；新说法可以加入生词本
struct PracticeSummary: View {
    let record: PracticeRecord
    let onAgain: () -> Void
    let onDone: () -> Void
    @ObservedObject private var history = HistoryStore.shared

    private var mineCount: Int { record.lines.filter(\.isMine).count }
    private var betterCount: Int { record.lines.filter { $0.better != nil }.count }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text("练习小结").font(.headline).frame(maxWidth: .infinity)
                HStack(spacing: 10) {
                    stat(mineCount, "句是我说的", .primary)
                    stat(betterCount, "处可以更地道", .lxAI)
                    stat(record.phrases.count, "个新说法", .lxSentenceInk)
                }
                if betterCount > 0 {
                    Text("更地道的说法").font(.footnote.weight(.semibold)).foregroundStyle(.secondary)
                    VStack(spacing: 8) {
                        ForEach(record.lines.filter { $0.better != nil }) { line in
                            VStack(alignment: .leading, spacing: 4) {
                                Text(line.text).font(.footnote).foregroundStyle(.secondary).strikethrough(color: .secondary.opacity(0.5))
                                CorrectionNote(better: line.better ?? "", reason: line.reason)
                            }
                        }
                    }
                }
                if !record.phrases.isEmpty {
                    Text("新说法").font(.footnote.weight(.semibold)).foregroundStyle(.secondary)
                    VStack(spacing: 0) {
                        ForEach(record.phrases) { phrase in
                            HStack(spacing: 10) {
                                Text(phrase.key).font(.body)
                                Spacer(minLength: 8)
                                Text(phrase.value).font(.footnote).foregroundStyle(.secondary)
                                Button { toggle(phrase) } label: {
                                    Image(systemName: history.isStarred(phrase.key) ? "star.fill" : "star")
                                        .foregroundStyle(history.isStarred(phrase.key) ? Color.lxAI : .secondary)
                                        .frame(width: 36, height: 36)
                                        .contentShape(.rect)
                                }
                                .buttonStyle(.plain)
                                .accessibilityLabel(history.isStarred(phrase.key) ? "从生词本移除" : "加入生词本")
                            }
                            .padding(.leading, 14)
                            .padding(.trailing, 4)
                            .padding(.vertical, 4)
                            if phrase.id != record.phrases.last?.id { Divider().padding(.leading, 14) }
                        }
                    }
                    .background(Color.lxSurface, in: .rect(cornerRadius: 16))
                    let allStarred = record.phrases.allSatisfy { history.isStarred($0.key) }
                    Button {
                        for phrase in record.phrases where !history.isStarred(phrase.key) { toggle(phrase) }
                    } label: {
                        Label(allStarred ? "已全部加入生词本" : "全部加入生词本", systemImage: allStarred ? "star.fill" : "star")
                            .font(.body.weight(.semibold))
                            .foregroundStyle(Color.lxOnAccent)
                            .frame(maxWidth: .infinity, minHeight: 50)
                            .background(allStarred ? Color.secondary.opacity(0.5) : Color.lxAccent, in: .capsule)
                    }
                    .buttonStyle(.plain)
                    .disabled(allStarred)
                }
                HStack(spacing: 12) {
                    Button(action: onAgain) {
                        Label("再练一次", systemImage: "arrow.counterclockwise")
                            .font(.body.weight(.semibold))
                            .frame(maxWidth: .infinity, minHeight: 46)
                            .background(Color.lxSurface, in: .capsule)
                    }
                    .buttonStyle(.plain)
                    Button(action: onDone) {
                        Text("完成")
                            .font(.body.weight(.semibold))
                            .frame(maxWidth: .infinity, minHeight: 46)
                            .background(Color.lxSurface, in: .capsule)
                    }
                    .buttonStyle(.plain)
                }
                Text("这次练习已保存在练习记录里。").font(.caption).foregroundStyle(.tertiary).frame(maxWidth: .infinity)
            }
            .padding(20)
        }
        #if os(macOS)
        .frame(minWidth: 460, minHeight: 560)
        #endif
    }

    private func stat(_ value: Int, _ title: String, _ color: Color) -> some View {
        VStack(spacing: 4) {
            Text("\(value)").font(.system(size: 30, weight: .semibold, design: .rounded)).foregroundStyle(color)
            Text(title).font(.caption).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, minHeight: 86)
        .background(Color.lxSurface, in: .rect(cornerRadius: 16))
    }

    private func toggle(_ phrase: Phrase) {
        if !history.items.contains(where: { $0.text == phrase.key }) {
            history.add(phrase.key, summary: phrase.value)
        }
        history.toggleStar(phrase.key)
    }
}

/// 预设的练习场景
struct PracticePreset: Identifiable {
    let title: String
    let role: String
    let detail: String
    let symbol: String
    var id: String { title }

    var scenario: String { title + "：" + detail }

    static let all = [
        PracticePreset(title: "租房", role: "房东", detail: "暖气坏了，和房东约时间来修", symbol: "house.fill"),
        PracticePreset(title: "餐厅点餐", role: "服务员", detail: "点餐、问推荐、结账", symbol: "fork.knife"),
        PracticePreset(title: "酒店入住", role: "前台", detail: "办理入住，问早餐和退房时间", symbol: "bed.double.fill"),
        PracticePreset(title: "机场值机", role: "地勤", detail: "值机、托运行李、选座位", symbol: "airplane"),
        PracticePreset(title: "看医生", role: "医生", detail: "描述症状，听医生的建议", symbol: "cross.case.fill"),
        PracticePreset(title: "工作面试", role: "面试官", detail: "介绍自己的经历，回答面试问题", symbol: "briefcase.fill"),
        PracticePreset(title: "购物退换", role: "店员", detail: "问尺码、试穿、退换货", symbol: "cart.fill"),
        PracticePreset(title: "课堂讨论", role: "老师", detail: "课上提问、讨论作业", symbol: "graduationcap.fill"),
        PracticePreset(title: "同事闲聊", role: "同事", detail: "茶水间聊周末和工作", symbol: "cup.and.saucer.fill"),
    ]
}
