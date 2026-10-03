import Foundation

struct Phonetic: Identifiable, Codable {
    var id = UUID()
    let label: String      // 英 / 美
    let ipa: String
    let accent: Int        // 有道发音参数：1 英音，2 美音
}

/// 按词性汇总的一行释义，例如 “v. 充电；控告；收费”
struct Definition: Identifiable, Codable {
    var id = UUID()
    let text: String
    let note: String?
}

struct ExamplePair: Identifiable, Codable {
    var id = UUID()
    let source: String        // 可能带 <b> 高亮
    let translation: String
    let english: String       // 用于朗读的英文原句
}

/// 单个义项：一个意思配它自己的词性和例句
struct Sense: Identifiable, Codable {
    var id = UUID()
    let pos: String?
    let meaning: String
    /// 词典把这个义项标为考试常见义，用来把常用义排在前面
    let isCommon: Bool
    let examples: [ExamplePair]
}

struct CollinsSense: Identifiable, Codable {
    var id = UUID()
    let pos: String?
    let explanation: String   // 英文解释 + 中文释义，可能带 <b>
    let examples: [ExamplePair]
}

struct Phrase: Identifiable, Codable, Equatable {
    var id = UUID()
    let key: String
    let value: String
}

struct RelatedWord: Identifiable, Codable {
    var id = UUID()
    let pos: String
    let word: String
    let meaning: String
}

/// 近义词辨析：一组容易混淆的词和各自的用法
struct Distinction: Identifiable, Codable {
    var id = UUID()
    let title: String
    let usages: [Phrase]
}

struct Suggestion: Identifiable, Codable {
    var id = UUID()
    let word: String
    let meaning: String
}

struct WordEntry: Codable {
    var word: String
    var isChinese: Bool
    var phonetics: [Phonetic] = []
    var pinyin: String?
    var tags: [String] = []
    var definitions: [Definition] = []
    var senses: [Sense] = []
    var forms: [String] = []
    var collins: [CollinsSense] = []
    var examples: [ExamplePair] = []
    var webMeanings: [String] = []
    var phrases: [Phrase] = []
    var related: [RelatedWord] = []
    var distinctions: [Distinction] = []
    var etymology: String?

    var hasContent: Bool {
        !definitions.isEmpty || !senses.isEmpty || !collins.isEmpty || !webMeanings.isEmpty
    }

    /// 历史记录里显示的一行摘要
    var summary: String {
        definitions.first?.text ?? senses.first?.meaning ?? webMeanings.first ?? ""
    }
}

struct SentenceResult: Codable {
    let source: String
    var translation: String
    let sourceIsChinese: Bool
    var engine: String
    var suggestions: [Suggestion] = []
    /// AI 校准后为 true；校准前的机器翻译保存在 machineTranslation（没有改动时为 nil）
    var calibrated = false
    var machineTranslation: String?
    /// AI 优化这一次消耗的 token 数
    var aiUsage: AIUsage?

    // 会话里的 AI 优化：translation 始终是机器翻译，AI 的结果单独保存，可以随时开关、不用重新请求
    var aiTranslation: String?
    var aiShown: Bool?
    /// 优化时用的服务商和模型，例如 “ChatGPT · gpt-6-luna”
    var aiModel: String?

    var showsAI: Bool { aiShown == true && aiTranslation != nil }
    /// 当前显示的译文
    var displayed: String { showsAI ? aiTranslation ?? translation : translation }
}

enum Phase {
    case idle
    case loading
    case word(WordEntry)
    case sentence(SentenceResult)
    case failed(String)
}

extension String {
    var trimmed: String { trimmingCharacters(in: .whitespacesAndNewlines) }

    var containsChinese: Bool {
        unicodeScalars.contains { (0x4E00...0x9FFF).contains($0.value) || (0x3400...0x4DBF).contains($0.value) }
    }

    /// 中英混排时，哪种语言占比大就以哪种为原文。一个英文单词大约相当于两个汉字的信息量，
    /// 所以按“汉字数”对“英文单词数 × 2”来比。
    var isMostlyChinese: Bool {
        var chinese = 0, englishWords = 0, inWord = false
        for scalar in unicodeScalars {
            if (0x4E00...0x9FFF).contains(scalar.value) || (0x3400...0x4DBF).contains(scalar.value) {
                chinese += 1
                inWord = false
            } else if scalar.isASCII, scalar.properties.isAlphabetic {
                if !inWord { englishWords += 1 }
                inWord = true
            } else if scalar != "'" && scalar != "-" {
                inWord = false
            }
        }
        if chinese == 0 { return false }
        return chinese >= englishWords * 2
    }

    var strippingTags: String {
        replacingOccurrences(of: "<[^>]+>", with: "", options: .regularExpression)
    }
}
