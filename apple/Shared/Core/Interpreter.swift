import AVFoundation
import Speech
#if os(iOS)
import UIKit
#endif

/// 同声传译：持续收音，用系统新一代识别模型（本机、离线）实时转写，一句话说完就翻译，滚动显示双语字幕。
@MainActor
final class Interpreter: ObservableObject {
    struct Segment: Identifiable, Equatable {
        let id = UUID()
        var original: String
        var translation: String?
    }

    enum State: Equatable {
        case idle
        case preparing(String)
        case running
        case paused
        case failed(String)
    }

    @Published private(set) var state: State = .idle
    @Published private(set) var segments: [Segment] = []
    /// 还在说、没说完的那一句，以及它边说边翻的译文
    @Published private(set) var live = "" {
        didSet { if live != oldValue { translateLive() } }
    }
    @Published private(set) var liveTranslation = ""
    @Published private(set) var levels: [CGFloat] = Array(repeating: 0, count: 30)
    @Published private(set) var startedAt: Date?
    /// 原文语言：true 是中文（中 → 英），false 是英语（英 → 中）
    @Published private(set) var sourceIsChinese = false
    /// 用耳机朗读译文
    @Published var speakTranslations = UserDefaults.standard.bool(forKey: SettingsKey.interpreterSpeak)

    /// (文字, 原文是否中文, 是否还没说完的半句) -> 译文
    private let translate: (String, Bool, Bool) async -> String?
    private var liveTranslating = false
    private var liveDirty = false
    private let engine = AVAudioEngine()
    private var analyzer: SpeechAnalyzer?
    private var transcriber: SpeechTranscriber?
    private var input: AsyncStream<AnalyzerInput>.Continuation?
    private var resultsTask: Task<Void, Never>?
    /// 设备不支持新一代识别模型时，退回系统的老识别接口
    private var legacyRecognizer: SFSpeechRecognizer?
    private var legacyTask: SFSpeechRecognitionTask?
    /// 每换一次识别器加一；旧识别任务晚到的回调按这个丢掉
    private var legacyGeneration = 0
    /// 正在用的识别方式，显示给用户看
    @Published private(set) var engineName = ""
    /// 这一段识别结果里已经切出去的字（只数文字和数字，不数标点和空格）。
    /// 识别会回头修改前面的内容（比如补标点），按字符位置记会错位、丢字
    private var emittedLetters = 0
    /// 暂停时累计的时长
    private var elapsedBeforePause: TimeInterval = 0

    init(translate: @escaping (String, Bool, Bool) async -> String?) {
        self.translate = translate
    }

    var elapsed: TimeInterval {
        elapsedBeforePause + (startedAt.map { Date().timeIntervalSince($0) } ?? 0)
    }

    var isActive: Bool { state == .running || state == .paused }

    // MARK: 开始、暂停、结束

    func start(sourceIsChinese: Bool) async {
        self.sourceIsChinese = sourceIsChinese
        guard await requestPermissions() else {
            state = .failed("需要允许使用麦克风和语音识别，可以在系统设置里打开。")
            return
        }
        do {
            try await startRecognition()
            try startAudio()
            startedAt = Date()
            state = .running
            #if os(iOS)
            UIApplication.shared.isIdleTimerDisabled = true
            #endif
        } catch {
            stopAudio()
            state = .failed("没法开始同传：\(error.localizedDescription)")
        }
    }

    func pause() {
        guard state == .running else { return }
        engine.pause()
        elapsedBeforePause = elapsed
        startedAt = nil
        state = .paused
    }

    func resume() {
        guard state == .paused else { return }
        lastAudioAt = Date()
        startedAt = Date()
        state = .running
        if (try? engine.start()) == nil { restartAudio(reason: "继续") }
    }

    /// 结束：把还没说完的那一句也收进来
    func stop() async {
        guard isActive || state != .idle else { return }
        stopAudio()
        input?.finish()
        let recognizer = legacyRecognizer
        legacyRecognizer = nil
        legacyTask?.finish()
        legacyTask = nil
        _ = recognizer
        try? await analyzer?.finalizeAndFinishThroughEndOfInput()
        await resultsTask?.value
        if !live.trimmed.isEmpty { emit(live) }
        live = ""
        analyzer = nil
        transcriber = nil
        elapsedBeforePause = elapsed
        startedAt = nil
        state = .idle
        #if os(iOS)
        UIApplication.shared.isIdleTimerDisabled = false
        #endif
    }

    /// 中途换方向：前面的字幕保留，后面按新语言识别。
    /// 录音不停，只换识别器：正在说的半句直接收进字幕，不等旧的识别器收尾，所以很快
    func switchDirection() async {
        let wasRunning = state == .running
        let wasPaused = state == .paused
        guard wasRunning || wasPaused else { return }
        if !live.trimmed.isEmpty { emit(live) }
        live = ""
        stopRecognition()
        sourceIsChinese.toggle()
        state = .preparing("正在切换到\(sourceIsChinese ? "中文" : "英语")…")
        do {
            try await startRecognition()
            // 新旧识别要的声音格式可能不一样：重装录音回调，音频通道不用关
            engine.stop()
            try installTap()
            engine.prepare()
            if wasRunning {
                try engine.start()
                lastAudioAt = Date()
                state = .running
            } else {
                state = .paused
            }
        } catch {
            state = .failed("切换语言失败：\(error.localizedDescription)")
        }
    }

    /// 马上停掉现在的识别器（不等它把最后一句收完）
    private func stopRecognition() {
        sink.setContinuation(nil)
        sink.setLegacyRequest(nil)
        input?.finish()
        input = nil
        resultsTask?.cancel()
        resultsTask = nil
        legacyGeneration += 1
        legacyTask?.cancel()
        legacyTask = nil
        legacyRecognizer = nil
        let old = analyzer
        analyzer = nil
        transcriber = nil
        Task { await old?.cancelAndFinishNow() }
    }

    /// 提前把另一种语言的识别模型准备好（登记、需要时下载），中途切换语言时不用再等
    func prepareOtherLanguage() async {
        let id = sourceIsChinese ? "en-US" : "zh-CN"
        guard let locale = await SpeechTranscriber.supportedLocale(equivalentTo: Locale(identifier: id)) else { return }
        let transcriber = SpeechTranscriber(locale: locale, transcriptionOptions: [], reportingOptions: [.volatileResults],
                                            attributeOptions: [])
        guard (try? await reserve(locale)) != nil else { return }
        if let request = try? await AssetInventory.assetInstallationRequest(supporting: [transcriber]) {
            try? await request.downloadAndInstall()
        }
    }

    /// 向系统登记要用这种语言；登记数满了就释放一个不是中文、也不是英语的
    private func reserve(_ locale: Locale) async throws {
        let reserved = await AssetInventory.reservedLocales
        guard !reserved.contains(where: { $0.identifier(.bcp47) == locale.identifier(.bcp47) }) else { return }
        if reserved.count >= AssetInventory.maximumReservedLocales {
            let keep = ["en", "zh"]
            if let old = reserved.first(where: { !keep.contains($0.language.languageCode?.identifier ?? "") }) ?? reserved.first {
                await AssetInventory.release(reservedLocale: old)
            }
        }
        _ = try await AssetInventory.reserve(locale: locale)
    }

    // MARK: 识别

    private func startRecognition() async throws {
        let id = sourceIsChinese ? "zh-CN" : "en-US"
        guard let locale = await SpeechTranscriber.supportedLocale(equivalentTo: Locale(identifier: id)) else {
            try await startLegacyRecognition()
            return
        }
        let transcriber = SpeechTranscriber(locale: locale, transcriptionOptions: [], reportingOptions: [.volatileResults],
                                            attributeOptions: [])
        // 先向系统登记要用这种语言（同时登记的语言有上限，满了就释放掉不用的）
        do {
            try await reserve(locale)
        } catch {
            log.error("interpreter: reserve failed \(error.localizedDescription, privacy: .public)")
            try await startLegacyRecognition()
            return
        }
        let status = await AssetInventory.status(forModules: [transcriber])
        if status == .unsupported {
            try await startLegacyRecognition()
            return
        }
        // 第一次用这种语言要下载识别模型
        if let request = try await AssetInventory.assetInstallationRequest(supporting: [transcriber]) {
            state = .preparing("正在下载\(sourceIsChinese ? "中文" : "英语")识别模型…")
            try await request.downloadAndInstall()
        }
        let analyzer = SpeechAnalyzer(modules: [transcriber])
        guard let format = await SpeechAnalyzer.bestAvailableAudioFormat(compatibleWith: [transcriber]) else {
            throw InterpreterError.unsupported
        }
        targetFormat = format
        let (stream, continuation) = AsyncStream<AnalyzerInput>.makeStream()
        input = continuation
        sink.setContinuation(continuation)
        try await analyzer.start(inputSequence: stream)
        self.analyzer = analyzer
        self.transcriber = transcriber
        engineName = "新一代本机识别"
        emittedLetters = 0
        resultsTask = Task { [weak self] in
            do {
                for try await result in transcriber.results {
                    if Task.isCancelled { break }
                    let text = String(result.text.characters)
                    await self?.handle(text, isFinal: result.isFinal)
                }
            } catch {}
        }
    }

    /// 老识别接口：一次识别结束（说完一段或者到了时长上限）就接着开下一次，保持一直在听
    private func startLegacyRecognition() async throws {
        let ok = await withCheckedContinuation { continuation in
            SFSpeechRecognizer.requestAuthorization { continuation.resume(returning: $0 == .authorized) }
        }
        guard ok else { throw InterpreterError.permission }
        guard let recognizer = SFSpeechRecognizer(locale: Locale(identifier: sourceIsChinese ? "zh-CN" : "en-US")),
              recognizer.isAvailable else { throw InterpreterError.unsupported }
        legacyRecognizer = recognizer
        targetFormat = nil
        engineName = recognizer.supportsOnDeviceRecognition ? "系统本机识别" : "系统识别"
        emittedLetters = 0
        startLegacyTask()
    }

    private func startLegacyTask() {
        guard let recognizer = legacyRecognizer else { return }
        let request = SFSpeechAudioBufferRecognitionRequest()
        request.shouldReportPartialResults = true
        request.addsPunctuation = true
        if recognizer.supportsOnDeviceRecognition { request.requiresOnDeviceRecognition = true }
        sink.setLegacyRequest(request)
        emittedLetters = 0
        let generation = legacyGeneration
        legacyTask = recognizer.recognitionTask(with: request) { [weak self] result, error in
            let text = result?.bestTranscription.formattedString
            let isFinal = (result?.isFinal ?? false) || error != nil
            Task { @MainActor in
                guard let self, self.legacyRecognizer != nil, self.legacyGeneration == generation else { return }
                if let text { self.handle(text, isFinal: isFinal) } else if isFinal, !self.live.isEmpty { self.handle(self.live, isFinal: true) }
                // 这一次识别结束了：还在同传就接着听
                if isFinal, self.state == .running || self.state == .paused {
                    // 稍等一下再接着听，避免没声音时反复报错、反复重开
                    try? await Task.sleep(for: .milliseconds(300))
                    if self.legacyRecognizer != nil, self.legacyGeneration == generation { self.startLegacyTask() }
                }
            }
        }
    }

    /// 一段识别结果：已经说完的句子马上切出去翻译，剩下的当作“还在说”
    private func handle(_ text: String, isFinal: Bool) {
        let chars = Array(text)
        let emitted = Self.index(afterLetters: emittedLetters, in: chars)
        var cut = emitted
        // 中文识别常常整段只在最后加句号、中间用逗号，所以一个分句够长时在逗号处也切开，译文才跟得上
        let clauseLimit = sourceIsChinese ? 14 : 60
        for i in emitted..<chars.count where "。！？!?.，,；;".contains(chars[i]) {
            // 标点后面还有字，说明这一句已经说完；英文的小数点（2.5）不算
            let next = i + 1 < chars.count ? chars[i + 1] : nil
            let prev = i > 0 ? chars[i - 1] : nil
            if chars[i] == ".", let prev, let next, prev.isNumber, next.isNumber { continue }
            guard next != nil || isFinal else { continue }
            if "，,；;".contains(chars[i]) {
                if i + 1 - cut >= clauseLimit { cut = i + 1 }
            } else {
                cut = i + 1
            }
        }
        if cut > emitted {
            for sentence in sentences(String(chars[emitted..<cut])) { emit(sentence) }
            emittedLetters += Self.letterCount(chars[emitted..<cut])
        }
        let start = max(cut, emitted)
        let rest = String(chars[start...]).trimmingCharacters(in: .whitespacesAndNewlines.union(.punctuationCharacters))
        if isFinal {
            if !rest.isEmpty { emit(rest) }
            emittedLetters = 0
            live = ""
        } else {
            live = rest
        }
    }

    private static func isLetter(_ ch: Character) -> Bool { ch.isLetter || ch.isNumber }

    private static func letterCount(_ chars: ArraySlice<Character>) -> Int { chars.filter(isLetter).count }

    /// 跳过前 n 个文字后的位置（连同紧跟的标点和空格）
    private static func index(afterLetters n: Int, in chars: [Character]) -> Int {
        guard n > 0 else { return 0 }
        var seen = 0
        var i = 0
        while i < chars.count, seen < n {
            if isLetter(chars[i]) { seen += 1 }
            i += 1
        }
        while i < chars.count, !isLetter(chars[i]) { i += 1 }
        return i
    }

    private func sentences(_ text: String) -> [String] {
        var result: [String] = []
        var current = ""
        for ch in text {
            current.append(ch)
            if "。！？!?".contains(ch) || (ch == "." && current.count > 1) || ("，,；;".contains(ch) && current.count >= (sourceIsChinese ? 14 : 60)) {
                if !current.trimmed.isEmpty { result.append(current.trimmed) }
                current = ""
            }
        }
        if !current.trimmed.isEmpty { result.append(current.trimmed) }
        return result
    }

    /// 边说边翻：还没说完的那句隔一会儿翻一次，同一时间只翻一次，翻完再看有没有新内容
    private func translateLive() {
        let text = live.trimmed
        guard text.count > 2 else {
            liveTranslation = ""
            return
        }
        if liveTranslating {
            liveDirty = true
            return
        }
        liveTranslating = true
        let chinese = sourceIsChinese
        Task {
            let result = await translate(text, chinese, true)
            liveTranslating = false
            if !live.trimmed.isEmpty, let result { liveTranslation = result }
            if liveDirty {
                liveDirty = false
                try? await Task.sleep(for: .milliseconds(150))
                translateLive()
            }
        }
    }

    private func emit(_ sentence: String) {
        let text = sentence.trimmed
        guard text.count > 1 else { return }
        // 说完的这句先用边说边翻的译文顶上，正式译文出来再替换
        var segment = Segment(original: text)
        segment.translation = liveTranslation.isEmpty ? nil : liveTranslation
        liveTranslation = ""
        segments.append(segment)
        let id = segment.id
        let chinese = sourceIsChinese
        Task {
            let translation = await translate(text, chinese, false) ?? "（翻译失败）"
            if let i = segments.firstIndex(where: { $0.id == id }) { segments[i].translation = translation }
            if speakTranslations { Speaker.shared.play(.text(translation, isChinese: !chinese), keepAudioSession: true) }
        }
    }

    // MARK: 录音

    private var targetFormat: AVAudioFormat?
    private let sink = AnalyzerSink()

    /// 最近一次收到声音的时间；超过 2 秒没收到就自动重启录音
    private var lastAudioAt = Date()
    private var watchdog: Task<Void, Never>?
    private var audioObservers: [NSObjectProtocol] = []

    private func startAudio() throws {
        #if os(iOS)
        let session = AVAudioSession.sharedInstance()
        // 只用蓝牙的高音质播放（A2DP）：声音用手机麦克风收，译文从耳机放。
        // 不用蓝牙通话模式（HFP）：开始录音时切换通话模式要一两秒，切换没完成就开始录会收不到声音
        try session.setCategory(.playAndRecord, mode: .default, options: [.allowBluetoothA2DP, .defaultToSpeaker])
        try session.setActive(true)
        #endif
        try installTap()
        engine.prepare()
        try engine.start()
        lastAudioAt = Date()
        observeAudioChanges()
        startWatchdog()
    }

    /// 按当前的声音设备格式装录音回调（换了设备格式会变，要重装）
    private func installTap() throws {
        let node = engine.inputNode
        let format = node.outputFormat(forBus: 0)
        guard format.sampleRate > 0, format.channelCount > 0 else { throw InterpreterError.noMicrophone }
        // 新识别模型要转换成它要的格式；老接口直接收原始声音
        var converter: AVAudioConverter?
        if let target = targetFormat {
            converter = AVAudioConverter(from: format, to: target)
            if converter == nil { throw InterpreterError.noMicrophone }
        }
        let target = targetFormat
        let sink = self.sink
        node.removeTap(onBus: 0)
        node.installTap(onBus: 0, bufferSize: 2048, format: format) { [weak self] buffer, _ in
            if let converter, let target {
                sink.convertAndSend(buffer, converter: converter, target: target)
            } else {
                sink.sendLegacy(buffer)
            }
            let level = Self.level(of: buffer)
            Task { @MainActor in
                guard let self else { return }
                self.lastAudioAt = Date()
                self.levels.removeFirst()
                self.levels.append(level)
            }
        }
    }

    /// 声音设备变了（插拔耳机、蓝牙连上断开）或者被打断（来电、Siri）结束后，重启录音
    private func observeAudioChanges() {
        removeAudioObservers()
        let center = NotificationCenter.default
        audioObservers.append(center.addObserver(forName: .AVAudioEngineConfigurationChange, object: engine, queue: .main) { [weak self] _ in
            Task { @MainActor in self?.restartAudio(reason: "设备变化") }
        })
        #if os(iOS)
        audioObservers.append(center.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { [weak self] note in
            let ended = (note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt) == AVAudioSession.InterruptionType.ended.rawValue
            Task { @MainActor in if ended { self?.restartAudio(reason: "打断结束") } }
        })
        #endif
    }

    private func removeAudioObservers() {
        audioObservers.forEach(NotificationCenter.default.removeObserver)
        audioObservers = []
    }

    private func startWatchdog() {
        watchdog?.cancel()
        watchdog = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(1))
                guard let self, self.state == .running else { continue }
                if Date().timeIntervalSince(self.lastAudioAt) > 2 { self.restartAudio(reason: "超过 2 秒没有声音") }
            }
        }
    }

    private func restartAudio(reason: String) {
        guard state == .running else { return }
        log.info("interpreter: restart audio, \(reason, privacy: .public)")
        lastAudioAt = Date()
        engine.stop()
        engine.inputNode.removeTap(onBus: 0)
        do {
            #if os(iOS)
            try AVAudioSession.sharedInstance().setActive(true)
            #endif
            try installTap()
            engine.prepare()
            try engine.start()
            // 老识别接口换了声音格式要重开一次
            if legacyRecognizer != nil { startLegacyTask() }
        } catch {
            log.error("interpreter: restart failed \(error.localizedDescription, privacy: .public)")
        }
    }

    private func stopAudio() {
        watchdog?.cancel()
        watchdog = nil
        removeAudioObservers()
        if engine.isRunning { engine.stop() }
        engine.inputNode.removeTap(onBus: 0)
        sink.setContinuation(nil)
        sink.setLegacyRequest(nil)
        #if os(iOS)
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        #endif
    }

    private func requestPermissions() async -> Bool {
        #if os(iOS)
        return await AVAudioApplication.requestRecordPermission()
        #else
        return await AVCaptureDevice.requestAccess(for: .audio)
        #endif
    }

    private nonisolated static func level(of buffer: AVAudioPCMBuffer) -> CGFloat {
        guard let data = buffer.floatChannelData?[0], buffer.frameLength > 0 else { return 0 }
        var sum: Float = 0
        for i in 0..<Int(buffer.frameLength) { sum += data[i] * data[i] }
        let db = 20 * log10(max(sqrt(sum / Float(buffer.frameLength)), 0.000_01))
        return CGFloat(min(1, max(0, (db + 55) / 45)))
    }

    enum InterpreterError: LocalizedError {
        case unsupported, noMicrophone, permission
        var errorDescription: String? {
            switch self {
            case .unsupported: "这台设备暂时不支持这种语言的实时识别"
            case .noMicrophone: "没有找到麦克风"
            case .permission: "需要允许使用语音识别"
            }
        }
    }
}

/// 录音线程上把声音转成识别模型要的格式，交给识别
private final class AnalyzerSink: @unchecked Sendable {
    private let lock = NSLock()
    private var continuation: AsyncStream<AnalyzerInput>.Continuation?
    private var legacyRequest: SFSpeechAudioBufferRecognitionRequest?

    func setLegacyRequest(_ request: SFSpeechAudioBufferRecognitionRequest?) {
        lock.withLock {
            legacyRequest?.endAudio()
            legacyRequest = request
        }
    }

    func sendLegacy(_ buffer: AVAudioPCMBuffer) {
        lock.withLock { legacyRequest?.append(buffer) }
    }

    func setContinuation(_ continuation: AsyncStream<AnalyzerInput>.Continuation?) {
        lock.withLock { self.continuation = continuation }
    }

    func convertAndSend(_ buffer: AVAudioPCMBuffer, converter: AVAudioConverter, target: AVAudioFormat) {
        let ratio = target.sampleRate / buffer.format.sampleRate
        guard let out = AVAudioPCMBuffer(pcmFormat: target, frameCapacity: AVAudioFrameCount(Double(buffer.frameLength) * ratio) + 64)
        else { return }
        var given = false
        var error: NSError?
        converter.convert(to: out, error: &error) { _, status in
            if given {
                status.pointee = .noDataNow
                return nil
            }
            given = true
            status.pointee = .haveData
            return buffer
        }
        guard error == nil, out.frameLength > 0 else { return }
        lock.withLock { _ = continuation?.yield(AnalyzerInput(buffer: out)) }
    }
}
