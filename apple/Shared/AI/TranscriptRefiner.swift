import AVFoundation
import Foundation

/// 同声传译的录音：几段录音拼成一条，可以回放、导出、按时间截取
enum InterpretAudio {
    /// 把一条记录的几段录音按顺序接起来
    static func composition(_ parts: [InterpretRecord.AudioPart]) async -> AVMutableComposition? {
        let composition = AVMutableComposition()
        guard let track = composition.addMutableTrack(withMediaType: .audio, preferredTrackID: kCMPersistentTrackID_Invalid) else { return nil }
        var cursor = CMTime.zero
        for part in parts {
            let asset = AVURLAsset(url: ConversationStore.mediaURL(part.name))
            guard let source = try? await asset.loadTracks(withMediaType: .audio).first,
                  let duration = try? await asset.load(.duration) else { continue }
            try? track.insertTimeRange(CMTimeRange(start: .zero, duration: duration), of: source, at: cursor)
            cursor = cursor + duration
        }
        return cursor > .zero ? composition : nil
    }

    /// 导出成一个 m4a 文件（可以只导出其中一段）
    static func export(_ parts: [InterpretRecord.AudioPart], range: ClosedRange<Double>? = nil, to url: URL) async throws {
        try? FileManager.default.removeItem(at: url)
        // 只有一段、又是整段导出时，直接复制
        if parts.count == 1, range == nil {
            try FileManager.default.copyItem(at: ConversationStore.mediaURL(parts[0].name), to: url)
            return
        }
        guard let composition = await composition(parts),
              let session = AVAssetExportSession(asset: composition, presetName: AVAssetExportPresetAppleM4A) else {
            throw AIError(message: "没有找到录音文件。")
        }
        if let range {
            session.timeRange = CMTimeRange(start: CMTime(seconds: range.lowerBound, preferredTimescale: 600),
                                            end: CMTime(seconds: range.upperBound, preferredTimescale: 600))
        }
        try await session.export(to: url, as: .m4a)
    }

    /// 临时文件夹里一个文件名，导出、上传用
    static func temporaryURL(_ name: String) -> URL {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("interpret", isDirectory: true)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory.appendingPathComponent(name)
    }
}

/// OpenAI 的语音转写接口。用用户自己的 key，从本机直接发给 OpenAI，不经过 Q-Translator 的服务器
enum SpeechTranscription {
    static let model = "gpt-4o-transcribe"
    /// 按 OpenAI 公布的价格估算（美元 / 分钟）
    static let pricePerMinute = 0.006

    struct Credentials {
        let apiKey: String
        let baseURL: String
    }

    @MainActor
    static var credentials: Credentials? {
        let key = AISettings.shared.openAIKey.trimmed
        guard !key.isEmpty else { return nil }
        let base = UserDefaults.standard.string(forKey: "ai.baseURL.\(AIProvider.openai.rawValue)") ?? AIProvider.openai.defaultBaseURL
        return Credentials(apiKey: key, baseURL: base.trimmed.isEmpty ? AIProvider.openai.defaultBaseURL : base.trimmed)
    }

    /// 转写一段录音。prompt 是前面一段的结尾，帮模型接上上下文；不指定语言，让模型自己判断中英文
    static func transcribe(_ file: URL, prompt: String, credentials: Credentials) async throws -> String {
        var base = credentials.baseURL
        while base.hasSuffix("/") { base.removeLast() }
        guard let url = URL(string: base + "/audio/transcriptions") else { throw AIError(message: "接口地址不正确。") }
        let boundary = "QTranslator-\(UUID().uuidString)"
        var body = Data()
        func field(_ name: String, _ value: String) {
            body.append(Data("--\(boundary)\r\nContent-Disposition: form-data; name=\"\(name)\"\r\n\r\n\(value)\r\n".utf8))
        }
        field("model", model)
        field("response_format", "json")
        if !prompt.isEmpty { field("prompt", prompt) }
        body.append(Data("--\(boundary)\r\nContent-Disposition: form-data; name=\"file\"; filename=\"audio.m4a\"\r\nContent-Type: audio/mp4\r\n\r\n".utf8))
        body.append(try Data(contentsOf: file))
        body.append(Data("\r\n--\(boundary)--\r\n".utf8))

        var request = URLRequest(url: url, timeoutInterval: 300)
        request.httpMethod = "POST"
        request.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
        request.setValue("Bearer \(credentials.apiKey)", forHTTPHeaderField: "Authorization")
        let data: Data, response: URLResponse
        do {
            (data, response) = try await URLSession.shared.upload(for: request, from: body)
        } catch {
            if Task.isCancelled { throw CancellationError() }
            throw AIError(message: "连不上 OpenAI，请检查网络。")
        }
        let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard status == 200 else {
            let detail = (json["error"] as? [String: Any])?["message"] as? String
            switch status {
            case 401, 403: throw AIError(message: "OpenAI 的 API Key 无效或没有权限。")
            case 429: throw AIError(message: "OpenAI 请求太频繁或额度用完了，稍后再试。")
            default: throw AIError(message: detail ?? "OpenAI 语音转写出错（\(status)）。")
            }
        }
        return (json["text"] as? String ?? "").trimmed
    }
}

/// AI 精校：把一条传译的录音切成几分钟一段，交给 OpenAI 重新识别，再整段交给 AI 分句、翻译。
/// 结果另存为“精校版”，实时字幕保留，可以切换对比。离开页面也继续做
@MainActor
final class TranscriptRefiner: ObservableObject {
    static let shared = TranscriptRefiner()

    struct Progress: Equatable {
        var done: Int
        var total: Int
        var message: String
    }

    @Published private(set) var progress: [UUID: Progress] = [:]
    @Published private(set) var errors: [UUID: String] = [:]
    private var tasks: [UUID: Task<Void, Never>] = [:]

    /// 每段最长几秒。太长的话转写结果会被截断
    private static let chunkLength = 240.0

    func isRunning(_ id: UUID) -> Bool { tasks[id] != nil }

    func clearError(_ id: UUID) { errors[id] = nil }

    func cancel(_ id: UUID) {
        tasks[id]?.cancel()
    }

    func refine(_ record: InterpretRecord) {
        guard tasks[record.id] == nil, let parts = record.audio, !parts.isEmpty else { return }
        guard let credentials = SpeechTranscription.credentials else {
            errors[record.id] = "需要 OpenAI 的 API Key 才能精校。"
            return
        }
        let config = Self.translationConfig(credentials)
        let id = record.id
        let chunks = Self.chunks(record)
        errors[id] = nil
        progress[id] = Progress(done: 0, total: chunks.count * 2, message: "准备录音…")
        let started = Date()
        tasks[id] = Task {
            defer {
                tasks[id] = nil
                progress[id] = nil
            }
            var lines: [TranscriptLine] = []
            var previousText = ""
            do {
                for (index, chunk) in chunks.enumerated() {
                    try Task.checkCancellation()
                    progress[id] = Progress(done: index * 2, total: chunks.count * 2,
                                            message: "正在重新识别第 \(index + 1)/\(chunks.count) 段…")
                    let file = InterpretAudio.temporaryURL("chunk-\(id.uuidString)-\(index).m4a")
                    try await InterpretAudio.export(parts, range: chunk, to: file)
                    defer { try? FileManager.default.removeItem(at: file) }
                    let prompt = "The speech may switch between Mandarin Chinese and English. " + String(previousText.suffix(200))
                    let text = try await SpeechTranscription.transcribe(file, prompt: prompt, credentials: credentials)
                    try Task.checkCancellation()
                    guard !text.isEmpty else { continue }
                    progress[id] = Progress(done: index * 2 + 1, total: chunks.count * 2,
                                            message: "正在翻译第 \(index + 1)/\(chunks.count) 段…")
                    let context = lines.suffix(3).map(\.original)
                    let pairs = try await AITasks.translateTranscriptChunk(text, context: context, config: config)
                    lines += Self.timed(pairs, in: chunk)
                    previousText = text
                }
                guard !lines.isEmpty else { throw AIError(message: "录音里没有识别到说话的内容。") }
                ModuleStore.shared.updateInterpretation(id) {
                    $0.refined = lines
                    $0.refinedAt = Date()
                    $0.showsRefined = true
                }
                Analytics.track(.interpretRefine, ["result": "ok", "minutes": Analytics.bucket(Int((chunks.last?.upperBound ?? 0) / 60), [5, 15, 30, 60]),
                                                   "seconds": Analytics.bucket(Int(Date().timeIntervalSince(started)), [30, 60, 180, 600])])
            } catch is CancellationError {
                Analytics.track(.interpretRefine, ["result": "cancel"])
            } catch {
                errors[id] = error.localizedDescription
                Analytics.track(.interpretRefine, ["result": "error"])
            }
        }
    }

    /// 翻译用的 AI：用户配好的服务商；没配的话用同一个 OpenAI Key
    private static func translationConfig(_ credentials: SpeechTranscription.Credentials) -> AIClient.Config {
        if AISettings.shared.isConfigured { return AIClient.currentConfig }
        let model = UserDefaults.standard.string(forKey: "ai.model.\(AIProvider.openai.rawValue)") ?? AIProvider.openai.defaultModel
        return AIClient.Config(provider: .openai, apiKey: credentials.apiKey,
                               model: model.trimmed.isEmpty ? AIProvider.openai.defaultModel : model.trimmed,
                               baseURL: credentials.baseURL)
    }

    /// 切段：尽量切在实时字幕的两句话之间，每段不超过 4 分钟
    private static func chunks(_ record: InterpretRecord) -> [ClosedRange<Double>] {
        let total = record.audioDuration
        guard total > 0 else { return [] }
        var cuts: [Double] = []
        var start = 0.0
        let ends = record.lines.compactMap(\.end).sorted()
        while total - start > chunkLength {
            // 这一段里最靠后的一个句末（至少 1 分钟），没有就硬切
            let limit = start + chunkLength
            let cut = ends.last { $0 > start + 60 && $0 <= limit } ?? limit
            cuts.append(cut)
            start = cut
        }
        var result: [ClosedRange<Double>] = []
        var lower = 0.0
        for cut in cuts + [total] {
            result.append(lower...cut)
            lower = cut
        }
        return result
    }

    /// 一段里的每句按字数分摊这段的时间，点一句可以从大概的位置开始播放
    private static func timed(_ pairs: [(String, String)], in chunk: ClosedRange<Double>) -> [TranscriptLine] {
        let total = Double(max(pairs.reduce(0) { $0 + $1.0.count }, 1))
        var cursor = chunk.lowerBound
        let length = chunk.upperBound - chunk.lowerBound
        return pairs.map { original, translation in
            let start = cursor
            cursor += length * Double(original.count) / total
            return TranscriptLine(original: original, translation: translation, start: start, end: cursor)
        }
    }
}
