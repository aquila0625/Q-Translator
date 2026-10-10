import AVFoundation
import CoreTransferable
import SwiftUI
import UniformTypeIdentifiers

/// 一条传译记录的全文：对照、原文、译文三种显示；有录音时可以回放（点一句从那里开始），
/// 每句都能修改；可以导出文字稿、字幕和录音，让 AI 总结要点、AI 精校，或者接着录
struct InterpretRecordPage: View {
    let id: UUID
    @ObservedObject var store: ModuleStore
    let onBack: () -> Void
    let onContinue: () -> Void

    @AppStorage("interpreter.display") private var display = 0
    @State private var renaming = false
    @State private var renameText = ""
    @State private var confirmDelete = false
    @State private var summarizing = false
    @State private var summaryError: String?
    @State private var editing: TranscriptLine?
    @State private var confirmRefine = false
    @State private var needKey = false
    @StateObject private var player = InterpretPlayer()
    @ObservedObject private var ai = AISettings.shared
    @ObservedObject private var refiner = TranscriptRefiner.shared

    var body: some View {
        if let record = store.interpretation(id) {
            VStack(spacing: 0) {
                topBar(record)
                Text(meta(record))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .padding(.top, 8)
                if record.refined != nil {
                    Picker("版本", selection: Binding(get: { record.showsRefined == true },
                                                     set: { value in store.updateInterpretation(id) { $0.showsRefined = value } })) {
                        Text("实时字幕").tag(false)
                        Text("AI 精校").tag(true)
                    }
                    .pickerStyle(.segmented)
                    .padding(.horizontal, 16)
                    .padding(.top, 8)
                }
                Picker("显示", selection: $display) {
                    Text("对照").tag(0)
                    Text("原文").tag(1)
                    Text("译文").tag(2)
                }
                .pickerStyle(.segmented)
                .padding(.horizontal, 16)
                .padding(.vertical, 8)
                if record.audio?.isEmpty == false { playerBar }
                transcript(record)
                    .overlay(alignment: .bottom) { actionBar(record) }
            }
            .task(id: record.audio) { await player.load(record.audio ?? []) }
            .onDisappear { player.stop() }
            .sheet(item: $editing) { line in
                TranscriptLineEditor(line: line) { original, translation, retranslated in
                    store.updateLine(id, line.id) {
                        $0.original = original
                        $0.translation = translation
                    }
                    Analytics.track(.interpretEdit, ["retranslate": retranslated ? "yes" : "no"])
                }
            }
            .alert("改名", isPresented: $renaming) {
                TextField("名称", text: $renameText)
                Button("取消", role: .cancel) {}
                Button("保存") {
                    let name = renameText.trimmed
                    if !name.isEmpty { store.updateInterpretation(id) { $0.title = name } }
                }
            }
            .confirmationDialog("删除“\(record.title)”？", isPresented: $confirmDelete, titleVisibility: .visible) {
                Button("删除", role: .destructive) {
                    player.stop()
                    store.deleteInterpretation(id)
                    onBack()
                }
            } message: {
                Text("字幕和录音都会删除，不能恢复。")
            }
            .confirmationDialog("AI 精校", isPresented: $confirmRefine, titleVisibility: .visible) {
                Button(record.refined == nil ? "开始精校" : "重新精校") { refiner.refine(record) }
            } message: {
                Text(refineMessage(record))
            }
            .alert("需要 OpenAI 的 API Key", isPresented: $needKey) {
                Button("取消", role: .cancel) {}
                Button("去配置") { NotificationCenter.default.post(name: .openAISettings, object: nil) }
            } message: {
                Text("AI 精校要把录音交给 OpenAI 重新识别（Claude 和 DeepSeek 没有语音转写）。在“设置 → AI 增强 → 语音转写”里填上 OpenAI 的 API Key。")
            }
        } else {
            Color.clear.onAppear(perform: onBack)
        }
    }

    private func meta(_ record: InterpretRecord) -> String {
        var parts = [record.createdAt.formatted(.dateTime.month().day().hour().minute()), "\(record.shownLines.count) 句",
                     AudioReplayButton.format(record.duration)]
        parts.append(record.showsRefined == true || record.autoLanguage == true ? "中英自动" : (record.sourceIsChinese ? "中 → 英" : "英 → 中"))
        return parts.joined(separator: " · ")
    }

    private func topBar(_ record: InterpretRecord) -> some View {
        ModuleTopBar(title: record.title, onBack: onBack) {
            Menu {
                Button("改名", systemImage: "pencil") {
                    renameText = record.title
                    renaming = true
                }
                Button("删除", systemImage: "trash", role: .destructive) { confirmDelete = true }
            } label: {
                Image(systemName: "ellipsis")
                    .font(.system(size: 17, weight: .medium))
                    .frame(width: 44, height: 44)
                    .contentShape(.circle)
            }
            .buttonStyle(.plain)
            .glassEffect(.regular.interactive(), in: .circle)
            .accessibilityLabel("更多")
        }
    }

    // MARK: 回放

    private var playerBar: some View {
        HStack(spacing: 10) {
            Button { player.toggle() } label: {
                Image(systemName: player.isPlaying ? "pause.fill" : "play.fill")
                    .font(.system(size: 16, weight: .bold))
                    .frame(width: 36, height: 36)
                    .contentShape(.circle)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(player.isPlaying ? "暂停" : "播放录音")
            Text(AudioReplayButton.format(player.time))
                .font(.caption.monospacedDigit())
                .foregroundStyle(.secondary)
            Slider(value: Binding(get: { player.time }, set: { player.seek(to: $0) }), in: 0...max(player.duration, 1))
            Text(AudioReplayButton.format(player.duration))
                .font(.caption.monospacedDigit())
                .foregroundStyle(.secondary)
        }
        .padding(.horizontal, 12)
        .frame(minHeight: 48)
        .glassEffect(.regular, in: .capsule)
        .padding(.horizontal, 16)
        .padding(.bottom, 6)
    }

    // MARK: 字幕

    private func transcript(_ record: InterpretRecord) -> some View {
        let lines = record.shownLines
        let playingID = player.isPlaying ? lines.last(where: { ($0.start ?? .infinity) <= player.time + 0.2 })?.id : nil
        return ScrollViewReader { proxy in
            ScrollView {
                VStack(alignment: .leading, spacing: 8) {
                    if let summary = record.summary { summaryCard(summary) }
                    if record.showsRefined == true, let date = record.refinedAt {
                        Label("AI 精校于 \(date.formatted(.dateTime.month().day().hour().minute()))：OpenAI 重新识别录音后整篇重新翻译",
                              systemImage: "sparkles")
                            .font(.caption)
                            .foregroundStyle(Color.lxAI)
                            .padding(.horizontal, 12)
                    }
                    ForEach(lines) { line in
                        lineView(line, record: record, playing: line.id == playingID)
                            .id(line.id)
                    }
                    Color.clear.frame(height: 80)
                }
                .padding(.horizontal, 12)
                .frame(maxWidth: 680)
                .frame(maxWidth: .infinity)
            }
            .onChange(of: playingID) { _, id in
                guard let id else { return }
                withAnimation(.easeOut(duration: 0.3)) { proxy.scrollTo(id, anchor: .center) }
            }
        }
    }

    private func lineView(_ line: TranscriptLine, record: InterpretRecord, playing: Bool) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            if display != 2 {
                Text(line.original)
                    .font(.system(size: display == 1 ? 17 : 14))
                    .foregroundStyle(display == 1 ? .primary : .secondary)
            }
            if display != 1 {
                Text(line.translation).font(.system(size: 16, weight: .medium))
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(playing ? Color.lxTranscriptInk.opacity(0.14) : .clear, in: .rect(cornerRadius: 12))
        .contentShape(.rect)
        .onTapGesture {
            if let start = line.start, record.audio?.isEmpty == false { player.play(from: max(0, start - 0.3)) }
        }
        .contextMenu {
            Button("修改", systemImage: "pencil") { editing = line }
            if let start = line.start, record.audio?.isEmpty == false {
                Button("从这句开始播放", systemImage: "play") { player.play(from: max(0, start - 0.3)) }
            }
            Button("复制", systemImage: "doc.on.doc") { Clipboard.copy(line.original + "\n" + line.translation) }
        }
        .accessibilityAction(named: "修改") { editing = line }
    }

    private func summaryCard(_ summary: String) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Label("要点", systemImage: "sparkles").font(.caption.weight(.bold)).foregroundStyle(Color.lxAI)
                Spacer()
                CopyButton(text: summary, label: "复制要点")
            }
            Text(summary).font(.subheadline).textSelection(.enabled)
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.lxAISoft, in: .rect(cornerRadius: 16))
    }

    // MARK: 底部按钮

    private func actionBar(_ record: InterpretRecord) -> some View {
        VStack(spacing: 6) {
            if let progress = refiner.progress[id] {
                HStack(spacing: 10) {
                    ProgressView(value: Double(progress.done), total: Double(max(progress.total, 1)))
                        .frame(width: 70)
                    Text(progress.message).font(.caption).foregroundStyle(.secondary)
                    Spacer()
                    Button("取消") { refiner.cancel(id) }.font(.caption.weight(.semibold))
                }
            } else if let error = refiner.errors[id] ?? summaryError {
                Text(error).font(.caption).foregroundStyle(Color.lxAI)
            } else if record.refined == nil, record.audio?.isEmpty == false {
                Text("长按一句可以修改；点一句从那里播放。识别不准时可以用 AI 精校重新识别整段录音。")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
            HStack(spacing: 8) {
                exportMenu(record)
                barButton(summarizing ? "总结中…" : "要点", "sparkles", tint: .lxAI) { summarize(record) }
                    .disabled(summarizing)
                barButton(refiner.isRunning(id) ? "精校中…" : "精校", "waveform.badge.magnifyingglass", tint: .lxAI) { startRefine(record) }
                    .disabled(refiner.isRunning(id))
                Button(action: onContinue) {
                    Label("继续", systemImage: "mic.fill")
                        .font(.subheadline.weight(.bold))
                        .foregroundStyle(Color.lxBackground)
                        .frame(maxWidth: .infinity, minHeight: 44)
                        .background(Color.lxTranscriptInk, in: .capsule)
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, 16)
        .padding(.bottom, 12)
        .padding(.top, 8)
        .background(.bar)
    }

    private func exportMenu(_ record: InterpretRecord) -> some View {
        let lines = record.shownLines
        let name = InterpretExport.fileName(record.title)
        return Menu {
            Button("复制全文", systemImage: "doc.on.doc") { Clipboard.copy(InterpretExport.text(record.title, lines)) }
            ShareLink(item: InterpretExport.text(record.title, lines)) {
                Label("导出文字稿", systemImage: "doc.text")
            }
            if lines.contains(where: { $0.start != nil }) {
                ShareLink(item: InterpretSubtitleFile(name: name, lines: lines), preview: SharePreview(name + ".srt")) {
                    Label("导出字幕（SRT）", systemImage: "captions.bubble")
                }
            }
            if let parts = record.audio, !parts.isEmpty {
                ShareLink(item: InterpretAudioFile(name: name, parts: parts), preview: SharePreview(name + ".m4a")) {
                    Label("导出录音（m4a）", systemImage: "waveform")
                }
            }
        } label: {
            barLabel("导出", "square.and.arrow.up")
        }
        .buttonStyle(.plain)
        .menuIndicator(.hidden)
    }

    private func barLabel(_ title: String, _ symbol: String, tint: Color = .primary) -> some View {
        Label(title, systemImage: symbol)
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(tint)
            .padding(.horizontal, 12)
            .frame(minHeight: 44)
            .glassEffect(.regular.interactive(), in: .capsule)
    }

    private func barButton(_ title: String, _ symbol: String, tint: Color = .primary, action: @escaping () -> Void) -> some View {
        Button(action: action) { barLabel(title, symbol, tint: tint) }.buttonStyle(.plain)
    }

    // MARK: AI

    private func summarize(_ record: InterpretRecord) {
        guard ai.isConfigured else {
            NotificationCenter.default.post(name: .needAI, object: nil)
            summaryError = "要先配置 AI 的 API Key。"
            return
        }
        summaryError = nil
        refiner.clearError(id)
        summarizing = true
        Task {
            defer { summarizing = false }
            do {
                Analytics.track(.interpretSummary)
                let response = try await AITasks.summarizeTranscript(record.shownLines, config: AIClient.currentConfig)
                store.updateInterpretation(id) { $0.summary = response.text }
            } catch {
                summaryError = error.localizedDescription
            }
        }
    }

    private func startRefine(_ record: InterpretRecord) {
        summaryError = nil
        refiner.clearError(id)
        guard record.audio?.isEmpty == false else {
            summaryError = "这条记录没有录音（更新前录的），没法精校。"
            return
        }
        guard !ai.openAIKey.trimmed.isEmpty else {
            needKey = true
            return
        }
        confirmRefine = true
    }

    private func refineMessage(_ record: InterpretRecord) -> String {
        let minutes = record.audioDuration / 60
        let cost = minutes * SpeechTranscription.pricePerMinute
        let translator = ai.isConfigured ? "\(ai.provider.title)（\(ai.model)）" : "ChatGPT"
        var text = "把这段 \(max(1, Int(minutes.rounded()))) 分钟的录音直接发给 OpenAI 重新识别，"
            + "按 OpenAI 标价约 \(String(format: "%.2f", max(cost, 0.01))) 美元；再用 \(translator) 整篇翻译，另按用量计费。"
            + "中英文会自动识别。原来的实时字幕会保留，可以切换对比。"
        if record.refined != nil { text += "\n\n重新精校会覆盖现在的精校版，包括你对它的修改。" }
        return text
    }
}

/// 修改一句字幕：原文、译文都能改，也可以按改好的原文重新翻译
private struct TranscriptLineEditor: View {
    let line: TranscriptLine
    let onSave: (String, String, Bool) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var original = ""
    @State private var translation = ""
    @State private var translating = false
    @State private var retranslated = false
    @State private var error: String?

    var body: some View {
        NavigationStack {
            Form {
                Section("原文") {
                    TextEditor(text: $original).frame(minHeight: 90)
                }
                Section {
                    TextEditor(text: $translation).frame(minHeight: 90)
                    Button {
                        retranslate()
                    } label: {
                        HStack {
                            Label("按原文重新翻译", systemImage: "arrow.triangle.2.circlepath")
                            if translating { ProgressView().controlSize(.small) }
                        }
                    }
                    .disabled(translating || original.trimmed.isEmpty)
                } header: {
                    Text("译文")
                } footer: {
                    if let error { Text(error) }
                }
            }
            .formStyle(.grouped)
            .navigationTitle("修改字幕")
            .inlineNavigationTitle()
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存") {
                        onSave(original.trimmed, translation.trimmed, retranslated)
                        dismiss()
                    }
                    .disabled(original.trimmed.isEmpty)
                }
            }
        }
        .tint(.lxAccent)
        .frame(minWidth: 420, minHeight: 420)
        .onAppear {
            original = line.original
            translation = line.translation
        }
    }

    /// 配了 AI 就用 AI 翻译，没配用在线翻译
    private func retranslate() {
        let text = original.trimmed
        translating = true
        error = nil
        Task {
            defer { translating = false }
            if AISettings.shared.isConfigured, let result = try? await AITasks.translateLine(text, config: AIClient.currentConfig) {
                translation = result
                retranslated = true
            } else if let result = try? await OnlineTranslator.translate(text, fromChinese: text.isMostlyChinese) {
                translation = result
                retranslated = true
            } else {
                error = "翻译失败，请检查网络后重试。"
            }
        }
    }
}

/// 播放一条记录的录音（几段接起来），供点句回放
@MainActor
final class InterpretPlayer: ObservableObject {
    @Published private(set) var isPlaying = false
    @Published private(set) var time: Double = 0
    @Published private(set) var duration: Double = 0

    private var player: AVPlayer?
    private var timeObserver: Any?
    private var endObserver: NSObjectProtocol?
    private var loaded: [InterpretRecord.AudioPart] = []

    func load(_ parts: [InterpretRecord.AudioPart]) async {
        guard parts != loaded else { return }
        stop()
        loaded = parts
        duration = parts.reduce(0) { $0 + $1.duration }
        time = 0
        guard !parts.isEmpty, let composition = await InterpretAudio.composition(parts) else {
            player = nil
            return
        }
        let item = AVPlayerItem(asset: composition)
        let player = AVPlayer(playerItem: item)
        self.player = player
        timeObserver = player.addPeriodicTimeObserver(forInterval: CMTime(seconds: 0.25, preferredTimescale: 600), queue: .main) { [weak self] time in
            MainActor.assumeIsolated { self?.time = time.seconds }
        }
        endObserver = NotificationCenter.default.addObserver(forName: AVPlayerItem.didPlayToEndTimeNotification, object: item, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated {
                self?.isPlaying = false
                self?.player?.seek(to: .zero)
            }
        }
    }

    func toggle() {
        isPlaying ? pause() : play(from: nil)
    }

    func play(from start: Double?) {
        guard let player else { return }
        Speaker.shared.stop()
        AudioPlayback.shared.stop()
        #if os(iOS)
        // 正在传译时音频通道是录音模式，不去改它
        if !Speaker.recordingActive {
            try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .spokenAudio)
            try? AVAudioSession.sharedInstance().setActive(true)
        }
        #endif
        if let start {
            player.seek(to: CMTime(seconds: start, preferredTimescale: 600), toleranceBefore: .zero, toleranceAfter: .zero)
            time = start
        }
        player.play()
        isPlaying = true
    }

    func pause() {
        player?.pause()
        isPlaying = false
    }

    func seek(to seconds: Double) {
        time = seconds
        player?.seek(to: CMTime(seconds: seconds, preferredTimescale: 600), toleranceBefore: .zero, toleranceAfter: .zero)
    }

    func stop() {
        player?.pause()
        isPlaying = false
        if let timeObserver { player?.removeTimeObserver(timeObserver) }
        timeObserver = nil
        if let endObserver { NotificationCenter.default.removeObserver(endObserver) }
        endObserver = nil
        player = nil
        loaded = []
    }
}

/// 导出用的文字稿和文件名
enum InterpretExport {
    static func text(_ title: String, _ lines: [TranscriptLine]) -> String {
        title + "\n\n" + lines.map { $0.original + "\n" + $0.translation }.joined(separator: "\n\n")
    }

    /// 文件名里不能有斜杠和冒号
    static func fileName(_ title: String) -> String {
        let cleaned = title.components(separatedBy: CharacterSet(charactersIn: "/\\:")).joined(separator: "-").trimmed
        return cleaned.isEmpty ? "同声传译" : cleaned
    }

    /// SRT 字幕：每句原文一行、译文一行
    static func srt(_ lines: [TranscriptLine]) -> String {
        func stamp(_ seconds: Double) -> String {
            let ms = Int((max(0, seconds) * 1000).rounded())
            return String(format: "%02d:%02d:%02d,%03d", ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000)
        }
        var index = 0
        var parts: [String] = []
        for line in lines {
            guard let start = line.start else { continue }
            index += 1
            let end = max(line.end ?? start + 2, start + 0.5)
            parts.append("\(index)\n\(stamp(start)) --> \(stamp(end))\n\(line.original)\n\(line.translation)")
        }
        return parts.joined(separator: "\n\n") + "\n"
    }
}

/// 分享时才生成的录音文件（几段录音合成一个 m4a）
struct InterpretAudioFile: Transferable {
    let name: String
    let parts: [InterpretRecord.AudioPart]

    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(exportedContentType: .mpeg4Audio) { file in
            let url = InterpretAudio.temporaryURL(file.name + ".m4a")
            try await InterpretAudio.export(file.parts, to: url)
            await MainActor.run { Analytics.track(.interpretExport, ["type": "audio"]) }
            return SentTransferredFile(url)
        }
    }
}

/// 分享时才生成的 SRT 字幕文件
struct InterpretSubtitleFile: Transferable {
    let name: String
    let lines: [TranscriptLine]

    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(exportedContentType: UTType(filenameExtension: "srt", conformingTo: .plainText) ?? .plainText) { file in
            let url = InterpretAudio.temporaryURL(file.name + ".srt")
            try InterpretExport.srt(file.lines).write(to: url, atomically: true, encoding: .utf8)
            await MainActor.run { Analytics.track(.interpretExport, ["type": "srt"]) }
            return SentTransferredFile(url)
        }
    }
}
