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

/// 朗读的音色和语速：英文、中文分别选，可以试听。“增强 / 高级”音色可以在系统设置里免费下载
struct SpeechVoicesView: View {
    @AppStorage(SettingsKey.voiceEnglish) private var englishVoice = ""
    @AppStorage(SettingsKey.voiceChinese) private var chineseVoice = ""
    @AppStorage(SettingsKey.speechRate) private var rate = 0.5
    @State private var preview = AVSpeechSynthesizer()

    private static func voices(_ prefix: String) -> [AVSpeechSynthesisVoice] {
        AVSpeechSynthesisVoice.speechVoices()
            .filter { $0.language.hasPrefix(prefix) }
            .sorted { ($0.quality.rawValue, $0.name) > ($1.quality.rawValue, $1.name) }
    }

    var body: some View {
        Form {
            Section {
                HStack {
                    Image(systemName: "tortoise").foregroundStyle(.secondary)
                    Slider(value: $rate, in: 0.3...0.62)
                    Image(systemName: "hare").foregroundStyle(.secondary)
                }
            } header: {
                Text("语速")
            } footer: {
                Text("查单词时的英、美真人发音不受影响。")
            }
            voiceSection("英文", prefix: "en", selection: $englishVoice,
                         sample: "Hi, the plumber will come by tomorrow morning.")
            voiceSection("中文", prefix: "zh", selection: $chineseVoice, sample: "你好，水管工明天上午会过来。")
            Section {
            } footer: {
                Text("标着“增强”“高级”的音色更自然。没有的话，可以在系统“设置 → 辅助功能 → 朗读内容 → 声音”里免费下载，下载后回到这里就能选。")
            }
        }
        .formStyle(.grouped)
        .navigationTitle("朗读声音")
        .inlineNavigationTitle()
        .onDisappear { preview.stopSpeaking(at: .immediate) }
    }

    private func voiceSection(_ title: String, prefix: String, selection: Binding<String>, sample: String) -> some View {
        Section(title) {
            voiceRow(name: "默认", detail: prefix == "en" ? "跟随英式 / 美式口音设置" : "系统默认", id: "", selection: selection,
                     sample: sample, voice: nil)
            ForEach(Self.voices(prefix), id: \.identifier) { voice in
                voiceRow(name: voice.name, detail: Self.region(voice.language), id: voice.identifier, selection: selection,
                         sample: sample, voice: voice, quality: voice.quality)
            }
        }
    }

    private func voiceRow(name: String, detail: String, id: String, selection: Binding<String>, sample: String,
                          voice: AVSpeechSynthesisVoice?, quality: AVSpeechSynthesisVoiceQuality = .default) -> some View {
        HStack(spacing: 10) {
            Image(systemName: "checkmark")
                .foregroundStyle(Color.lxAccent)
                .opacity(selection.wrappedValue == id ? 1 : 0)
                .frame(width: 18)
            VStack(alignment: .leading, spacing: 1) {
                Text(name)
                Text(detail).font(.caption).foregroundStyle(.secondary)
            }
            if quality == .premium {
                tag("高级", .green)
            } else if quality == .enhanced {
                tag("增强", .lxAccent)
            }
            Spacer()
            Button {
                preview.stopSpeaking(at: .immediate)
                let utterance = AVSpeechUtterance(string: sample)
                utterance.voice = voice ?? AVSpeechSynthesisVoice(language: name == "默认" && sample.isMostlyChinese ? "zh-CN" : "en-US")
                utterance.rate = Float(rate)
                preview.speak(utterance)
            } label: {
                Image(systemName: "speaker.wave.2").frame(width: 36, height: 32)
            }
            .buttonStyle(.borderless)
            .accessibilityLabel("试听\(name)")
        }
        .contentShape(.rect)
        .onTapGesture { selection.wrappedValue = id }
    }

    private func tag(_ text: String, _ color: Color) -> some View {
        Text(text)
            .font(.caption2.weight(.bold))
            .foregroundStyle(color)
            .padding(.horizontal, 6)
            .padding(.vertical, 1)
            .background(color.opacity(0.12), in: .rect(cornerRadius: 5))
    }

    private static func region(_ language: String) -> String {
        switch language {
        case "en-US": "美式"
        case "en-GB": "英式"
        case "en-AU": "澳式"
        case "en-IE": "爱尔兰"
        case "en-IN": "印度"
        case "en-ZA": "南非"
        case "zh-CN": "普通话"
        case "zh-TW": "台湾"
        case "zh-HK": "粤语"
        default: language
        }
    }
}
