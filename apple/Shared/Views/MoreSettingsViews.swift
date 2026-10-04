import AVFoundation
import Speech
import SwiftUI
import Translation

// MARK: 主题

/// 外观：0 跟随系统，1 浅色，2 深色
extension View {
    func appAppearance() -> some View { modifier(AppAppearance()) }
}

private struct AppAppearance: ViewModifier {
    @AppStorage(SettingsKey.appearance) private var appearance = 0

    func body(content: Content) -> some View {
        #if os(macOS)
        // Mac 上设置整个 App 的外观，菜单栏小窗口也一起变
        content
            .onAppear { apply() }
            .onChange(of: appearance) { apply() }
        #else
        content.preferredColorScheme(appearance == 1 ? .light : appearance == 2 ? .dark : nil)
        #endif
    }

    #if os(macOS)
    private func apply() {
        NSApp.appearance = appearance == 1 ? NSAppearance(named: .aqua) : appearance == 2 ? NSAppearance(named: .darkAqua) : nil
    }
    #endif
}

// MARK: 离线模型

/// 离线模型：中英翻译模型、同声传译用的语音识别模型。下载后不用联网，速度也更快。
struct OfflineModelsView: View {
    enum ModelState: Equatable {
        case checking, installed, available, downloading(Double), unsupported, failed(String)
    }

    @State private var translation: [Bool: ModelState] = [:]
    @State private var speech: [String: ModelState] = [:]
    /// 要下载的翻译方向；变化时由 translationTask 弹出系统的下载确认
    @State private var downloadConfig: TranslationSession.Configuration?
    @State private var downloadingChinese: Bool?

    private let speechLocales = [("en-US", "英语"), ("zh-CN", "中文")]

    var body: some View {
        Form {
            Section {
                ForEach([false, true], id: \.self) { chinese in
                    row(chinese ? "中 → 英" : "英 → 中", state: translation[chinese] ?? .checking) {
                        downloadingChinese = chinese
                        translation[chinese] = .downloading(-1)
                        let (source, target) = SystemTranslator.languages(chinese: chinese)
                        downloadConfig = TranslationSession.Configuration(source: source, target: target)
                    }
                }
            } header: {
                Text("翻译模型")
            } footer: {
                Text("句子、段落、图片、同声传译和面对面对话都会优先用本机翻译：不用联网、不限量，比在线翻译快很多。下载时系统会弹出确认。")
            }

            Section {
                ForEach(speechLocales, id: \.0) { id, name in
                    row(name, state: speech[id] ?? .checking) { Task { await downloadSpeech(id) } }
                }
            } header: {
                Text("语音识别模型")
            } footer: {
                Text("同声传译用的新一代本机识别模型，比系统旧的识别更准、延迟更低。识别在本机完成，录音不上传。")
            }
        }
        .formStyle(.grouped)
        .navigationTitle("离线模型")
        .inlineNavigationTitle()
        .translationTask(downloadConfig) { session in
            do {
                try await session.prepareTranslation()
            } catch {
                if let chinese = downloadingChinese { translation[chinese] = .failed("没有下载") }
            }
            await refresh()
        }
        .task { await refresh() }
    }

    private func row(_ title: String, state: ModelState, download: @escaping () -> Void) -> some View {
        HStack {
            Text(title)
            Spacer()
            switch state {
            case .checking:
                ProgressView().controlSize(.small)
            case .installed:
                Label("已下载", systemImage: "checkmark.circle.fill").foregroundStyle(.green)
            case .available:
                Button("下载", action: download).buttonStyle(.borderless)
            case .downloading(let progress):
                if progress >= 0 {
                    ProgressView(value: progress).frame(width: 90)
                    Text("\(Int(progress * 100))%").font(.footnote.monospacedDigit()).foregroundStyle(.secondary)
                } else {
                    ProgressView().controlSize(.small)
                }
            case .unsupported:
                Text("这台设备不支持").foregroundStyle(.secondary)
            case .failed(let message):
                Text(message).foregroundStyle(.secondary)
                Button("重试", action: download).buttonStyle(.borderless)
            }
        }
        .font(.body)
    }

    private func refresh() async {
        for chinese in [false, true] {
            let (source, target) = SystemTranslator.languages(chinese: chinese)
            switch await LanguageAvailability().status(from: source, to: target) {
            case .installed: translation[chinese] = .installed
            case .supported: if case .downloading = translation[chinese] {} else { translation[chinese] = .available }
            default: translation[chinese] = .unsupported
            }
        }
        for (id, _) in speechLocales where !(speech[id].map(isDownloading) ?? false) {
            speech[id] = await speechState(id)
        }
    }

    private func isDownloading(_ state: ModelState) -> Bool {
        if case .downloading = state { return true }
        return false
    }

    private func transcriber(_ id: String) async -> SpeechTranscriber? {
        guard let locale = await SpeechTranscriber.supportedLocale(equivalentTo: Locale(identifier: id)) else { return nil }
        return SpeechTranscriber(locale: locale, transcriptionOptions: [], reportingOptions: [], attributeOptions: [])
    }

    private func speechState(_ id: String) async -> ModelState {
        guard let transcriber = await transcriber(id) else { return .unsupported }
        switch await AssetInventory.status(forModules: [transcriber]) {
        case .installed: return .installed
        case .supported: return .available
        case .downloading: return .downloading(-1)
        default: return .unsupported
        }
    }

    private func downloadSpeech(_ id: String) async {
        guard let transcriber = await transcriber(id), let locale = transcriber.selectedLocales.first else { return }
        speech[id] = .downloading(0)
        do {
            let reserved = await AssetInventory.reservedLocales
            if !reserved.contains(where: { $0.identifier(.bcp47) == locale.identifier(.bcp47) }) {
                if reserved.count >= AssetInventory.maximumReservedLocales, let old = reserved.first {
                    await AssetInventory.release(reservedLocale: old)
                }
                _ = try await AssetInventory.reserve(locale: locale)
            }
            guard let request = try await AssetInventory.assetInstallationRequest(supporting: [transcriber]) else {
                speech[id] = .installed
                return
            }
            // 每半秒看一下下载进度
            let progress = request.progress
            let watcher = Task {
                while !Task.isCancelled {
                    speech[id] = .downloading(progress.fractionCompleted)
                    try? await Task.sleep(for: .milliseconds(500))
                }
            }
            try await request.downloadAndInstall()
            watcher.cancel()
            speech[id] = .installed
        } catch {
            speech[id] = .failed("下载失败")
        }
    }
}

// MARK: 朗读声音

/// 朗读的音色和语速：AI 音色（更像真人，要 ChatGPT 的 Key）或精选的系统音色，选中就读一句试听
struct SpeechVoicesView: View {
    @AppStorage(SettingsKey.voiceEnglish) private var englishVoice = ""
    @AppStorage(SettingsKey.voiceChinese) private var chineseVoice = ""
    @AppStorage(SettingsKey.aiVoice) private var aiVoice = ""
    @AppStorage(SettingsKey.playbackSpeed) private var speed = 1.0
    @Environment(\.openURL) private var openURL
    @ObservedObject private var speaker = Speaker.shared
    @State private var preview = AVSpeechSynthesizer()
    @State private var hint: String?

    private let englishSample = "Hi, nice to meet you. How are you doing today?"
    private let chineseSample = "你好，很高兴认识你，今天过得怎么样？"

    var body: some View {
        Form {
            Section {
                SpeechSpeedPicker()
                    .onChange(of: speed) { playCurrent() }
            } footer: {
                Text("所有朗读都按这个速度：AI 音色、系统音色和查单词时的真人发音。")
            }
            aiSection
            Section {
                ForEach(SystemVoice.english) { systemRow($0, selection: $englishVoice, sample: englishSample) }
            } header: {
                Text("英文系统音色")
            }
            Section {
                ForEach(SystemVoice.chinese) { systemRow($0, selection: $chineseVoice, sample: chineseSample) }
            } header: {
                Text("中文系统音色")
            } footer: {
                Text(hint ?? "系统音色免费、离线。标“需下载”的音色要先到系统的朗读声音设置里下载（选“高级”或“增强”版本更好听），下载后回到这里就能选。")
            }
            Section {
                Button {
                    if let url = SystemVoice.settingsURL { openURL(url) }
                } label: {
                    Label("去系统设置下载更多音色", systemImage: "arrow.up.forward.app")
                }
            } footer: {
                #if os(iOS)
                Text("打开系统设置后，依次点“辅助功能 → 朗读内容 → 声音”，选语言后下载音色。")
                #else
                Text("在“辅助功能 → 朗读内容”里点“系统声音”旁边的 ⓘ，选“管理声音”下载。")
                #endif
            }
        }
        .formStyle(.grouped)
        .navigationTitle("朗读声音")
        .inlineNavigationTitle()
        .onDisappear {
            preview.stopSpeaking(at: .immediate)
            Speaker.shared.stop()
        }
    }

    // MARK: AI 音色

    private var aiSection: some View {
        Section {
            row(title: "不用 AI 音色", detail: "用下面的系统音色", selected: aiVoice.isEmpty, tag: nil) {
                aiVoice = ""
            }
            ForEach(AIVoice.voices, id: \.id) { voice in
                row(title: voice.id.capitalized, detail: voice.detail, selected: aiVoice == voice.id, tag: nil) {
                    guard AIVoice.apiKey != nil else {
                        hint = "AI 音色要先在设置的“AI 增强”里选 ChatGPT 并填写 API Key。"
                        return
                    }
                    hint = nil
                    aiVoice = voice.id
                    Analytics.track(.settingsVoice, ["type": "ai", "voice": voice.id])
                    Speaker.shared.playAI(.text(englishSample + " " + chineseSample, isChinese: false), voice: voice.id)
                }
                .opacity(AIVoice.apiKey == nil ? 0.45 : 1)
            }
        } header: {
            Text("AI 音色（最像真人）")
        } footer: {
            Text(AIVoice.apiKey == nil
                 ? "要先在设置的“AI 增强”里选 ChatGPT 并填写 API Key。AI 音色需要联网，按字数计费，读一句大约不到 1 分钱。"
                 : "中英文都能读。需要联网，按字数计费，读一句大约不到 1 分钱；读过的句子会缓存，再读不收费。网络不好时自动改用系统音色。")
        }
    }

    // MARK: 系统音色

    private func systemRow(_ voice: SystemVoice, selection: Binding<String>, sample: String) -> some View {
        let best = voice.best
        let tag: (String, Color)? = switch best?.quality {
        case .premium: ("高级", .green)
        case .enhanced: ("增强", .lxAccent)
        case .some: ("基础", .secondary)
        case nil: ("需下载", .orange)
        }
        return row(title: voice.name, detail: voice.detail, selected: selection.wrappedValue == voice.id, tag: tag) {
            guard let best else {
                hint = "“\(voice.name)”还没有下载：打开系统“设置 → 辅助功能 → 朗读内容 → 声音”，在对应语言里找到 \(voice.name) 下载，下载后回到这里再选。"
                return
            }
            hint = nil
            selection.wrappedValue = voice.id
            Analytics.track(.settingsVoice, ["type": "system", "voice": voice.name])
            playSystem(sample, voice: best)
        }
    }

    private func row(title: String, detail: String, selected: Bool, tag: (String, Color)?, action: @escaping () -> Void) -> some View {
        HStack(spacing: 10) {
            Image(systemName: "checkmark")
                .foregroundStyle(Color.lxAccent)
                .opacity(selected ? 1 : 0)
                .frame(width: 18)
            VStack(alignment: .leading, spacing: 1) {
                Text(title)
                Text(detail).font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
            if let tag {
                Text(tag.0)
                    .font(.caption2.weight(.bold))
                    .foregroundStyle(tag.1)
                    .padding(.horizontal, 6)
                    .padding(.vertical, 1)
                    .background(tag.1.opacity(0.12), in: .rect(cornerRadius: 5))
            }
            Image(systemName: "speaker.wave.2").foregroundStyle(Color.lxAccent)
        }
        .contentShape(.rect)
        .onTapGesture(perform: action)
        .accessibilityAddTraits(selected ? [.isButton, .isSelected] : .isButton)
    }

    /// 选中系统音色就读一句试听
    private func playSystem(_ sample: String, voice: AVSpeechSynthesisVoice?) {
        Speaker.shared.stop()
        preview.stopSpeaking(at: .immediate)
        #if os(iOS)
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .spokenAudio)
        try? AVAudioSession.sharedInstance().setActive(true)
        #endif
        let utterance = AVSpeechUtterance(string: sample)
        utterance.voice = voice ?? AVSpeechSynthesisVoice(language: sample.isMostlyChinese ? "zh-CN" : "en-US")
        utterance.rate = Speaker.systemRate
        preview.speak(utterance)
    }

    /// 改了语速：用现在选的音色读一句听听效果
    private func playCurrent() {
        if let voice = AIVoice.selected, AIVoice.apiKey != nil {
            Speaker.shared.playAI(.english(englishSample), voice: voice)
        } else {
            playSystem(englishSample, voice: SystemVoice.resolve(englishVoice))
        }
    }
}

/// 全局朗读速度：设置页和场景练习里都能调
struct SpeechSpeedPicker: View {
    @AppStorage(SettingsKey.playbackSpeed) private var speed = 1.0

    var body: some View {
        Picker(selection: $speed) {
            ForEach(Speaker.speeds, id: \.self) { Text(Speaker.speedLabel($0)).tag($0) }
        } label: {
            Text("朗读速度")
        }
        .onChange(of: speed) { _, value in Analytics.track(.settingsSpeed, ["speed": Speaker.speedLabel(value)]) }
    }
}
