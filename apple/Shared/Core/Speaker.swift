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
final class Speaker: NSObject, ObservableObject, AVSpeechSynthesizerDelegate {
    static let shared = Speaker()

    /// 正在朗读的内容；界面据此把“朗读”按钮换成“停止”
    @Published private(set) var playing: Speech?

    private let synthesizer = AVSpeechSynthesizer()
    private var utterance: AVSpeechUtterance?
    private var player: AVPlayer?
    private var observers: [NSObjectProtocol] = []

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
        // 短的英文用真人发音，长段落和中文用系统语音
        if !speech.isChinese, speech.text.count <= 300 {
            playOnline(speech)
        } else {
            synthesize(speech)
        }
    }

    func stop() {
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
        utterance.voice = (chosen.isEmpty ? nil : AVSpeechSynthesisVoice(identifier: chosen))
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
    /// 同声传译默认用耳机朗读译文；面对面对话朗读译文
    static let interpreterSpeak = "interpreter.speak"
    static let dialogSpeak = "dialog.speak"
}
