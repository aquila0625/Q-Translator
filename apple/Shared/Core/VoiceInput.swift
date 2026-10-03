import AVFoundation
import Speech

/// 语音输入：用系统的语音识别把说的话实时变成文字，同时把原声录下来，之后可以回放。
/// 能在本机识别时只在本机识别，录音不上传。
@MainActor
final class VoiceInput: ObservableObject {
    static let shared = VoiceInput()

    enum State: Equatable {
        case idle
        case listening
        case failed(String)
    }

    /// 识别的语言：英语或中文
    enum Language: String {
        case english = "en-US"
        case chinese = "zh-CN"

        var title: String { self == .english ? "英语" : "中文" }
        var toggled: Language { self == .english ? .chinese : .english }
    }

    @Published private(set) var state: State = .idle
    /// 已经说完的部分和还在变化的部分，界面上后者用浅色显示
    @Published private(set) var finalText = ""
    @Published private(set) var volatileText = ""
    @Published private(set) var language: Language
    /// 最近一段的音量（0…1），用来画声波
    @Published private(set) var levels: [CGFloat] = Array(repeating: 0, count: 24)
    @Published private(set) var startedAt: Date?

    var text: String { (finalText + volatileText).trimmed }
    var isListening: Bool { state == .listening }

    private let engine = AVAudioEngine()
    private var request: SFSpeechAudioBufferRecognitionRequest? {
        didSet { sink.setRequest(request) }
    }
    private var task: SFSpeechRecognitionTask?
    private var audioName: String?
    /// 录音线程直接把声音交给识别和录音文件（系统会复用缓冲区，不能等到主线程再处理）
    private let sink = TapSink()
    /// 切换语言时，前一段识别出来的文字保留下来
    private var committed = ""

    private init() {
        language = Language(rawValue: UserDefaults.standard.string(forKey: "voice.language") ?? "") ?? .english
    }

    // MARK: 开始和结束

    /// 开始听。preferred 为 nil 时用上次用的语言
    func start(preferred: Language?) async {
        guard state != .listening else { return }
        if let preferred { language = preferred }
        guard await requestPermissions() else {
            state = .failed("需要允许使用麦克风和语音识别，可以在系统设置里打开。")
            return
        }
        finalText = ""
        volatileText = ""
        committed = ""
        levels = Array(repeating: 0, count: levels.count)
        do {
            try startEngine()
            try startRecognition()
            startedAt = Date()
            state = .listening
        } catch {
            stopEngine()
            state = .failed("没法开始录音：\(error.localizedDescription)")
        }
    }

    /// 说完了：返回识别出的文字、原声文件名和时长
    func stop() -> (text: String, audio: String?, duration: Double) {
        let result = text
        let duration = startedAt.map { Date().timeIntervalSince($0) } ?? 0
        let audio = audioName
        request?.endAudio()
        task?.finish()
        stopEngine()
        state = .idle
        startedAt = nil
        audioName = nil
        UserDefaults.standard.set(language.rawValue, forKey: "voice.language")
        // 没说出内容时不保留录音
        if result.isEmpty, let audio { ConversationStore.deleteMediaFile(audio) }
        return (result, result.isEmpty ? nil : audio, duration)
    }

    /// 取消：不要文字，也不要录音
    func cancel() {
        let audio = audioName
        _ = stop()
        if let audio { ConversationStore.deleteMediaFile(audio) }
        finalText = ""
        volatileText = ""
    }

    /// 说到一半切换中英：前面识别出的文字保留，后面按新语言识别
    func switchLanguage() {
        language = language.toggled
        UserDefaults.standard.set(language.rawValue, forKey: "voice.language")
        guard state == .listening else { return }
        committed = text.isEmpty ? "" : text + " "
        finalText = committed
        volatileText = ""
        task?.cancel()
        request?.endAudio()
        try? startRecognition()
    }

    func clearError() {
        if case .failed = state { state = .idle }
    }

    // MARK: 权限

    private func requestPermissions() async -> Bool {
        let speech = await withCheckedContinuation { continuation in
            SFSpeechRecognizer.requestAuthorization { continuation.resume(returning: $0 == .authorized) }
        }
        guard speech else { return false }
        #if os(iOS)
        return await AVAudioApplication.requestRecordPermission()
        #else
        return await AVCaptureDevice.requestAccess(for: .audio)
        #endif
    }

    // MARK: 录音

    private func startEngine() throws {
        #if os(iOS)
        let session = AVAudioSession.sharedInstance()
        // 不用蓝牙通话模式：开始录音时切换要一两秒，切换没完成就开始录会收不到声音。用手机麦克风收音
        try session.setCategory(.playAndRecord, mode: .measurement, options: [.duckOthers, .defaultToSpeaker, .allowBluetoothA2DP])
        try session.setActive(true, options: .notifyOthersOnDeactivation)
        #endif
        Speaker.shared.stop()
        let input = engine.inputNode
        let format = input.outputFormat(forBus: 0)
        guard format.sampleRate > 0, format.channelCount > 0 else { throw VoiceError.noMicrophone }

        // 原声存成 m4a，和图片放在一起
        let name = UUID().uuidString + ".m4a"
        let settings: [String: Any] = [
            AVFormatIDKey: kAudioFormatMPEG4AAC,
            AVSampleRateKey: format.sampleRate,
            AVNumberOfChannelsKey: format.channelCount,
            AVEncoderBitRateKey: 64_000,
        ]
        let file = try? AVAudioFile(forWriting: ConversationStore.mediaURL(name), settings: settings,
                                    commonFormat: format.commonFormat, interleaved: format.isInterleaved)
        sink.setFile(file)
        audioName = file == nil ? nil : name

        try installTap()
        engine.prepare()
        try engine.start()
        lastAudioAt = Date()
        observeAudioChanges()
    }

    private var lastAudioAt = Date()
    private var watchdog: Task<Void, Never>?
    private var audioObservers: [NSObjectProtocol] = []

    private func installTap() throws {
        let input = engine.inputNode
        let format = input.outputFormat(forBus: 0)
        guard format.sampleRate > 0, format.channelCount > 0 else { throw VoiceError.noMicrophone }
        input.removeTap(onBus: 0)
        let sink = self.sink
        input.installTap(onBus: 0, bufferSize: 1024, format: format) { [weak self] buffer, _ in
            // 在音频线程上：交给识别、写进文件；音量交给界面
            sink.consume(buffer)
            let level = Self.level(of: buffer)
            Task { @MainActor in
                guard let self else { return }
                self.lastAudioAt = Date()
                self.levels.removeFirst()
                self.levels.append(level)
            }
        }
    }

    /// 声音设备变了或者超过 2 秒没收到声音：重装录音回调再开始
    private func observeAudioChanges() {
        audioObservers.forEach(NotificationCenter.default.removeObserver)
        audioObservers = [NotificationCenter.default.addObserver(forName: .AVAudioEngineConfigurationChange, object: engine,
                                                                 queue: .main) { [weak self] _ in
            Task { @MainActor in self?.restartAudio() }
        }]
        watchdog?.cancel()
        watchdog = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(1))
                guard let self, self.state == .listening else { continue }
                if Date().timeIntervalSince(self.lastAudioAt) > 2 { self.restartAudio() }
            }
        }
    }

    private func restartAudio() {
        guard state == .listening else { return }
        lastAudioAt = Date()
        engine.stop()
        engine.inputNode.removeTap(onBus: 0)
        #if os(iOS)
        try? AVAudioSession.sharedInstance().setActive(true)
        #endif
        try? installTap()
        engine.prepare()
        try? engine.start()
    }

    private func stopEngine() {
        watchdog?.cancel()
        watchdog = nil
        audioObservers.forEach(NotificationCenter.default.removeObserver)
        audioObservers = []
        if engine.isRunning { engine.stop() }
        engine.inputNode.removeTap(onBus: 0)
        sink.setFile(nil)
        request = nil
        task = nil
        #if os(iOS)
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        #endif
    }

    // MARK: 识别

    private func startRecognition() throws {
        guard let recognizer = SFSpeechRecognizer(locale: Locale(identifier: language.rawValue)), recognizer.isAvailable else {
            throw VoiceError.recognizerUnavailable
        }
        let request = SFSpeechAudioBufferRecognitionRequest()
        request.shouldReportPartialResults = true
        request.addsPunctuation = true
        // 能在本机识别就只在本机识别，录音不上传
        if recognizer.supportsOnDeviceRecognition { request.requiresOnDeviceRecognition = true }
        self.request = request
        let prefix = committed
        task = recognizer.recognitionTask(with: request) { [weak self] result, error in
            let text = result?.bestTranscription.formattedString
            let isFinal = result?.isFinal ?? false
            let failed = error != nil && result == nil
            Task { @MainActor in
                guard let self, self.request === request else { return }
                if let text {
                    if isFinal {
                        self.finalText = prefix + text
                        self.volatileText = ""
                    } else {
                        // 最后几个字还可能变，用浅色显示
                        let words = text.split(separator: " ", omittingEmptySubsequences: false)
                        if self.language == .english, words.count > 2 {
                            self.finalText = prefix + words.dropLast(2).joined(separator: " ") + " "
                            self.volatileText = words.suffix(2).joined(separator: " ")
                        } else if self.language == .chinese, text.count > 3 {
                            self.finalText = prefix + String(text.dropLast(3))
                            self.volatileText = String(text.suffix(3))
                        } else {
                            self.finalText = prefix
                            self.volatileText = text
                        }
                    }
                } else if failed, self.text.isEmpty {
                    self.volatileText = ""
                }
            }
        }
    }

    // MARK: 工具

    private nonisolated static func level(of buffer: AVAudioPCMBuffer) -> CGFloat {
        guard let data = buffer.floatChannelData?[0], buffer.frameLength > 0 else { return 0 }
        var sum: Float = 0
        for i in 0..<Int(buffer.frameLength) { sum += data[i] * data[i] }
        let rms = sqrt(sum / Float(buffer.frameLength))
        // 转成分贝再映射到 0…1，安静时接近 0
        let db = 20 * log10(max(rms, 0.000_01))
        return CGFloat(min(1, max(0, (db + 55) / 45)))
    }

    enum VoiceError: LocalizedError {
        case noMicrophone, recognizerUnavailable
        var errorDescription: String? {
            switch self {
            case .noMicrophone: "没有找到麦克风"
            case .recognizerUnavailable: "这台设备暂时不能识别这种语言"
            }
        }
    }
}

/// 回放语音输入时录下的原声
@MainActor
final class AudioPlayback: NSObject, ObservableObject, AVAudioPlayerDelegate {
    static let shared = AudioPlayback()

    @Published private(set) var playing: String?
    private var player: AVAudioPlayer?

    func toggle(_ name: String) {
        if playing == name {
            stop()
            return
        }
        stop()
        Speaker.shared.stop()
        #if os(iOS)
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .spokenAudio)
        try? AVAudioSession.sharedInstance().setActive(true)
        #endif
        guard let player = try? AVAudioPlayer(contentsOf: ConversationStore.mediaURL(name)) else { return }
        player.delegate = self
        player.play()
        self.player = player
        playing = name
    }

    func stop() {
        player?.stop()
        player = nil
        playing = nil
    }

    nonisolated func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        Task { @MainActor in self.stop() }
    }
}

/// 录音线程和主线程共用的出口：识别请求和录音文件，用锁保护
private final class TapSink: @unchecked Sendable {
    private let lock = NSLock()
    private var request: SFSpeechAudioBufferRecognitionRequest?
    private var file: AVAudioFile?

    func setRequest(_ request: SFSpeechAudioBufferRecognitionRequest?) {
        lock.withLock { self.request = request }
    }

    func setFile(_ file: AVAudioFile?) {
        lock.withLock { self.file = file }
    }

    func consume(_ buffer: AVAudioPCMBuffer) {
        lock.withLock {
            request?.append(buffer)
            try? file?.write(from: buffer)
        }
    }
}
