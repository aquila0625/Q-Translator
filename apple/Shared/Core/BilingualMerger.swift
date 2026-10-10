import Foundation
import Speech

/// 中英自动识别：中文和英文两个识别器同时听同一段声音，合成一条字幕。
///
/// 中文识别器什么都能听到（英文也会写出来，但常拼错），所以按它来切句：句末标点处切；
/// 定稿的结果带每个字的时间，中英文交界处如果停顿了一下（换人说话），也切开。切出来的句子：
/// - 主要是汉字的，就是中文句子，直接用；
/// - 主要是英文的，到英文识别器同一时间段里找文字最像的那几个词，用它的拼写
///   （英文识别器听到中文时写出来的东西和这句不像、把握度也低，不会被选上）。
/// 没定稿的结果不带每个字的时间，只知道听到了哪里，所以句子的时间按“上一句结束到现在”估计。
/// 时间都是相对识别开始的秒数。
struct BilingualMerger {
    struct Sentence: Equatable {
        var text: String
        var chinese: Bool
        var start: Double
        var end: Double
    }

    /// 带时间的一小段文字（识别结果里的一个 run，通常是一个词）
    private struct Piece {
        var text: String
        var start: Double
        var end: Double
        var confidence: Double
    }

    /// 等英文识别器结果的英文句子
    private struct Pending {
        var sentence: Sentence
        var deadline: Double
        /// 因为太长被强制切开的（句子还没说完）：找英文时不往后补到整句
        var forced: Bool
        /// 已经是英文识别器的结果，不用再找
        var resolved = false
    }

    // 中文识别器当前这一段（没定稿前会反复修改）
    private var zhPieces: [Piece] = []
    private var zhEmittedLetters = 0
    /// 已经切出去的最后几个字：识别器事后改了前面的字（比如英文拼写），按字数找切点会错位，用它按内容找回来
    private var zhAnchor = ""
    // 英文识别器已定稿的词，以及正在说的那句
    private var enWords: [Piece] = []
    private var enFinalEnd = 0.0
    private var enVolatile = ""
    private var queue: [Pending] = []
    /// 已经听到的最晚时间
    private(set) var clock = 0.0
    /// 上一句的结束时间
    private var lastEnd = 0.0
    /// 上一句的文字（只有字母、数字和汉字）
    private var lastLetters = ""
    /// 中文识别器这一个结果听到了哪里
    private var zhResultEnd = 0.0
    /// 中文识别器的文字最后一次变化的时间
    private var zhChangedAt = 0.0
    private var zhText = ""

    /// 还没说完的那句（显示用）
    private(set) var live = ""
    private(set) var liveChinese = true

    // MARK: 输入

    /// 中文识别器的一个结果。返回可以显示的新句子
    mutating func addChinese(_ text: AttributedString, isFinal: Bool, end: Double) -> [Sentence] {
        zhPieces = Self.pieces(text)
        zhResultEnd = end
        let plain = String(text.characters)
        if plain != zhText {
            zhText = plain
            zhChangedAt = end
        }
        clock = max(clock, end)
        cut(isFinal: isFinal)
        if isFinal {
            zhPieces = []
            zhEmittedLetters = 0
            zhAnchor = ""
        }
        updateLive()
        return release()
    }

    /// 英文识别器的一个结果
    mutating func addEnglish(_ text: AttributedString, isFinal: Bool, end: Double) -> [Sentence] {
        if isFinal {
            enWords += Self.pieces(text).filter { $0.text.contains(where: \.isLetter) || !enWords.isEmpty }
            enFinalEnd = max(enFinalEnd, end)
            enVolatile = ""
            // 很早以前的词用不上了
            enWords.removeAll { $0.end < lastEnd - 3 }
            if enWords.count > 400 { enWords.removeFirst(enWords.count - 400) }
            alignEnglish()
        } else {
            enVolatile = String(text.characters).trimmingCharacters(in: .whitespaces)
        }
        clock = max(clock, end)
        updateLive()
        return release()
    }

    /// 时间往前走（定时调用）：等太久的英文句子就用中文识别器的写法
    mutating func advance(to time: Double) -> [Sentence] {
        clock = max(clock, time)
        // 停顿了一会儿：最后一句已经有句号的话，不用等后面的话就先放出来
        // 停了 2 秒以上：没有句号也先放出来
        if clock - zhChangedAt >= 2 {
            cut(isFinal: true)
            updateLive()
        } else if clock - zhChangedAt >= 1.2, zhText.trimmingCharacters(in: .whitespaces).last.map({ "。！？!?.".contains($0) }) == true {
            cut(isFinal: false, flushEnding: true)
            updateLive()
        }
        return release()
    }

    /// 结束：没说完的也收进来
    mutating func finish() -> [Sentence] {
        cut(isFinal: true)
        zhPieces = []
        zhEmittedLetters = 0
        zhAnchor = ""
        live = ""
        clock = .infinity
        return release()
    }

    // MARK: 切句

    private mutating func cut(isFinal: Bool, flushEnding: Bool = false) {
        // 按字符展开，记下每个字的时间
        var chars: [Character] = []
        var times: [(Double, Double)] = []
        for piece in zhPieces {
            for ch in piece.text {
                chars.append(ch)
                // 没定稿的结果不知道每个字的时间
                times.append(isFinal ? (piece.start, piece.end) : (-1, -1))
            }
        }
        let begin = boundary(in: chars)
        var start = begin
        var i = begin
        while i < chars.count {
            let ch = chars[i]
            let next = i + 1 < chars.count ? chars[i + 1] : nil
            var cutAfter = false
            if "。！？!?".contains(ch) || (ch == "." && !(i > 0 && chars[i - 1].isNumber && (next?.isNumber ?? false))) {
                cutAfter = next != nil || isFinal || flushEnding
            } else if "，,；;".contains(ch), next != nil {
                let length = i + 1 - start
                cutAfter = length >= (Self.isCJK(chars[start..<i]) ? 14 : 60)
            } else if isFinal, let next, Self.isLetter(ch), Self.isLetter(next), Self.isChinese(ch) != Self.isChinese(next),
                      times[i + 1].0 - times[i].1 >= 0.35 {
                // 中英文交界处有停顿：多半是换了一个人说话
                cutAfter = true
            }
            if cutAfter {
                emit(chars[start...i], times: times[start...i])
                start = i + 1
            }
            i += 1
        }
        if isFinal, start < chars.count {
            emit(chars[start...], times: times[start...])
            start = chars.count
        } else if !isFinal, start < chars.count {
            // 一直在说、没有标点：太长了就先切出去一段，只留最后几个字（它们可能还会变）
            let rest = chars[start...]
            let chinese = Self.mostlyChinese(String(rest))
            let letters = Self.letterCount(rest)
            let words = String(rest).split(whereSeparator: \.isWhitespace).count
            if chinese ? letters >= 28 : words >= 30 {
                var keep = chinese ? 4 : 0
                var index = chars.count
                if chinese {
                    while index > start, keep > 0 {
                        index -= 1
                        if Self.isLetter(chars[index]) { keep -= 1 }
                    }
                } else {
                    // 英文留最后三个词
                    var spaces = 0
                    while index > start, spaces < 3 {
                        index -= 1
                        if chars[index].isWhitespace { spaces += 1 }
                    }
                }
                if index > start {
                    emit(chars[start..<index], times: times[start..<index], forced: true)
                    start = index
                }
            }
        }
        zhEmittedLetters += Self.letterCount(chars[begin..<start])
        if start > begin {
            let letters = String(chars[..<start].filter(Self.isLetter))
            zhAnchor = String(letters.suffix(6))
        }
    }

    /// 已经切出去的部分在这一版文字里到哪里：先按字数找，再在附近按最后几个字对一下
    private mutating func boundary(in chars: [Character]) -> Int {
        guard zhEmittedLetters > 0 else { return 0 }
        if !zhAnchor.isEmpty {
            let anchor = Array(zhAnchor)
            var letters: [Character] = []
            var positions: [Int] = []
            for (i, ch) in chars.enumerated() where Self.isLetter(ch) {
                letters.append(ch)
                positions.append(i)
            }
            var best: Int?
            for end in anchor.count...max(anchor.count, letters.count) where end <= letters.count {
                guard abs(end - zhEmittedLetters) <= 10, Array(letters[(end - anchor.count)..<end]) == anchor else { continue }
                if best.map({ abs(end - zhEmittedLetters) < abs($0 - zhEmittedLetters) }) ?? true { best = end }
            }
            if let best {
                zhEmittedLetters = best
                var i = positions[best - 1] + 1
                while i < chars.count, !Self.isLetter(chars[i]) { i += 1 }
                return i
            }
        }
        return Self.index(afterLetters: zhEmittedLetters, in: chars)
    }

    private mutating func emit(_ chars: ArraySlice<Character>, times: ArraySlice<(Double, Double)>, forced: Bool = false) {
        let text = String(chars).trimmingCharacters(in: .whitespacesAndNewlines)
        guard Self.letterCount(chars) > 0 else { return }
        // 识别器事后改了前面已经放出去的句子（多补了一两个字），剩下的尾巴和上一句的结尾一样，不要重复
        let letters = String(chars.filter(Self.isLetter))
        if letters.count <= 3, lastLetters.hasSuffix(letters) { return }
        lastLetters = letters
        // 只看文字的时间（标点的时间不准）；不知道时间就按上一句结束到现在
        let known = zip(chars, times).filter { Self.isLetter($0.0) && $0.1.0 >= 0 }.map(\.1)
        let start = known.map(\.0).min() ?? lastEnd
        let end = known.map(\.1).max() ?? max(zhResultEnd, start)
        lastEnd = max(lastEnd, end)
        let chinese = Self.mostlyChinese(text)
        let sentence = Sentence(text: text, chinese: chinese, start: start, end: end)
        // 英文句子先等一下英文识别器（它说完一句很快就定稿）
        queue.append(Pending(sentence: sentence, deadline: chinese ? 0 : sentence.end + 2.5, forced: forced))
    }

    /// 按顺序放出已经确定的句子；排在前面的英文句子还在等，后面的也等着
    private mutating func release() -> [Sentence] {
        var out: [Sentence] = []
        while let head = queue.first {
            var sentence = head.sentence
            if !sentence.chinese, !head.resolved {
                let ready = enFinalEnd >= sentence.end - 0.15
                guard ready || clock >= head.deadline else { break }
                if let better = englishMatch(sentence, completeSentence: !head.forced) {
                    sentence.text = better.text
                    sentence.start = better.start
                    sentence.end = better.end
                }
            }
            queue.removeFirst()
            out.append(sentence)
        }
        return out
    }

    /// 正在说英文时，英文识别器每定稿一句（它断句准、拼写准）就直接放出这句，
    /// 再在中文识别器的文字里找到对应的那一段，把切点挪过去，不用等中文识别器加标点
    private mutating func alignEnglish() {
        let chars = zhPieces.flatMap { Array($0.text) }
        var begin = boundary(in: chars)
        func endsSentence(_ i: Int) -> Bool { enWords[i].text.trimmingCharacters(in: .whitespaces).last.map { ".?!".contains($0) } ?? false }
        while begin < chars.count, let last = enWords.indices.first(where: endsSentence) {
            let rest = String(chars[begin...])
            guard !Self.mostlyChinese(rest), Self.letterCount(chars[begin...]) > 0 else { return }
            let words = Array(enWords[0...last])
            let letters = words.reduce(0) { $0 + $1.text.filter(\.isLetter).count }
            let confidence = words.reduce(0) { $0 + $1.confidence * Double($1.text.filter(\.isLetter).count) } / Double(max(letters, 1))
            let text = words.map(\.text).joined().trimmingCharacters(in: .whitespacesAndNewlines)
            guard confidence >= 0.6, letters > 0 else { return }
            // 中文识别器的文字里，按词往后数，找和这句最像的开头一段
            var best: (score: Double, end: Int)?
            var i = begin
            while i < chars.count {
                while i < chars.count, !chars[i].isWhitespace, !Self.isChinese(chars[i]) { i += 1 }
                let score = Self.similarity(String(chars[begin..<i]), text)
                if score > (best?.score ?? 0) { best = (score, i) }
                if i < chars.count, Self.isChinese(chars[i]) { break }
                i += 1
            }
            guard let best, best.score >= 0.4 else { return }
            var end = best.end
            while end < chars.count, !Self.isLetter(chars[end]), !Self.isChinese(chars[end]) { end += 1 }
            let sentence = Sentence(text: text, chinese: false, start: words.first!.start, end: words.last!.end)
            queue.append(Pending(sentence: sentence, deadline: 0, forced: false, resolved: true))
            lastEnd = max(lastEnd, sentence.end)
            lastLetters = String(text.filter(Self.isLetter))
            enWords.removeFirst(last + 1)
            zhEmittedLetters += Self.letterCount(chars[begin..<end])
            zhAnchor = String(String(chars[..<end].filter(Self.isLetter)).suffix(6))
            begin = end
        }
    }

    /// 英文识别器在这段时间附近听到的、和这句最像的连续几个词
    private mutating func englishMatch(_ sentence: Sentence, completeSentence: Bool) -> (text: String, start: Double, end: Double)? {
        let candidates = enWords.indices.filter { enWords[$0].end > sentence.start - 1 && enWords[$0].start < sentence.end + 1 }
        guard let first = candidates.first else { return nil }
        var best: (score: Double, from: Int, to: Int)?
        // 从哪个词开始、到哪个词结束，挑和这句文字最像的一段
        for from in candidates {
            var text = ""
            for to in candidates where to >= from {
                text += enWords[to].text
                let score = Self.similarity(text, sentence.text)
                if score > (best?.score ?? 0) { best = (score, from, to) }
            }
        }
        guard let best, best.score >= 0.35 else { return nil }
        // 补齐到英文识别器自己的整句：前后漏掉的一两个词（比如句首的 Okay、句尾的 ready）
        var from = best.from, to = best.to
        func endsSentence(_ i: Int) -> Bool { enWords[i].text.trimmingCharacters(in: .whitespaces).last.map { ".?!".contains($0) } ?? false }
        while from > first, !endsSentence(from - 1), enWords[from - 1].start > sentence.start - 1.5 { from -= 1 }
        while completeSentence, to + 1 < enWords.count, !endsSentence(to), enWords[to + 1].start < sentence.end + 1.5 { to += 1 }
        let words = Array(enWords[from...to])
        let letters = words.reduce(0) { $0 + $1.text.filter(\.isLetter).count }
        let confidence = words.reduce(0) { $0 + $1.confidence * Double($1.text.filter(\.isLetter).count) } / Double(max(letters, 1))
        guard confidence >= 0.5 else { return nil }
        // 这几个词和它们前面的都用过了
        enWords.removeSubrange(first...to)
        let text = words.map(\.text).joined().trimmingCharacters(in: .whitespacesAndNewlines)
        return (text, words.first!.start, words.last!.end)
    }

    /// 两段文字有多像：只看字母，按相邻两个字母的组合算重合比例
    private static func similarity(_ a: String, _ b: String) -> Double {
        func grams(_ text: String) -> Set<String> {
            let letters = Array(text.lowercased().filter { $0.isLetter || $0.isNumber })
            guard letters.count > 1 else { return Set(letters.map { String($0) }) }
            return Set((0..<letters.count - 1).map { String(letters[$0...$0 + 1]) })
        }
        let x = grams(a), y = grams(b)
        guard !x.isEmpty, !y.isEmpty else { return 0 }
        return Double(x.intersection(y).count) / Double(x.union(y).count)
    }

    private mutating func updateLive() {
        let chars = zhPieces.flatMap { Array($0.text) }
        let begin = boundary(in: chars)
        let rest = String(chars[begin...]).trimmingCharacters(in: .whitespacesAndNewlines.union(.punctuationCharacters))
        liveChinese = Self.mostlyChinese(rest)
        // 正在说英文时，显示英文识别器的写法（拼写更准）
        live = !liveChinese && !enVolatile.isEmpty ? enVolatile : rest
    }

    // MARK: 工具

    private static func pieces(_ text: AttributedString) -> [Piece] {
        text.runs.compactMap { run in
            let string = String(text[run.range].characters)
            guard let range = run.audioTimeRange else { return nil }
            return Piece(text: string, start: range.start.seconds, end: (range.start + range.duration).seconds,
                         confidence: run.transcriptionConfidence ?? 1)
        }
    }

    private static func isLetter(_ ch: Character) -> Bool { ch.isLetter || ch.isNumber }

    private static func isChinese(_ ch: Character) -> Bool {
        ch.unicodeScalars.contains { (0x3400...0x9FFF).contains($0.value) }
    }

    private static func isCJK(_ chars: ArraySlice<Character>) -> Bool {
        chars.contains(where: isChinese)
    }

    private static func letterCount(_ chars: ArraySlice<Character>) -> Int { chars.filter(isLetter).count }

    /// 汉字数不少于英文词数的两倍，就算中文（中文里夹几个英文术语还是中文）
    static func mostlyChinese(_ text: String) -> Bool {
        var chinese = 0, words = 0, inWord = false
        for ch in text {
            if isChinese(ch) {
                chinese += 1
                inWord = false
            } else if ch.isLetter {
                if !inWord { words += 1 }
                inWord = true
            } else if ch != "'" && ch != "-" {
                inWord = false
            }
        }
        return chinese > 0 && chinese >= words * 2
    }

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
}
