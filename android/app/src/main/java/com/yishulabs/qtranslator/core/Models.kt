package com.yishulabs.qtranslator.core

import kotlinx.serialization.Serializable

@Serializable
data class Phonetic(val label: String, val ipa: String, val accent: Int)

/** 按词性汇总的一行释义，例如 “v. 充电；控告；收费” */
@Serializable
data class Definition(val text: String, val note: String? = null)

@Serializable
data class ExamplePair(val source: String, val translation: String, val english: String)

/** 单个义项：一个意思配它自己的词性和例句 */
@Serializable
data class Sense(val pos: String?, val meaning: String, val isCommon: Boolean, val examples: List<ExamplePair>)

@Serializable
data class CollinsSense(val pos: String?, val explanation: String, val examples: List<ExamplePair>)

@Serializable
data class Phrase(val key: String, val value: String)

@Serializable
data class RelatedWord(val pos: String, val word: String, val meaning: String)

/** 近义词辨析：一组容易混淆的词和各自的用法 */
@Serializable
data class Distinction(val title: String, val usages: List<Phrase>)

@Serializable
data class Suggestion(val word: String, val meaning: String)

@Serializable
data class WordEntry(
    val word: String,
    val isChinese: Boolean,
    val phonetics: List<Phonetic> = emptyList(),
    val pinyin: String? = null,
    val tags: List<String> = emptyList(),
    val definitions: List<Definition> = emptyList(),
    val senses: List<Sense> = emptyList(),
    val forms: List<String> = emptyList(),
    val collins: List<CollinsSense> = emptyList(),
    val examples: List<ExamplePair> = emptyList(),
    val webMeanings: List<String> = emptyList(),
    val phrases: List<Phrase> = emptyList(),
    val related: List<RelatedWord> = emptyList(),
    val distinctions: List<Distinction> = emptyList(),
    val etymology: String? = null,
) {
    val hasContent: Boolean
        get() = definitions.isNotEmpty() || senses.isNotEmpty() || collins.isNotEmpty() || webMeanings.isNotEmpty()

    /** 生词本里显示的一行摘要 */
    val summary: String
        get() = definitions.firstOrNull()?.text ?: senses.firstOrNull()?.meaning ?: webMeanings.firstOrNull() ?: ""
}

/** 一次 AI 请求消耗的 token 数，由服务商在响应里给出 */
@Serializable
data class AIUsage(val input: Int, val output: Int) {
    val total: Int get() = input + output

    /** 例如 “本次消耗 286 tokens（输入 231 · 输出 55）” */
    val summary: String get() = "本次消耗 $total tokens（输入 $input · 输出 $output）"
}

@Serializable
data class SentenceResult(
    val source: String,
    /** 始终是机器翻译；AI 的结果单独保存，可以随时开关、不用重新请求 */
    val translation: String,
    val sourceIsChinese: Boolean,
    val engine: String,
    val suggestions: List<Suggestion> = emptyList(),
    val aiTranslation: String? = null,
    val aiShown: Boolean? = null,
    /** 优化时用的服务商和模型，例如 “ChatGPT · gpt-6-luna” */
    val aiModel: String? = null,
    val aiUsage: AIUsage? = null,
) {
    val showsAI: Boolean get() = aiShown == true && aiTranslation != null

    /** 当前显示的译文 */
    val displayed: String get() = if (showsAI) aiTranslation ?: translation else translation
}

private fun Char.isHan(): Boolean = this in '一'..'鿿' || this in '㐀'..'䶿'

val String.containsChinese: Boolean get() = any { it.isHan() }

/**
 * 中英混排时，哪种语言占比大就以哪种为原文。一个英文单词大约相当于两个汉字的信息量，
 * 所以按“汉字数”对“英文单词数 × 2”来比。
 */
val String.isMostlyChinese: Boolean
    get() {
        var chinese = 0
        var englishWords = 0
        var inWord = false
        for (ch in this) {
            when {
                ch.isHan() -> {
                    chinese++
                    inWord = false
                }
                ch in 'a'..'z' || ch in 'A'..'Z' -> {
                    if (!inWord) englishWords++
                    inWord = true
                }
                ch != '\'' && ch != '-' -> inWord = false
            }
        }
        if (chinese == 0) return false
        return chinese >= englishWords * 2
    }

val String.strippingTags: String get() = replace(Regex("<[^>]+>"), "")

/** 会话列表里的相对时间（刚刚、5 分钟前、3 小时前、昨天、10月2日）。界面是中文，不跟手机语言走 */
fun relativeTime(time: Long, now: Long = System.currentTimeMillis()): String {
    val minutes = (now - time) / 60_000
    val calendar = java.util.Calendar.getInstance()
    calendar.timeInMillis = now
    calendar.set(java.util.Calendar.HOUR_OF_DAY, 0); calendar.set(java.util.Calendar.MINUTE, 0)
    calendar.set(java.util.Calendar.SECOND, 0); calendar.set(java.util.Calendar.MILLISECOND, 0)
    val startOfToday = calendar.timeInMillis
    return when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> "$minutes 分钟前"
        time >= startOfToday -> "${minutes / 60} 小时前"
        time >= startOfToday - 86_400_000 -> "昨天"
        else -> java.text.SimpleDateFormat("M月d日", java.util.Locale.CHINA).format(java.util.Date(time))
    }
}
