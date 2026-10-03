import Foundation

/// Q-Translator 里用到 AI 的几件事：校准机器翻译、帮用户写回复。
enum AITasks {
    enum ReplyKind: String, CaseIterable, Identifiable {
        case message, email
        var id: String { rawValue }
        var title: String { self == .message ? "短信 / 微信" : "邮件" }
    }

    enum ReplyMode: String, CaseIterable, Identifiable {
        case points, draft
        var id: String { rawValue }
        var title: String { self == .points ? "我说要点，AI 来写" : "我写草稿，AI 优化" }
    }

    struct Reply {
        let text: String
        let chinese: String
        let usage: AIUsage?
    }

    // MARK: 校准译文

    static func calibrate(source: String, machine: String, sourceIsChinese: Bool, config: AIClient.Config) async throws -> AIResponse {
        let system = """
        You review machine translations between English and Simplified Chinese for a dictionary and translation app. \
        The user gives you a source text and a machine translation of it.

        Return an improved translation in the target language. Fix mistranslations, wrong word senses, unnatural \
        phrasing and awkward word order, while keeping the meaning, tone and line breaks of the source. \
        If the machine translation is already accurate and natural, return it unchanged.

        Output only the translation itself, with no explanation, quotation marks or labels: the app shows your \
        output to the user directly as the translation.
        """
        let user = """
        <source>
        \(source)
        </source>
        <machine_translation>
        \(machine)
        </machine_translation>
        Target language: \(sourceIsChinese ? "English" : "Simplified Chinese")
        """
        return try await AIClient.complete(system: system, user: user, config: config)
    }

    // MARK: 图片里的文字

    /// 把图片里识别出的几段文字一起翻译，可以带用户的要求（例如“只翻译菜名”）。返回和输入一一对应的译文，跳过的段是空字符串
    static func translateImageBlocks(_ blocks: [String], instruction: String?, toChinese: Bool,
                                     config: AIClient.Config) async throws -> (texts: [String], usage: AIUsage?) {
        let system = """
        You translate text that was recognized (OCR) from a photo in a translation app. The text comes as numbered \
        blocks; each block is one paragraph or label at its own place in the image, and the app draws your translation \
        over the original text at that place.

        Translate every block into \(toChinese ? "Simplified Chinese" : "English"). Keep translations about as short as \
        the original so they fit in the same space. Fix obvious OCR mistakes. If the user gives an instruction, follow it; \
        when the instruction says to leave some content out, use an empty string for those blocks.

        Answer with only a JSON array of strings, one per block in the same order, and nothing else: the app parses it.
        """
        var user = blocks.enumerated().map { "<block index=\"\($0.offset + 1)\">\n\($0.element)\n</block>" }.joined(separator: "\n")
        if let instruction, !instruction.trimmed.isEmpty {
            user += "\n<instruction>\n\(instruction.trimmed)\n</instruction>"
        }
        let response = try await AIClient.complete(system: system, user: user, config: config)
        var texts: [String] = []
        if let start = response.text.firstIndex(of: "["), let end = response.text.lastIndex(of: "]"),
           let data = String(response.text[start...end]).data(using: .utf8),
           let decoded = try? JSONDecoder().decode([String].self, from: data) {
            texts = decoded
        } else {
            throw AIError(message: "AI 返回的格式不对，可以重试。")
        }
        // 数量对不上时按位置补齐或截断
        texts = Array((texts + Array(repeating: "", count: max(0, blocks.count - texts.count))).prefix(blocks.count))
        return (texts, response.usage)
    }

    // MARK: 同声传译要点

    /// 把一段讲座或会议的字幕整理成中文要点
    static func summarizeTranscript(_ lines: [TranscriptLine], config: AIClient.Config) async throws -> AIResponse {
        let system = """
        You summarize the transcript of a lecture or meeting for a Chinese-speaking user. The transcript comes from live \
        speech recognition, so ignore small recognition slips. Lines are in the original language; translations may follow.

        Write 5 to 8 key points in Simplified Chinese, one per line, each starting with "• ". Keep every point short and \
        concrete (names, numbers, deadlines and decisions matter most). End with one line starting with "待办：" listing \
        action items or deadlines mentioned, or leave that line out if there are none. Output only the points: the app \
        shows your answer to the user directly.
        """
        var text = lines.map { $0.original }.joined(separator: "\n")
        if text.count > 40000 { text = String(text.prefix(40000)) }
        return try await AIClient.complete(system: system, user: "<transcript>\n\(text)\n</transcript>", config: config)
    }

    // MARK: 场景练习

    struct PracticeReply {
        /// 用户上一句更地道的说法和原因（中文）；说得好就是 nil
        var better: String?
        var reason: String?
        /// AI 扮演的角色接着说的话和中文意思
        var reply: String
        var chinese: String
        var phrases: [Phrase]
        /// 自己描述的场景：AI 选的角色（中文，例如“咖啡师”）
        var role: String?
        var usage: AIUsage?
    }

    static let practiceLevels = ["初级", "中级", "高级"]

    /// 场景练习的一轮：先看用户刚说的那句，给出更地道的说法（不打断对话），再以角色身份接着说。
    /// lines 为空时由 AI 开场。
    static func practiceTurn(scenario: String, role: String, level: Int, lines: [PracticeLine],
                             config: AIClient.Config) async throws -> PracticeReply {
        let levelRule = switch level {
        case 0: "The learner is a beginner: use short sentences and everyday words, and speak slowly and clearly."
        case 2: "The learner is advanced: speak naturally as a native speaker would, with idioms and a normal pace."
        default: "The learner is intermediate: use natural everyday English, but avoid rare words and long sentences."
        }
        let roleRule = role.isEmpty
            ? "Choose the most fitting role for yourself in this scenario, and give its name in Simplified Chinese (2–4 characters) in the `role` field."
            : "You play the \(role) (this is the role's name in Chinese). Set `role` to null."
        let system = """
        You are a role-play partner in an English speaking practice app for Chinese speakers. The learner practices \
        a real-life scenario with you: you play one side and the learner plays the other, speaking English. Their \
        messages usually come from speech recognition, so ignore capitalization, punctuation and obvious recognition slips.

        Scenario (described in Chinese): \(scenario)
        \(roleRule)
        \(levelRule)

        Each time, do the following.
        1. Look at the learner's latest message. If it has grammar mistakes or wording a native speaker would not use, \
        or if it is partly or fully in Chinese because they did not know how to say it, put a natural English version of \
        the whole message in `better`, and explain the key point in one short Simplified Chinese sentence in `reason`. \
        If the message is already natural, set both to null. Do not rewrite messages that are fine just to sound fancier.
        2. Reply in character with 1 to 3 short spoken sentences that keep the conversation going; usually end with a \
        question or something the learner can respond to. Never step out of the role in the reply to teach or correct: \
        the app shows the correction separately.
        3. Put a faithful Simplified Chinese translation of your reply in `chinese`.
        4. In `phrases`, list up to 2 expressions from your reply or from `better` that are worth learning for this \
        scenario, each as {"en": "...", "zh": "..."} with a short Chinese meaning. Use an empty list when nothing stands out.

        If there are no messages yet, open the conversation in character with a natural first line, and set `better` \
        and `reason` to null.

        Answer with only one JSON object and nothing else, because the app parses it:
        {"better": string or null, "reason": string or null, "reply": string, "chinese": string, \
        "phrases": [{"en": string, "zh": string}], "role": string or null}
        """
        let speaker = role.isEmpty ? "Partner" : "Partner (\(role))"
        let history = lines.map { ($0.isMine ? "Learner" : speaker) + ": " + $0.text }.joined(separator: "\n")
        let user = lines.isEmpty
            ? "There are no messages yet. Open the conversation."
            : "<conversation>\n\(history)\n</conversation>\nRespond to the learner's latest message."
        let response = try await AIClient.complete(system: system, user: user, config: config)
        guard let start = response.text.firstIndex(of: "{"), let end = response.text.lastIndex(of: "}"),
              let data = String(response.text[start...end]).data(using: .utf8),
              let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let reply = (json["reply"] as? String)?.trimmed, !reply.isEmpty else {
            throw AIError(message: "AI 返回的格式不对，可以重试。")
        }
        func text(_ key: String) -> String? {
            let value = (json[key] as? String)?.trimmed ?? ""
            return value.isEmpty ? nil : value
        }
        let phrases = (json["phrases"] as? [[String: Any]] ?? []).compactMap { item -> Phrase? in
            guard let en = (item["en"] as? String)?.trimmed, !en.isEmpty else { return nil }
            return Phrase(key: en, value: (item["zh"] as? String)?.trimmed ?? "")
        }
        return PracticeReply(better: text("better"), reason: text("reason"), reply: reply, chinese: text("chinese") ?? "",
                             phrases: Array(phrases.prefix(2)), role: text("role"), usage: response.usage)
    }

    // MARK: 写回复

    private static let separator = "===ZH==="

    /// previous + change：在上一版回复的基础上按要求修改（更短、更正式……）
    static func reply(to received: String, kind: ReplyKind, mode: ReplyMode, input: String,
                      previous: String? = nil, change: String? = nil, config: AIClient.Config) async throws -> Reply {
        let kindRule = switch kind {
        case .message:
            "The reply is a text message (SMS or WeChat): short, natural and conversational, with no subject line and no sign-off."
        case .email:
            "The reply is an email: begin with a line `Subject: ...`, then a greeting, the body and a sign-off. Use [Your name] where the sender's name goes."
        }
        let modeRule = switch mode {
        case .points:
            "The user describes what they want to say, often in Chinese. Write the reply from those points. Do not add commitments, facts or details the user did not mention."
        case .draft:
            "The user wrote a draft of the reply themselves. Keep their meaning and level of detail; only fix grammar, word choice and tone."
        }
        let system = """
        You help the user of a translation app reply to a message they received. The user is a Chinese speaker.

        Write the reply in the same language as the received message, so the user can paste it straight back to \
        the sender. \(kindRule) \(modeRule)

        After the reply, add a faithful Simplified Chinese translation of it so the user can check what they are \
        about to send.

        Format your answer as: the reply, then a line containing exactly \(separator), then the Chinese translation. \
        Write nothing else, because the app splits your answer on that line and shows both parts to the user.
        """
        var user = """
        <received_message>
        \(received)
        </received_message>
        <user_input>
        \(input)
        </user_input>
        """
        if let previous, let change {
            user += """

            <previous_reply>
            \(previous)
            </previous_reply>
            <requested_change>
            \(change)
            </requested_change>
            Revise the previous reply according to the requested change.
            """
        }
        let response = try await AIClient.complete(system: system, user: user, config: config)
        let parts = response.text.components(separatedBy: separator)
        return Reply(text: parts[0].trimmed, chinese: parts.count > 1 ? parts[1].trimmed : "", usage: response.usage)
    }
}
