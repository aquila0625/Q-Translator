package com.yishulabs.qtranslator.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** 和苹果版一致的轻快明亮配色：蓝色是主色，橙色专门给 AI */
@Immutable
data class LxColors(
    val background: Color,
    val wash: Color,
    val surface: Color,
    val surface2: Color,
    val accent: Color,
    val accentSoft: Color,
    val onAccent: Color,
    val ai: Color,
    val aiSoft: Color,
    val ink: Color,
    val ink2: Color,
    val ink3: Color,
    val line: Color,
    /** 会话里句子译文卡片的底色（浅薄荷绿），和蓝色的单词卡片、橙色的 AI 分开 */
    val sentenceCard: Color,
    /** 会话里图片译文卡片的底色（中性浅灰） */
    val imageCard: Color,
    /** 和卡片底色配套的深色：用于筛选按钮的文字和选中时的底色 */
    val sentenceInk: Color,
    val imageInk: Color,
    /** 面对面对话（浅青色）、同声传译（浅琥珀色）、场景练习（浅玫瑰色） */
    val dialogCard: Color,
    val dialogInk: Color,
    val transcriptCard: Color,
    val transcriptInk: Color,
    val practiceCard: Color,
    val practiceInk: Color,
    val isDark: Boolean,
)

private val Light = LxColors(
    background = Color(0xFFFFFFFF), wash = Color(0xFFE4F0FF), surface = Color(0xFFF1F7FF), surface2 = Color(0xFFE1ECFB),
    accent = Color(0xFF0B6BF0), accentSoft = Color(0xFFDCEBFF), onAccent = Color.White,
    ai = Color(0xFFB8430A), aiSoft = Color(0xFFFFEEDD),
    ink = Color(0xFF0F1720), ink2 = Color(0xFF4A5565), ink3 = Color(0xFF5F6B7A), line = Color(0x170F1720),
    sentenceCard = Color(0xFFEEF7F2), imageCard = Color(0xFFF3F4F6), sentenceInk = Color(0xFF1E7A45), imageInk = Color(0xFF4A5565),
    dialogCard = Color(0xFFE6F5F4), dialogInk = Color(0xFF0B6B66), transcriptCard = Color(0xFFFFF6E3), transcriptInk = Color(0xFF8A5A00),
    practiceCard = Color(0xFFFDEFF3), practiceInk = Color(0xFFB0305C), isDark = false,
)

private val Dark = LxColors(
    background = Color(0xFF0B0E13), wash = Color(0xFF0F1B2E), surface = Color(0xFF161B23), surface2 = Color(0xFF232A35),
    accent = Color(0xFF62A8FF), accentSoft = Color(0xFF12305A), onAccent = Color(0xFF04142B),
    ai = Color(0xFFFFAE78), aiSoft = Color(0xFF3A2210),
    ink = Color(0xFFF1F4F8), ink2 = Color(0xFFAEB8C4), ink3 = Color(0xFF97A2AF), line = Color(0x1FFFFFFF),
    sentenceCard = Color(0xFF15221C), imageCard = Color(0xFF1A1D22), sentenceInk = Color(0xFF7FD6A3), imageInk = Color(0xFFAEB8C4),
    dialogCard = Color(0xFF0F2726), dialogInk = Color(0xFF6FD3CC), transcriptCard = Color(0xFF2A2210), transcriptInk = Color(0xFFF2C14E),
    practiceCard = Color(0xFF2A1620), practiceInk = Color(0xFFF59AB8), isDark = true,
)

val LocalLx = staticCompositionLocalOf { Light }

object Lx {
    val colors: LxColors @Composable get() = LocalLx.current
}

/** 场景封面配色：浅色底 + 深色图标 */
object ScenePalette {
    private val light = listOf(
        Color(0xFFDCEBFF) to Color(0xFF0A58C9), Color(0xFFE3F5EA) to Color(0xFF1E7A45), Color(0xFFFFEEDD) to Color(0xFFB8430A),
        Color(0xFFDDF3F2) to Color(0xFF0B6B66), Color(0xFFFFF4D6) to Color(0xFF8A5A00), Color(0xFFECEEF2) to Color(0xFF4A5565),
    )
    private val dark = listOf(
        Color(0xFF12305A) to Color(0xFF8CC0FF), Color(0xFF123322) to Color(0xFF7FD6A3), Color(0xFF3A2210) to Color(0xFFFFAE78),
        Color(0xFF0F3331) to Color(0xFF6FD3CC), Color(0xFF3A2E0C) to Color(0xFFF2C14E), Color(0xFF262A30) to Color(0xFFAEB8C4),
    )
    val count = light.size

    @Composable
    fun color(index: Int): Pair<Color, Color> {
        val list = if (Lx.colors.isDark) dark else light
        return list[((index % count) + count) % count]
    }
}

/** 设置里的外观：0 跟随系统，1 浅色，2 深色 */
@Composable
fun QTranslatorTheme(content: @Composable () -> Unit) {
    val dark = when (com.yishulabs.qtranslator.core.Prefs.appearance) {
        1 -> false
        2 -> true
        else -> isSystemInDarkTheme()
    }
    val lx = if (dark) Dark else Light
    val scheme = if (dark) {
        darkColorScheme(
            primary = lx.accent, onPrimary = lx.onAccent, primaryContainer = lx.accentSoft, background = lx.background,
            surface = lx.background, surfaceContainerLow = lx.surface, surfaceContainer = lx.surface,
            surfaceContainerHigh = lx.surface2, onSurface = lx.ink, onSurfaceVariant = lx.ink2, error = Color(0xFFFF6B5E),
        )
    } else {
        lightColorScheme(
            primary = lx.accent, onPrimary = lx.onAccent, primaryContainer = lx.accentSoft, background = lx.background,
            surface = lx.background, surfaceContainerLow = lx.surface, surfaceContainer = lx.surface,
            surfaceContainerHigh = lx.surface2, onSurface = lx.ink, onSurfaceVariant = lx.ink2, error = Color(0xFFD92D20),
        )
    }
    CompositionLocalProvider(LocalLx provides lx) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
