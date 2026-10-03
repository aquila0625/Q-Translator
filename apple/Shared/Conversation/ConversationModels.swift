import Foundation

/// 场景的封面：一个 SF Symbol 图标加一组配色
struct SceneCover: Codable, Hashable {
    var symbol: String
    var palette: Int

    static let symbols = ["book.closed.fill", "graduationcap.fill", "sun.max.fill", "leaf.fill",
                          "house.fill", "airplane", "fork.knife", "cart.fill",
                          "briefcase.fill", "cross.case.fill", "bubble.left.and.bubble.right.fill", "star.fill"]
}

/// 场景：会话的第一层分组，例如“教室”“户外交流”
struct SceneGroup: Codable, Identifiable, Hashable {
    var id = UUID()
    var name: String
    var cover: SceneCover
}

/// 图片里的一段文字和它在图里的位置（0…1，左上角为原点），译文覆盖在原来的位置上
struct ImageBlock: Codable, Equatable, Identifiable {
    var id = UUID()
    var text: String
    var translation = ""
    var x: Double
    var y: Double
    var width: Double
    var height: Double
    /// 原文有几行，用来估算覆盖译文的字号
    var lines: Int
    /// 原文周围的底色（RGB），译文用同样的底色盖住原文
    var background: UInt32?
}

/// 一张图片：本机文件名、识别出的文字和它的译文
struct TurnImage: Codable, Identifiable, Equatable {
    var id = UUID()
    var fileName: String
    var recognized = ""
    var translation = ""
    var done = false
    /// 按段识别的文字和位置；旧版本保存的图片没有
    var blocks: [ImageBlock]?
    /// 识别出错，或者有几段没翻译成功：显示“重新识别”
    var failed: Bool?
}

enum TurnState: String, Codable {
    case working, done, failed
}

/// 面对面对话里的一句：谁说的、原话、译文
struct DialogLine: Codable, Identifiable, Equatable {
    var id = UUID()
    /// true 是我说的，false 是对方说的
    var isMine: Bool
    var original: String
    var translation: String
    var originalIsChinese: Bool
    var createdAt = Date()
}

/// 场景练习里的一句：AI 扮演的角色说的，或者我说的（带 AI 给的更地道说法）
struct PracticeLine: Codable, Identifiable, Equatable {
    var id = UUID()
    var isMine: Bool
    var text: String
    /// 中文意思（AI 说的话才有）
    var chinese: String?
    /// 我说的话：更地道的说法和原因；说得好就没有
    var better: String?
    var reason: String?
}

/// 一次场景练习
struct PracticeRecord: Codable, Equatable {
    /// 场景，例如“租房：你是租客，AI 是房东”
    var scenario: String
    /// AI 扮演的角色，例如“房东”
    var role: String
    /// 难度：0 初级，1 中级，2 高级
    var level: Int
    var lines: [PracticeLine] = []
    /// 练习里学到的新说法（英文 + 中文）
    var phrases: [Phrase] = []
}

/// 同声传译记录里的一句
struct TranscriptLine: Codable, Identifiable, Equatable {
    var id = UUID()
    var original: String
    var translation: String
}

/// 会话里的一轮：一次输入（文字或几张图片）和它的翻译结果
struct Turn: Codable, Identifiable {
    var id = UUID()
    var source: String
    var images: [TurnImage] = []
    var sourceIsChinese: Bool
    /// 用户手动指定了方向；否则按内容自动识别
    var manualDirection = false
    var edited = false
    var createdAt = Date()
    var state: TurnState = .working
    var word: WordEntry?
    var sentence: SentenceResult?
    var errorMessage: String?
    var aiError: String?
    var isOptimizing = false
    /// 发图片时附带的要求（开着 AI 时才有），例如“只翻译菜名”
    var instruction: String?
    /// 语音输入时录下的原声（文件名）和时长
    var audioFile: String?
    var audioDuration: Double?
    /// 面对面对话：整段对话作为一轮保存
    var dialog: [DialogLine]?
    /// 场景练习：整段练习作为一轮保存
    var practice: PracticeRecord?
    /// 同声传译：整段字幕作为一轮保存，以及收音时长（秒）
    var transcript: [TranscriptLine]?
    var transcriptDuration: Double?

    var isImage: Bool { !images.isEmpty }

    /// 原文目录里显示的那一行
    var outlineText: String {
        if let dialog { return "面对面对话 · \(dialog.count) 句：" + (dialog.first?.original ?? "") }
        if let transcript { return "同声传译 · \(transcript.count) 句：" + (transcript.first?.original ?? "") }
        if let practice { return "场景练习 · \(practice.role)：" + (practice.lines.first?.text ?? "") }
        if isImage {
            let parts = images.map { String($0.recognized.prefix(24)) }.filter { !$0.isEmpty }
            return "\(images.count) 张图片" + (parts.isEmpty ? "" : "：" + parts.joined(separator: " / "))
        }
        return source
    }

    /// 搜索时匹配的全部文字
    var searchableText: String {
        [source, sentence?.translation ?? "", word?.summary ?? ""].joined(separator: "\n")
            + images.map { $0.recognized + "\n" + $0.translation }.joined(separator: "\n")
            + (dialog ?? []).map { $0.original + "\n" + $0.translation }.joined(separator: "\n")
            + (transcript ?? []).map { $0.original + "\n" + $0.translation }.joined(separator: "\n")
            + (practice?.lines ?? []).map { $0.text + "\n" + ($0.chinese ?? "") + ($0.better ?? "") }.joined(separator: "\n")
    }
}

/// 一个翻译会话
struct ChatSession: Codable, Identifiable {
    var id = UUID()
    var title: String
    /// 还没有被用户命名过：第一轮翻译后用原文开头当名称
    var autoTitled = true
    var sceneID: UUID?
    var aiEnabled: Bool
    var createdAt = Date()
    var updatedAt = Date()
    var turns: [Turn] = []

    static let defaultTitle = "新会话"

    var lastSnippet: String {
        guard let last = turns.last else { return "还没有内容" }
        return last.isImage ? "\(last.images.count) 张图片" : last.source
    }
}
