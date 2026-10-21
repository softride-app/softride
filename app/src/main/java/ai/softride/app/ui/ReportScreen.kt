package ai.softride.app.ui

import ai.softride.app.engine.TripReport
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign

/** Trip report (mockup A_06), laid out for the 1680×1210 app area. Shown only while parked. */
@Composable
fun ReportScreen(r: TripReport, onAgain: () -> Unit, onSettings: () -> Unit) {
    val c = LocalPalette.current
    val s = LocalScale.current
    val minutes = r.durationS >= 600   // short trips read in seconds, so axis ticks and the section never repeat
    fun t(sec: Int) = if (minutes) "${sec / 60}분" else "${sec}초"

    Box(Modifier.fillMaxSize().background(c.ground)) {
        BasicText(r.route, style = disp(96, c.ink), modifier = Modifier.offset(s.dp(56), s.dp(70)))
        BasicText(
            "${t(r.durationS)} 동안" + (r.child?.let { ", $it 뒷좌석" } ?: ""),
            style = sans(42, c.ink2, FontWeight.Medium), modifier = Modifier.offset(s.dp(60), s.dp(200)),
        )
        Column(Modifier.offset(s.dp(1024), s.dp(10)).width(s.dp(600)), horizontalAlignment = Alignment.End) {
            BasicText("${r.score}", style = disp(190, c.ink, -0.02f).copy(textAlign = TextAlign.End))
            BasicText("부드러운 운전 점수", style = sans(42, c.ink))
        }

        // Sway trace as water filling from the baseline; the most-swaying section is marked behind it.
        val gx = 56f; val gw = 1568f; val gTop = 420f; val gBase = 690f   // label sits above gTop, never on water
        val n = r.samples.size.coerceAtLeast(2)
        fun gxAt(i: Int) = gx + gw * i / (n - 1)
        Canvas(Modifier.fillMaxSize()) {
            fun y(v: Float) = s.px(gBase - (gBase - gTop) * (v.coerceIn(0f, 100f) / 100f))
            drawRect(c.band, Offset(s.px(gxAt(r.mostFromS)), s.px(gTop - 120f)),
                Size(s.px(gxAt(r.mostToS) - gxAt(r.mostFromS)), s.px(gBase - gTop + 120f)))
            val pts = r.samples.mapIndexed { i, v -> Offset(s.px(gxAt(i)), y(v)) }
            drawPath(Path().apply { moveTo(pts.first().x, s.px(gBase)); pts.forEach { lineTo(it.x, it.y) }; lineTo(pts.last().x, s.px(gBase)); close() }, c.water)
            drawPath(Path().apply { pts.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) } },
                c.surface, style = Stroke(width = s.px(8f)))
            drawRect(c.ink, Offset(s.px(gx), s.px(gBase)), Size(s.px(gw), s.px(3f)))
        }
        val labelX = gxAt(r.mostFromS).coerceAtMost(gx + gw - 330f)
        Column(Modifier.offset(s.dp(labelX + 16), s.dp(gTop - 110))) {
            BasicText("가장 흔들린 구간", style = sans(38, c.ink))
            BasicText("${t(r.mostFromS)}–${t(r.mostToS)}", style = sans(34, c.ink2, FontWeight.Medium, tabular = true))
        }
        Row(Modifier.offset(s.dp(gx), s.dp(gBase + 14)).width(s.dp(gw))) {
            val ticks = 4
            repeat(ticks + 1) { i ->
                BasicText(
                    t(r.durationS * i / ticks),
                    style = sans(34, c.ink2, FontWeight.Medium, tabular = true).copy(
                        textAlign = when (i) { 0 -> TextAlign.Start; ticks -> TextAlign.End; else -> TextAlign.Center }),
                    modifier = Modifier.width(s.dp(gw / (ticks + 1))),
                )
            }
        }

        Row(Modifier.offset(s.dp(56), s.dp(800))) {
            Stat("${r.avg}", "평균 흔들림"); Stat("${r.cues}", "미리 알림"); Stat("${r.airCares}", "공조 케어"); Stat("${r.rests}", "휴식")
        }

        Column(Modifier.offset(s.dp(1010), s.dp(810)).width(s.dp(614))) {
            BasicText("${t(r.mostFromS)}쯤 가장 많이 흔들렸어요.", style = sans(42, c.ink))
            BasicText("그 앞에서 미리 속도를 줄여 보세요.", style = sans(42, c.ink))
            Spacer(Modifier.height(s.dp(22)))
            Box(Modifier.width(s.dp(614)).height(s.dp(3)).background(c.rule))
            Spacer(Modifier.height(s.dp(22)))
            BasicText(if (r.cues > 0) "뒷좌석에 ${r.cues}번 미리 알렸어요." else "이번엔 미리 알릴 일이 없었어요.", style = sans(42, c.ink))
            Spacer(Modifier.height(s.dp(30)))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.clickable(onClick = onAgain).background(c.water, RoundedCornerShape(s.dp(16)))
                        .padding(horizontal = s.dp(40), vertical = s.dp(20))
                ) { BasicText("다시 시험 주행", style = sans(40, c.onWater)) }
                Spacer(Modifier.width(s.dp(40)))
                BasicText("탑승자 설정", style = sans(40, c.told), modifier = Modifier.clickable(onClick = onSettings).padding(vertical = s.dp(12)))
            }
        }
    }
}

@Composable
private fun Stat(value: String, label: String) {
    val c = LocalPalette.current
    val s = LocalScale.current
    Column(Modifier.width(s.dp(236))) {
        BasicText(value, style = disp(120, c.ink))
        BasicText(label, style = sans(40, c.ink2, FontWeight.Medium))
    }
}
