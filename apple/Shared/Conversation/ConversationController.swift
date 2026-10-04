import SwiftUI

/// 会话页的大脑：发送、翻译、编辑原文、AI 优化、图片处理。
@MainActor
final class ConversationController: ObservableObject {
    let store = ConversationStore()
    let translator = SystemTranslator()

    @Published var currentID: UUID
    @Published var draft = "" {
        // 把输入框清空了：语音输入留下的原声也不要了
        didSet {
            if draft.trimmed.isEmpty, let audio = pendingAudio {
                ConversationStore.deleteMediaFile(audio.name)
                pendingAudio = nil
            }
        }
    }
    @Published var direction: Direction = .auto
    /// 系统离线翻译模型可以下载但还没下载
    @Published var offlineDownloadable = false
    /// 选好还没发出去的图片，显示在输入框上方，可以预览、排序、删除
    @Published var pendingImages: [PendingImage] = []
    /// 语音输入留下的原声，跟着下一次发送的文字一起保存；把输入框清空就丢掉
    @Published var pendingAudio: (name: String, duration: Double)?

    /// 待发图片最近一次的来源（统计用）
    private var pendingImageSource = "other"

    struct PendingImage: Identifiable {
        let id = UUID()
        let image: PlatformImage
    }

    init() {
        ModuleStore.shared.migrate(from: store)
        if let latest = store.sessions.max(by: { $0.updatedAt < $1.updatedAt }) {
            currentID = latest.id
        } else {
            currentID = store.createSession(title: nil, sceneID: nil, aiEnabled: AISettings.shared.autoCalibrate).id
        }
    }

    var current: ChatSession? { store.session(currentID) }

    // MARK: 会话

    func select(_ id: UUID) {
        ModuleRouter.shared.module = nil
        currentID = id
        direction = .auto
    }

    func newSession(title: String? = nil, sceneID: UUID? = nil, aiEnabled: Bool? = nil) {
        ModuleRouter.shared.module = nil
        // 当前会话还是空的、也没起名，就直接复用它（移到要的场景里）
        if title == nil, let current, current.turns.isEmpty, current.autoTitled {
            store.moveSession(current.id, toScene: sceneID, before: nil)
            return
        }
        let session = store.createSession(title: title, sceneID: sceneID, aiEnabled: aiEnabled ?? AISettings.shared.autoCalibrate)
        Analytics.track(.sessionNew, ["in_scene": sceneID == nil ? "no" : "yes"])
        select(session.id)
    }

    func deleteScene(_ id: UUID, deleteSessions: Bool) {
        let removesCurrent = deleteSessions && store.session(currentID)?.sceneID == id
        store.deleteScene(id, deleteSessions: deleteSessions)
        if removesCurrent {
            if let next = store.sessions.max(by: { $0.updatedAt < $1.updatedAt }) {
                select(next.id)
            } else {
                currentID = store.createSession(title: nil, sceneID: nil, aiEnabled: AISettings.shared.autoCalibrate).id
            }
        }
    }

    func deleteSession(_ id: UUID) {
        store.deleteSession(id)
        if currentID == id {
            if let next = store.sessions.max(by: { $0.updatedAt < $1.updatedAt }) {
                select(next.id)
            } else {
                currentID = store.createSession(title: nil, sceneID: nil, aiEnabled: AISettings.shared.autoCalibrate).id
            }
        }
    }

    func setAI(_ on: Bool) {
        store.updateSession(currentID) { $0.aiEnabled = on }
    }

    /// 方向按钮：自动 → 英译中 → 中译英 → 自动
    func cycleDirection() {
        switch direction {
        case .auto: direction = .englishToChinese
        case .englishToChinese: direction = .chineseToEnglish
        case .chineseToEnglish: direction = .auto
        }
    }

    private func sourceIsChinese(_ text: String) -> Bool {
        switch direction {
        case .auto: text.isMostlyChinese
        case .englishToChinese: false
        case .chineseToEnglish: true
        }
    }

    // MARK: 发送

    // MARK: 模块共用

    /// 按已知的语言翻译一句（面对面对话、同声传译用），先本机离线，不行再在线
    func translate(_ text: String, fromChinese: Bool) async -> String? {
        try? await translateSentence(text, chinese: fromChinese).0
    }

    // MARK: 语音输入

    /// 按翻译方向决定识别哪种语言；“自动”时用上次说的语言
    var voiceLanguage: VoiceInput.Language? {
        switch direction {
        case .auto: nil
        case .englishToChinese: .english
        case .chineseToEnglish: .chinese
        }
    }

    func startVoice() {
        Task { await VoiceInput.shared.start(preferred: voiceLanguage) }
    }

    /// 说完了：文字放进输入框（可以改），原声留着跟这次发送一起保存；设置了“说完自动翻译”就直接发出去
    func finishVoice() {
        let result = VoiceInput.shared.stop()
        guard !result.text.isEmpty else { return }
        Analytics.track(.voiceInput, ["language": VoiceInput.shared.language.rawValue,
                                      "auto_send": UserDefaults.standard.bool(forKey: SettingsKey.voiceAutoSend) ? "yes" : "no",
                                      "seconds": Analytics.bucket(Int(result.duration), [5, 15, 60])])
        if let old = pendingAudio, draft.trimmed.isEmpty { ConversationStore.deleteMediaFile(old.name) }
        draft = draft.trimmed.isEmpty ? result.text : draft.trimmed + " " + result.text
        if let audio = result.audio { pendingAudio = (audio, result.duration) }
        if UserDefaults.standard.bool(forKey: SettingsKey.voiceAutoSend) { send() }
    }

    func cancelVoice() {
        VoiceInput.shared.cancel()
    }

    /// 选好的图片先放在输入框上方，不直接发送
    /// source：图片从哪里来（camera / photos / files / paste / drop / other），只用于统计
    func attachImages(_ images: [PlatformImage], source: String = "other") {
        guard !images.isEmpty else { return }
        pendingImages += images.map { PendingImage(image: $0) }
        pendingImageSource = source
    }

    func removePending(_ id: UUID) {
        pendingImages.removeAll { $0.id == id }
    }

    /// 拖动排序：把一张图放到另一张前面
    func movePending(_ id: UUID, before target: UUID) {
        guard id != target, let from = pendingImages.firstIndex(where: { $0.id == id }) else { return }
        let item = pendingImages.remove(at: from)
        let to = pendingImages.firstIndex(where: { $0.id == target }) ?? pendingImages.count
        pendingImages.insert(item, at: to)
    }

    func send() {
        // 有待发的图片：发图片，输入框里的文字是给 AI 的要求（关着 AI 时不能输入）
        if !pendingImages.isEmpty {
            let instruction = current?.aiEnabled == true ? draft.trimmed : ""
            let images = pendingImages.map(\.image)
            pendingImages = []
            draft = ""
            Analytics.track(.imageTranslate, ["source": pendingImageSource, "count": Analytics.bucket(images.count, [1, 3, 6]),
                                              "ai": current?.aiEnabled == true ? "on" : "off",
                                              "instruction": instruction.isEmpty ? "no" : "yes"])
            sendImages(images, instruction: instruction.isEmpty ? nil : instruction)
            return
        }
        let text = draft.trimmed
        guard !text.isEmpty else { return }
        let audio = pendingAudio
        pendingAudio = nil
        draft = ""
        var turn = Turn(source: text, sourceIsChinese: sourceIsChinese(text), manualDirection: direction != .auto)
        let attributes = ["direction": Analytics.direction(fromChinese: turn.sourceIsChinese), "voice": audio == nil ? "no" : "yes",
                          "ai": current?.aiEnabled == true ? "on" : "off"]
        if Self.isWordLike(text) {
            Analytics.track(.wordLookup, attributes)
        } else {
            Analytics.track(.textTranslate, attributes.merging(["length": Analytics.bucket(text.count, [40, 200, 1000])]) { $1 })
        }
        turn.audioFile = audio?.name
        turn.audioDuration = audio?.duration
        let sessionID = currentID
        store.appendTurn(turn, to: sessionID)
        Task { await process(sessionID, turn.id) }
    }

    /// 一次发送多张图片，作为同一轮
    func sendImages(_ images: [PlatformImage], instruction: String? = nil) {
        let files = images.compactMap { ConversationStore.saveImage($0) }
        guard !files.isEmpty else { return }
        var turn = Turn(source: "", images: files.map { TurnImage(fileName: $0) }, sourceIsChinese: direction == .chineseToEnglish,
                        manualDirection: direction != .auto)
        turn.instruction = instruction
        let sessionID = currentID
        store.appendTurn(turn, to: sessionID)
        Task { await process(sessionID, turn.id) }
    }

    /// 把译文放到原文的位置再反向翻译一次，作为新的一轮
    func swap(_ turn: Turn) {
        guard let translation = turn.sentence?.displayed else { return }
        let new = Turn(source: translation, sourceIsChinese: !turn.sourceIsChinese, manualDirection: true)
        let sessionID = currentID
        store.appendTurn(new, to: sessionID)
        Task { await process(sessionID, new.id) }
    }

    func retry(_ turnID: UUID) {
        let sessionID = currentID
        store.updateTurn(sessionID, turnID) {
            $0.state = .working
            $0.errorMessage = nil
            for i in $0.images.indices { $0.images[i].done = false }
        }
        Task { await process(sessionID, turnID) }
    }

    /// 改了原文：只重新翻译这一轮
    func editSource(_ turnID: UUID, to text: String) {
        let text = text.trimmed
        guard !text.isEmpty, let old = store.turn(currentID, turnID), old.source != text else { return }
        let sessionID = currentID
        store.updateTurn(sessionID, turnID) {
            $0.source = text
            $0.edited = true
            if !$0.manualDirection { $0.sourceIsChinese = text.isMostlyChinese }
            $0.state = .working
            $0.word = nil
            $0.sentence = nil
            $0.errorMessage = nil
            $0.aiError = nil
        }
        Task { await process(sessionID, turnID) }
    }

    func deleteTurn(_ turnID: UUID) {
        store.deleteTurn(currentID, turnID)
    }

    /// 删掉一张图片，它的译文一起去掉；最后一张也删了就删除整轮
    func deleteImage(_ turnID: UUID, _ imageID: UUID) {
        guard let turn = store.turn(currentID, turnID), let image = turn.images.first(where: { $0.id == imageID }) else { return }
        if turn.images.count == 1 {
            deleteTurn(turnID)
            return
        }
        store.deleteImageFile(image.fileName)
        store.updateTurn(currentID, turnID) {
            $0.images.removeAll { $0.id == imageID }
            $0.source = $0.images.map(\.recognized).joined(separator: "\n")
        }
    }

    /// 只重新识别和翻译这一张图片（识别失败、没识别到文字或翻译不完整时用）
    func reprocessImage(_ turnID: UUID, _ imageID: UUID) {
        Analytics.track(.imageRecognizeAgain)
        let sessionID = currentID
        store.updateTurn(sessionID, turnID) {
            if let i = $0.images.firstIndex(where: { $0.id == imageID }) {
                $0.images[i].recognized = ""
                $0.images[i].translation = ""
                $0.images[i].blocks = nil
                $0.images[i].failed = nil
                $0.images[i].done = false
            }
            $0.errorMessage = nil
            $0.state = .working
        }
        Task { await process(sessionID, turnID) }
    }

    /// 把图片顺时针转 90°：译文跟着图片一起转，不用重新识别（转歪了的照片想按新方向重新识别，点“重新识别”）
    func rotateImage(_ turnID: UUID, _ imageID: UUID) {
        guard let turn = store.turn(currentID, turnID), let item = turn.images.first(where: { $0.id == imageID }), item.done,
              let image = store.image(named: item.fileName) else { return }
        Analytics.track(.imageRotate)
        store.replaceImageFile(item.fileName, with: image.rotatedClockwise())
        store.updateTurn(currentID, turnID) {
            guard let i = $0.images.firstIndex(where: { $0.id == imageID }) else { return }
            $0.images[i].blocks = $0.images[i].blocks?.map { $0.rotatedClockwise() }
        }
    }

    // MARK: 翻译

    private func process(_ sessionID: UUID, _ turnID: UUID) async {
        guard let turn = store.turn(sessionID, turnID) else { return }
        if turn.isImage {
            await processImages(sessionID, turn)
        } else {
            await processText(sessionID, turn)
        }
    }

    private func processText(_ sessionID: UUID, _ turn: Turn) async {
        let text = turn.source
        var suggestions: [Suggestion] = []

        if Self.isWordLike(text), let response = try? await Youdao.lookup(text) {
            if let entry = response.entry {
                store.updateTurn(sessionID, turn.id) {
                    $0.word = entry
                    $0.state = .done
                }
                HistoryStore.shared.add(entry.word, summary: entry.summary)
                autoTitle(sessionID, from: text)
                if UserDefaults.standard.bool(forKey: SettingsKey.autoSpeak) {
                    Speaker.shared.play(.text(entry.word, isChinese: entry.isChinese))
                }
                return
            }
            suggestions = response.suggestions
        }

        do {
            let (translation, engine) = try await translateSentence(text, chinese: turn.sourceIsChinese)
            store.updateTurn(sessionID, turn.id) {
                $0.sentence = SentenceResult(source: text, translation: translation, sourceIsChinese: turn.sourceIsChinese,
                                             engine: engine, suggestions: suggestions)
                $0.state = .done
            }
            autoTitle(sessionID, from: text)
            // 单词和短语不自动用 AI：它们查词典就够了，AI 优化只针对整句话
            if store.session(sessionID)?.aiEnabled == true, AISettings.shared.isConfigured, !Self.isWordLike(text) {
                await optimize(sessionID, turn.id)
            }
        } catch {
            store.updateTurn(sessionID, turn.id) {
                $0.state = .failed
                $0.errorMessage = "翻译失败，请检查网络后重试。"
            }
        }
    }

    /// 逐张识别，按段翻译，译文记下位置，显示时覆盖在原文上。开着 AI 时整张图的几段一起交给 AI，可以带用户的要求
    private func processImages(_ sessionID: UUID, _ turn: Turn) async {
        let useAI = store.session(sessionID)?.aiEnabled == true && AISettings.shared.isConfigured
        for item in turn.images where !item.done {
            guard let image = store.image(named: item.fileName) else { continue }
            var failed = false
            let found: [ImageText.Block]
            do {
                found = try await ImageText.recognizeBlocks(image)
            } catch {
                found = []
                failed = true
            }
            let recognized = found.map(\.text).joined(separator: "\n")
            let chinese = turn.manualDirection ? turn.sourceIsChinese : recognized.isMostlyChinese
            var translations = Array(repeating: "", count: found.count)
            if !found.isEmpty {
                if useAI, let result = try? await AITasks.translateImageBlocks(found.map(\.text), instruction: turn.instruction,
                                                                                 toChinese: !chinese, config: AIClient.currentConfig) {
                    translations = result.texts
                } else {
                    for (index, block) in found.enumerated() {
                        if let text = try? await translateSentence(block.text, chinese: chinese).0 {
                            translations[index] = text
                        } else {
                            failed = true
                        }
                    }
                }
            }
            let colors = ImageText.backgroundColors(image, rects: found.map(\.rect))
            let blocks = zip(zip(found, translations), colors).map { pair, color in
                let (block, translation) = pair
                return ImageBlock(text: block.text, translation: translation, x: block.rect.minX, y: block.rect.minY,
                                  width: block.rect.width, height: block.rect.height, lines: block.lines, background: color)
            }
            store.updateTurn(sessionID, turn.id) {
                if let i = $0.images.firstIndex(where: { $0.id == item.id }) {
                    $0.images[i].recognized = recognized
                    $0.images[i].blocks = blocks
                    $0.images[i].translation = found.isEmpty ? "（这张图片里没有识别到文字）"
                        : translations.filter { !$0.isEmpty }.joined(separator: "\n")
                    $0.images[i].failed = failed || translations.allSatisfy(\.isEmpty) && !found.isEmpty ? true : nil
                    $0.images[i].done = true
                }
            }
        }
        store.updateTurn(sessionID, turn.id) {
            $0.source = $0.images.map(\.recognized).joined(separator: "\n")
            $0.state = .done
        }
        autoTitle(sessionID, from: turn.instruction ?? "图片翻译")
    }

    /// 先用系统离线翻译，不行再用在线翻译
    private func translateSentence(_ text: String, chinese: Bool) async throws -> (String, String) {
        let status = await translator.status(chinese: chinese)
        if status == .installed, let result = try? await translator.translate(text, chinese: chinese) {
            return (result, "系统离线翻译")
        }
        offlineDownloadable = status == .supported
        return (try await OnlineTranslator.translate(text, fromChinese: chinese), OnlineTranslator.name)
    }

    func downloadOfflineModel() {
        translator.prepare(chinese: false)
        offlineDownloadable = false
    }

    // MARK: AI 优化

    /// 点“AI 优化”：打开时有之前的结果就直接用，没有才请求；再点一次关掉，显示机器翻译
    func toggleAI(_ turnID: UUID) {
        guard let sentence = store.turn(currentID, turnID)?.sentence else { return }
        if sentence.showsAI {
            store.updateTurn(currentID, turnID) { $0.sentence?.aiShown = false }
        } else if sentence.aiTranslation != nil {
            store.updateTurn(currentID, turnID) { $0.sentence?.aiShown = true }
        } else {
            let sessionID = currentID
            Task { await optimize(sessionID, turnID) }
        }
    }

    /// 换了服务商或模型后，重新用 AI 优化一次
    func reoptimize(_ turnID: UUID) {
        let sessionID = currentID
        Task { await optimize(sessionID, turnID, force: true) }
    }

    /// 完整词条里查不到的词组，用和会话相同的方式翻译
    func quickTranslate(_ text: String) async -> String? {
        try? await translateSentence(text, chinese: text.isMostlyChinese).0
    }

    private func optimize(_ sessionID: UUID, _ turnID: UUID, force: Bool = false) async {
        guard let turn = store.turn(sessionID, turnID), let sentence = turn.sentence, !turn.isOptimizing else { return }
        if sentence.aiTranslation != nil, !force {
            store.updateTurn(sessionID, turnID) { $0.sentence?.aiShown = true }
            return
        }
        store.updateTurn(sessionID, turnID) {
            $0.isOptimizing = true
            $0.aiError = nil
        }
        Analytics.track(.aiOptimize, ["trigger": force ? "manual" : "auto", "provider": AISettings.shared.provider.rawValue])
        do {
            let config = AIClient.currentConfig
            let response = try await AITasks.calibrate(source: sentence.source, machine: sentence.translation,
                                                       sourceIsChinese: sentence.sourceIsChinese, config: config)
            store.updateTurn(sessionID, turnID) {
                guard var s = $0.sentence else { return }
                s.aiTranslation = response.text
                s.aiShown = true
                s.aiUsage = response.usage
                s.aiModel = "\(config.provider.title) · \(config.model)"
                $0.sentence = s
                $0.isOptimizing = false
            }
        } catch {
            store.updateTurn(sessionID, turnID) {
                $0.isOptimizing = false
                $0.aiError = error.localizedDescription
            }
        }
    }

    // MARK: 工具

    /// 没被用户命名的会话，用第一轮的原文开头当名称
    private func autoTitle(_ sessionID: UUID, from text: String) {
        guard let session = store.session(sessionID), session.autoTitled, session.title == ChatSession.defaultTitle else { return }
        let line = text.components(separatedBy: .newlines).first?.trimmed ?? text
        store.updateSession(sessionID) { $0.title = String(line.prefix(18)) }
    }

    /// 单词或短语（查词典）；不是的才算一句话，AI 优化只针对一句话
    static func isWordLike(_ text: String) -> Bool {
        if text.contains(where: { "\n,.!?;，。！？；".contains($0) }) { return false }
        if text.containsChinese { return text.count <= 8 }
        return text.count <= 40 && text.split(separator: " ").count <= 4
    }
}
