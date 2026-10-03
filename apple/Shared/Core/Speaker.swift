import AVFoundation

/// 要朗读的一段话。同一段话再点一次就是停止。
struct Speech: Equatable {
    let text: String
    let isChinese: Bool
    /// 英文口音：1 英音，2 美音（中文时不用）
    let accent: Int

    static func english(_ text: String, accent: Int? = nil) -> Speech {
        Speech(text: text, isChinese: false, accent: accent ?? Speaker.defaultAccent)
    }

    static func chinese(_ text: String) -> Speech {
        Speech(text: text, isChinese: true, accent: 0)
    }

    static func text(_ text: String, isChinese: Bool) -> Speech {
        isChinese ? chinese(text) : english(text)
    }
}

/// 发音：英文优先用有道真人发音（区分英/美音），拿不到或中文时用系统语音合成（离线可用）。
@MainActor
final class Speaker: NSObject, ObservableObject, AVSpeechSynthesizerDelegate, AVAudioPlayerDelegate {
    static let shared = Speaker()

    /// 正在朗读的内容；界面据此把“朗读”按钮换成“停止”
    @Published private(set) var playing: Speech?

    private let synthesizer = AVSpeechSynthesizer()
    private var utterance: AVSpeechUtterance?
    private var player: AVPlayer?
    private var observers: [NSObjectProtocol] = []
    /// AI 音色读出来的声音
    private var aiPlayer: AVAudioPlayer?
    private var aiTask: Task<Void, Never>?
    /// 读过的 AI 声音缓存一下，同一句再读不用再请求（也不再花钱）
    private var aiCache: [String: Data] = [:]

    /// 设置里选的默认口音：1 英音，2 美音
    nonisolated static var defaultAccent: Int {
        UserDefaults.standard.integer(forKey: SettingsKey.accent) == 1 ? 1 : 2
    }

    override private init() {
        super.init()
        synthesizer.delegate = self
    }

    /// 没在读这段就开始读；正在读这段就停下
    func toggle(_ speech: Speech) {
        if playing == speech {
            stop()
        } else {
            play(speech)
        }
    }

    /// keepAudioSession：同声传译时一边收音一边朗读，不切换音频通道
    func play(_ speech: Speech, keepAudioSession: Bool = false) {
        stop()
        #if os(iOS)
        // 静音开关打开时也能朗读
        if !keepAudioSession {
            try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .spokenAudio)
        }
        #endif
        playing = speech
        // 英文单词和短语用有道真人发音；句子用选的音色：AI 音色或系统音色
        if !speech.isChinese, Self.isWordLike(speech.text) {
            playOnline(speech)
        } else if let voice = AIVoice.selected, AIVoice.apiKey != nil {
            playAI(speech, voice: voice)
        } else {
            synthesize(speech)
        }
    }

    private static func isWordLike(_ text: String) -> Bool {
        text.count <= 40 && text.split(separator: " ").count <= 4 && !text.contains(where: { ".!?,;\n".contains($0) })
    }

    /// 用某个 AI 音色读一段（设置里试听也用它）
    func playAI(_ speech: Speech, voice: String) {
        stop()
        playing = speech
        let key = voice + "|" + speech.text
        aiTask = Task {
            var data = aiCache[key]
            if data == nil {
                data = try? await AIVoice.speak(speech.text, voice: voice)
                if let data { aiCache[key] = data }
                if aiCache.count > 40 { aiCache.removeAll() }
            }
            guard !Task.isCancelled, playing == speech else { return }
            guard let data, let player = try? AVAudioPlayer(data: data) else {
                // 网络或 Key 有问题：改用系统音色
                synthesize(speech)
                return
            }
            player.delegate = self
            player.play()
            aiPlayer = player
        }
    }

    nonisolated func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        Task { @MainActor in
            if self.aiPlayer === player {
                self.aiPlayer = nil
                self.playing = nil
            }
        }
    }

    func stop() {
        aiTask?.cancel()
        aiTask = nil
        aiPlayer?.stop()
        aiPlayer = nil
        player?.pause()
        player = nil
        observers.forEach(NotificationCenter.default.removeObserver)
        observers = []
        utterance = nil
        synthesizer.stopSpeaking(at: .immediate)
        playing = nil
    }

    private func playOnline(_ speech: Speech) {
        var comps = URLComponents(string: "https://dict.youdao.com/dictvoice")!
        comps.queryItems = [URLQueryItem(name: "audio", value: speech.text),
                            URLQueryItem(name: "type", value: String(speech.accent))]
        let item = AVPlayerItem(url: comps.url!)
        let center = NotificationCenter.default
        observers = [
            center.addObserver(forName: .AVPlayerItemDidPlayToEndTime, object: item, queue: .main) { [weak self] _ in
                Task { @MainActor in
                    if self?.playing == speech { self?.stop() }
                }
            },
            // 在线发音拿不到时退回系统语音
            center.addObserver(forName: .AVPlayerItemFailedToPlayToEndTime, object: item, queue: .main) { [weak self] _ in
                Task { @MainActor in
                    guard let self, self.playing == speech else { return }
                    self.player = nil
                    self.synthesize(speech)
                }
            },
        ]
        player = AVPlayer(playerItem: item)
        player?.play()
    }

    private func synthesize(_ speech: Speech) {
        let utterance = AVSpeechUtterance(string: speech.text)
        let defaults = UserDefaults.standard
        // 设置里选了音色就用它，没选按口音用系统默认
        let chosen = defaults.string(forKey: speech.isChinese ? SettingsKey.voiceChinese : SettingsKey.voiceEnglish) ?? ""
        utterance.voice = SystemVoice.resolve(chosen)
            ?? AVSpeechSynthesisVoice(language: speech.isChinese ? "zh-CN" : (speech.accent == 1 ? "en-GB" : "en-US"))
        if defaults.object(forKey: SettingsKey.speechRate) != nil {
            utterance.rate = Float(defaults.double(forKey: SettingsKey.speechRate))
        }
        self.utterance = utterance
        synthesizer.speak(utterance)
    }

    // MARK: AVSpeechSynthesizerDelegate

    nonisolated func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) {
        Task { @MainActor in self.finished(utterance) }
    }

    nonisolated func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didCancel utterance: AVSpeechUtterance) {
        Task { @MainActor in self.finished(utterance) }
    }

    /// 只有当前这段读完才清状态；被新的一段打断的旧回调不算
    private func finished(_ utterance: AVSpeechUtterance) {
        guard utterance === self.utterance else { return }
        self.utterance = nil
        playing = nil
    }
}

enum SettingsKey {
    static let accent = "speech.accent"
    static let autoSpeak = "speech.autoSpeak"
    /// 在译文下面显示这次 AI 用了多少 token（默认不显示，用量报表里都有）
    static let showAIUsage = "ai.showUsage"
    /// 语音输入说完后直接翻译（默认先放进输入框，可以改）
    static let voiceAutoSend = "voice.autoSend"
    /// 外观：0 跟随系统，1 浅色，2 深色
    static let appearance = "ui.appearance"
    /// 朗读语速（AVSpeechUtterance 的 rate，默认 0.5）和选的音色（空表示默认）
    static let speechRate = "speech.rate"
    static let voiceEnglish = "speech.voice.en"
    static let voiceChinese = "speech.voice.zh"
    /// 选的 AI 音色（OpenAI 的 voice 名字），空表示不用 AI 音色
    static let aiVoice = "speech.aiVoice"
    /// 同声传译默认用耳机朗读译文；面对面对话朗读译文
    static let interpreterSpeak = "interpreter.speak"
    static let dialogSpeak = "dialog.speak"
}

/// 精选的系统音色：只留好听、有代表性的几个，不要带特效的
struct SystemVoice: Identifiable {
    let language: String
    let name: String
    let detail: String
    var id: String { language + "|" + name }

    static let english = [
        SystemVoice(language: "en-US", name: "Ava", detail: "美式 · 女声"),
        SystemVoice(language: "en-US", name: "Zoe", detail: "美式 · 女声"),
        SystemVoice(language: "en-US", name: "Evan", detail: "美式 · 男声"),
        SystemVoice(language: "en-GB", name: "Serena", detail: "英式 · 女声"),
        SystemVoice(language: "en-GB", name: "Daniel", detail: "英式 · 男声"),
    ]
    static let chinese = [
        SystemVoice(language: "zh-CN", name: "Lili", detail: "普通话 · 女声"),
        SystemVoice(language: "zh-CN", name: "Tingting", detail: "普通话 · 女声"),
        SystemVoice(language: "zh-CN", name: "Li-Mu", detail: "普通话 · 男声"),
    ]

    /// 这台设备上这个音色最好的版本（高级 > 增强 > 基础）；没有就是 nil
    var best: AVSpeechSynthesisVoice? {
        AVSpeechSynthesisVoice.speechVoices()
            .filter { $0.language == language && $0.name.replacingOccurrences(of: "-", with: "").lowercased()
                == name.replacingOccurrences(of: "-", with: "").lowercased() }
            .max { $0.quality.rawValue < $1.quality.rawValue }
    }

    /// 设置里存的是“语言|名字”，用的时候取最好的版本；以前存的音色 ID 也认
    static func resolve(_ stored: String) -> AVSpeechSynthesisVoice? {
        guard !stored.isEmpty else { return nil }
        let parts = stored.split(separator: "|").map(String.init)
        if parts.count == 2 { return SystemVoice(language: parts[0], name: parts[1], detail: "").best }
        return AVSpeechSynthesisVoice(identifier: stored)
    }
}

/// AI 音色：OpenAI 的语音合成，更像真人。用 ChatGPT 的 API Key，按字数计费
enum AIVoice {
    static let voices: [(id: String, detail: String)] = [
        ("nova", "明亮的女声"), ("shimmer", "柔和的女声"), ("coral", "温暖的女声"),
        ("alloy", "中性的声音"), ("echo", "沉稳的男声"), ("onyx", "低沉的男声"),
    ]

    static var selected: String? {
        let value = UserDefaults.standard.string(forKey: SettingsKey.aiVoice) ?? ""
        return value.isEmpty ? nil : value
    }

    /// ChatGPT 的 Key（设置里 ChatGPT 那一项填的）
    static var apiKey: String? {
        let key = Keychain.get(account: "openai")?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return key.isEmpty ? nil : key
    }

    static func speak(_ text: String, voice: String) async throws -> Data {
        guard let key = apiKey else { throw URLError(.userAuthenticationRequired) }
        var base = UserDefaults.standard.string(forKey: "ai.baseURL.openai") ?? "https://api.openai.com/v1"
        if base.trimmingCharacters(in: .whitespaces).isEmpty { base = "https://api.openai.com/v1" }
        while base.hasSuffix("/") { base.removeLast() }
        var request = URLRequest(url: URL(string: base + "/audio/speech")!, timeoutInterval: 30)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("Bearer \(key)", forHTTPHeaderField: "Authorization")
        request.httpBody = try JSONSerialization.data(withJSONObject: [
            "model": "gpt-4o-mini-tts", "voice": voice, "input": String(text.prefix(4000)), "response_format": "mp3",
        ])
        let (data, response) = try await URLSession.shared.data(for: request)
        guard (response as? HTTPURLResponse)?.statusCode == 200 else { throw URLError(.badServerResponse) }
        return data
    }
}
