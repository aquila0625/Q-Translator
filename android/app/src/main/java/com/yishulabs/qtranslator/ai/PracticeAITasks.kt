package com.yishulabs.qtranslator.ai

import com.yishulabs.qtranslator.core.AIUsage
import com.yishulabs.qtranslator.core.Phrase
import com.yishulabs.qtranslator.modules.PracticeLine
import org.json.JSONObject

/** 英语场景练习用到的 AI：扮演角色接着说，同时给用户上一句更地道的说法。对应苹果版 AITasks.practiceTurn */
object PracticeAITasks {
    class PracticeReply(
        /** 用户上一句更地道的说法和原因（中文）；说得好就是 null */
        val better: String?,
        val reason: String?,
        /** AI 扮演的角色接着说的话和中文意思 */
        val reply: String,
        val chinese: String,
        val phrases: List<Phrase>,
        /** 自己描述的场景：AI 选的角色（中文，例如“咖啡师”） */
        val role: String?,
        val usage: AIUsage?,
    )

    val levels = listOf("初级", "中级", "高级")

    fun levelTitle(level: Int): String = levels[level.coerceIn(0, 2)]

    /**
     * 场景练习的一轮：先看用户刚说的那句，给出更地道的说法（不打断对话），再以角色身份接着说。
     * lines 为空时由 AI 开场。
     */
    suspend fun practiceTurn(scenario: String, role: String, level: Int, lines: List<PracticeLine>, config: AIClient.Config): PracticeReply {
        val levelRule = when (level) {
            0 -> "The learner is a beginner: use short sentences and everyday words, and speak slowly and clearly."
            2 -> "The learner is advanced: speak naturally as a native speaker would, with idioms and a normal pace."
            else -> "The learner is intermediate: use natural everyday English, but avoid rare words and long sentences."
        }
        val roleRule = if (role.isEmpty()) {
            "Choose the most fitting role for yourself in this scenario, and give its name in Simplified Chinese (2–4 characters) in the `role` field."
        } else {
            "You play the $role (this is the role's name in Chinese). Set `role` to null."
        }
        val system = """
            You are a role-play partner in an English speaking practice app for Chinese speakers. The learner practices a real-life scenario with you: you play one side and the learner plays the other, speaking English. Their messages usually come from speech recognition, so ignore capitalization, punctuation and obvious recognition slips.

            Scenario (described in Chinese): $scenario
            $roleRule
            $levelRule

            Each time, do the following.
            1. Look at the learner's latest message. If it has grammar mistakes or wording a native speaker would not use, or if it is partly or fully in Chinese because they did not know how to say it, put a natural English version of the whole message in `better`, and explain the key point in one short Simplified Chinese sentence in `reason`. If the message is already natural, set both to null. Do not rewrite messages that are fine just to sound fancier.
            2. Reply in character with 1 to 3 short spoken sentences that keep the conversation going; usually end with a question or something the learner can respond to. Never step out of the role in the reply to teach or correct: the app shows the correction separately.
            3. Put a faithful Simplified Chinese translation of your reply in `chinese`.
            4. In `phrases`, list up to 2 expressions from your reply or from `better` that are worth learning for this scenario, each as {"en": "...", "zh": "..."} with a short Chinese meaning. Use an empty list when nothing stands out.

            If there are no messages yet, open the conversation in character with a natural first line, and set `better` and `reason` to null.

            Answer with only one JSON object and nothing else, because the app parses it:
            {"better": string or null, "reason": string or null, "reply": string, "chinese": string, "phrases": [{"en": string, "zh": string}], "role": string or null}
        """.trimIndent()
        val speaker = if (role.isEmpty()) "Partner" else "Partner ($role)"
        val history = lines.joinToString("\n") { (if (it.isMine) "Learner" else speaker) + ": " + it.text }
        val user = if (lines.isEmpty()) {
            "There are no messages yet. Open the conversation."
        } else {
            "<conversation>\n$history\n</conversation>\nRespond to the learner's latest message."
        }
        // 用量在 AIClient.complete 里记
        val response = AIClient.complete(system, user, config)
        val start = response.text.indexOf('{')
        val end = response.text.lastIndexOf('}')
        val json = if (start >= 0 && end > start) runCatching { JSONObject(response.text.substring(start, end + 1)) }.getOrNull() else null
        val reply = json?.text("reply") ?: throw AIException("AI 返回的格式不对，可以重试。")
        val list = json.optJSONArray("phrases")
        val phrases = (0 until (list?.length() ?: 0)).mapNotNull { index ->
            val item = list?.optJSONObject(index) ?: return@mapNotNull null
            val en = item.text("en") ?: return@mapNotNull null
            Phrase(en, item.text("zh") ?: "")
        }
        return PracticeReply(
            better = json.text("better"), reason = json.text("reason"), reply = reply, chinese = json.text("chinese") ?: "",
            phrases = phrases.take(2), role = json.text("role"), usage = response.usage,
        )
    }

    /** 取一个字符串字段：没有、是 null 或空白都当作 null */
    private fun JSONObject.text(key: String): String? {
        if (isNull(key)) return null
        val value = (opt(key) as? String)?.trim() ?: return null
        return value.ifEmpty { null }
    }
}
