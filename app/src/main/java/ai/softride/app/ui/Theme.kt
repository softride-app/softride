package ai.softride.app.ui

import ai.softride.app.R
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** "물 한 컵" tokens (mockup_v2/DESIGN.md). */
data class Palette(
    val ground: Color, val ink: Color, val ink2: Color, val rule: Color,
    val water: Color, val surface: Color, val onWater: Color, val onWater2: Color, val told: Color, val band: Color,
)

val Day = Palette(
    ground = Color(0xFFEEF2F4), ink = Color(0xFF0E1B26), ink2 = Color(0xFF43535F), rule = Color(0xFFC3CDD4),
    water = Color(0xFF1A57A0), surface = Color(0xFF6A9FDA), onWater = Color(0xFFFFFFFF), onWater2 = Color(0xFFCFE0F3),
    told = Color(0xFF1A57A0), band = Color(0xFFD7DEE4),
)
val Night = Palette(
    ground = Color(0xFF1A2838), ink = Color(0xFFEDF1F4), ink2 = Color(0xFFA9B8C6), rule = Color(0xFF33465A),
    water = Color(0xFF0D3768), surface = Color(0xFF3F78B8), onWater = Color(0xFFEAF2FB), onWater2 = Color(0xFFA9C6E6),
    told = Color(0xFF8DBDED), band = Color(0xFF263749),
)

val LocalPalette = staticCompositionLocalOf { Day }

val Paperlogy = FontFamily(
    Font(R.font.paperlogy_extrabold, FontWeight.ExtraBold),
    Font(R.font.paperlogy_semibold, FontWeight.SemiBold),
)

@OptIn(ExperimentalTextApi::class)
val Pretendard = FontFamily(
    Font(R.font.pretendard_variable, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.pretendard_variable, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.pretendard_variable, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
)

/**
 * Mockups are drawn at 2560 px wide. [k] maps mockup px to this window so the layout keeps the
 * approved proportions whatever app area the system leaves us.
 */
class Scale(private val k: Float, private val density: Float) {
    fun dp(px: Float): Dp = (px * k / density).dp
    fun dp(px: Int): Dp = dp(px.toFloat())
    fun sp(px: Float): TextUnit = (px * k / density).sp   // fontScale 1 in the car
    fun sp(px: Int): TextUnit = sp(px.toFloat())
    fun px(v: Float) = v * k
}

val LocalScale = staticCompositionLocalOf { Scale(1f, 1f) }

@Composable
fun disp(px: Int, color: Color, tracking: Float = 0.01f): TextStyle = TextStyle(
    fontFamily = Paperlogy, fontWeight = FontWeight.ExtraBold, fontSize = LocalScale.current.sp(px),
    letterSpacing = tracking.em, color = color, lineHeight = LocalScale.current.sp(px * 1.08f), lineBreak = KeepAll,
)

/** Break Korean lines between words (CSS keep-all), so no syllable is left alone on a line. */
val KeepAll = LineBreak(LineBreak.Strategy.Balanced, LineBreak.Strictness.Strict, LineBreak.WordBreak.Phrase)

@Composable
fun sans(px: Int, color: Color, weight: FontWeight = FontWeight.SemiBold, tabular: Boolean = false): TextStyle = TextStyle(
    fontFamily = Pretendard, fontWeight = weight, fontSize = LocalScale.current.sp(px), color = color,
    lineHeight = LocalScale.current.sp(px * 1.3f), fontFeatureSettings = if (tabular) "tnum" else null, lineBreak = KeepAll,
)
