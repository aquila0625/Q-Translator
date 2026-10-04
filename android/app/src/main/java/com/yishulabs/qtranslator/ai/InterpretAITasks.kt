package com.yishulabs.qtranslator.ai

import com.yishulabs.qtranslator.modules.TranscriptLine

/** 同声传译用到 AI 的事：总结一段讲座或会议的要点。对应苹果版 AITasks.summarizeTranscript */
object InterpretAITasks {
    suspend fun summarizeTranscript(lines: List<TranscriptLine>, config: AIClient.Config): AIResponse {
        val system = """
            You summarize the transcript of a lecture or meeting for a Chinese-speaking user. The transcript comes from live speech recognition, so ignore small recognition slips. Lines are in the original language; translations may follow.

            Write 5 to 8 key points in Simplified Chinese, one per line, each starting with "• ". Keep every point short and concrete (names, numbers, deadlines and decisions matter most). End with one line starting with "待办：" listing action items or deadlines mentioned, or leave that line out if there are none. Output only the points: the app shows your answer to the user directly.
        """.trimIndent()
        var text = lines.joinToString("\n") { it.original }
        if (text.length > 40000) text = text.take(40000)
        return AIClient.complete(system, "<transcript>\n$text\n</transcript>", config)
    }
}
