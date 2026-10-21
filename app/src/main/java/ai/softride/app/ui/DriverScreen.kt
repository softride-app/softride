package ai.softride.app.ui

import ai.softride.app.engine.AirCare
import ai.softride.app.engine.AirState
import ai.softride.app.engine.RestState
import ai.softride.app.engine.Screen
import ai.softride.app.engine.UiState
import ai.softride.app.signals.EventKind
import ai.softride.app.signals.RouteEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlin.math.sin

/**
 * Driver main screen (mockup A_01), laid out for the Pleos app area (1680×1210 px next to the driving view).
 * Static while driving: the engine publishes the sway number in steps of at most every 5 s and nothing animates.
 * Coordinates are px of that app area; [Scale] maps them to the real window.
 */
@Composable
fun DriverScreen(ui: UiState, onStartDemo: () -> Unit, onSettings: () -> Unit) {
    val c = LocalPalette.current
    val s = LocalScale.current
    val driving = ui.screen == Screen.DRIVING
    val (head, sub) = when {
        !driving -> "출발 전이에요" to "뒷좌석 알림을 준비했어요"
        ui.sway < 25 -> "잔잔해요" to "물 한 컵도 넘치지 않을 만큼"
        ui.sway < 50 -> "조금 출렁여요" to "흔들림이 조금 커졌어요"
        else -> "많이 흔들려요" to "흔들림이 많이 커졌어요"
    }
    val leftW = 790

    Box(Modifier.fillMaxSize().background(c.ground)) {
        // Water: the one living element. Level and calm come from sway; redrawn only when the number steps.
        Canvas(Modifier.offset(0.dp, s.dp(470)).width(s.dp(leftW)).fillMaxHeight()) {
            val sway = ui.sway.toFloat()
            val tilt = s.px(sway * 0.45f); val amp = s.px(sway * 0.22f); val period = s.px(90f)
            val level = s.px(60f)
            val pts = (0..size.width.toInt() step 8).map { x ->
                Offset(x.toFloat(), level - tilt / 2 + tilt * x / size.width + amp * sin(x / period))
            }
            drawPath(Path().apply { moveTo(0f, size.height); pts.forEach { lineTo(it.x, it.y) }; lineTo(size.width, size.height); close() }, c.water)
            drawPath(Path().apply { pts.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) } },
                c.surface, style = Stroke(width = s.px(10f)))
        }

        // Wordmark, and the simulation badge whenever Replay feeds the app
        Row(Modifier.offset(s.dp(56), s.dp(40)), verticalAlignment = Alignment.CenterVertically) {
            BasicText("소프트라이드", style = disp(40, c.ink).copy(fontWeight = FontWeight.SemiBold))
            if (ui.simulated) {
                Spacer(Modifier.width(s.dp(36)))
                Box(Modifier.size(s.dp(16)).background(c.water, RoundedCornerShape(s.dp(3))))
                Spacer(Modifier.width(s.dp(12)))
                BasicText("시뮬레이션 입력", style = sans(36, c.ink2, FontWeight.Medium))
            }
        }
        // Arrival, right-anchored so the changing minutes never push the label
        if (!ui.destination.isNullOrBlank() && (ui.remainMin ?: 0) > 0) {
            BasicText(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontFamily = Pretendard, fontWeight = FontWeight.SemiBold, fontSize = s.sp(42))) { append("${ui.destination}까지 ") }
                    withStyle(SpanStyle(fontFamily = Paperlogy, fontWeight = FontWeight.ExtraBold, fontSize = s.sp(72))) { append("${ui.remainMin}") }
                    withStyle(SpanStyle(fontFamily = Pretendard, fontWeight = FontWeight.SemiBold, fontSize = s.sp(42))) { append("분") }
                },
                style = sans(42, c.ink).copy(textAlign = TextAlign.End),
                modifier = Modifier.offset(s.dp(1000), s.dp(14)).width(s.dp(624)),
            )
        }

        // State headline
        Column(Modifier.offset(s.dp(50), s.dp(178))) {
            BasicText(head, style = disp(when { head.length <= 4 -> 160; head.length <= 6 -> 124; else -> 108 }, c.ink))
            Spacer(Modifier.height(s.dp(20)))
            BasicText(sub, style = sans(42, c.ink2, FontWeight.Medium), modifier = Modifier.padding(start = s.dp(8)))
        }

        // Sway number on water, left-anchored in a box sized for "100"
        BasicText("${ui.sway}", style = disp(300, c.onWater, -0.02f), modifier = Modifier.offset(s.dp(70), s.dp(560)).width(s.dp(640)))
        Column(Modifier.offset(s.dp(80), s.dp(905))) {
            BasicText("흔들림 지수", style = sans(50, c.onWater))
            BasicText("낮을수록 부드러워요", style = sans(36, c.onWater2, FontWeight.Medium))
        }
        BasicText(
            if (driving) "지난 5분 기준, 5초마다 바뀌어요" else "출발하면 흔들림을 재기 시작해요",
            style = sans(36, c.onWater2, FontWeight.Medium), modifier = Modifier.offset(s.dp(80), s.dp(1100)),
        )

        // Right column: what comes next
        val rx = 860; val rw = 764
        Column(Modifier.offset(s.dp(rx), s.dp(170)).width(s.dp(rw))) {
            val next = ui.next
            if (next != null && driving) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    EventIcon(next.kind, c.ink, Modifier.size(s.dp(112)))
                    Spacer(Modifier.width(s.dp(28)))
                    val text = "${distanceStep(next.distanceM)} ${next.label}"
                    BasicText(text, style = disp(fitSize(text, 624, 104), c.ink), softWrap = false)
                }
                Spacer(Modifier.height(s.dp(22)))
                Row(Modifier.padding(start = s.dp(140)), verticalAlignment = Alignment.CenterVertically) {
                    when {
                        ui.nextTold -> {
                            SpeakerIcon(c.told, Modifier.size(s.dp(42)))
                            Spacer(Modifier.width(s.dp(14)))
                            BasicText(if (ui.nextConfirmed) "깜빡이와 함께 알렸어요" else "뒷좌석에 미리 알렸어요", style = sans(42, c.told))
                        }
                        next.kind.phrase == null -> BasicText("쉬어 갈 수 있는 곳이에요", style = sans(42, c.ink2, FontWeight.Medium))
                        ui.armed -> BasicText("1.5초 전에 뒷좌석에 알려요", style = sans(42, c.ink2, FontWeight.Medium))
                        else -> BasicText("뒷좌석이 비어 알림을 쉬어요", style = sans(42, c.ink2, FontWeight.Medium))
                    }
                }
            } else {
                BasicText(if (driving) "곧은 길이에요" else "경로를 기다려요", style = disp(104, c.ink))
                Spacer(Modifier.height(s.dp(22)))
                BasicText(
                    if (driving) "회전과 덜컹 구간이 다가오면 알려요" else "내비 회전 안내로 미리 알려요",
                    style = sans(42, c.ink2, FontWeight.Medium),
                )
            }
        }

        Box(Modifier.offset(s.dp(rx), s.dp(440)).width(s.dp(rw)).height(s.dp(3)).background(c.rule))

        Column(Modifier.offset(s.dp(rx), s.dp(470)).width(s.dp(rw))) {
            if (driving) {
                ui.cautions.forEach { e ->
                    Row(Modifier.height(s.dp(100)), verticalAlignment = Alignment.CenterVertically) {
                        BasicText(distanceText(e.distanceM), style = sans(54, c.ink, FontWeight.Bold, tabular = true), modifier = Modifier.width(s.dp(236)))
                        BasicText(rowText(e), style = sans(48, c.ink))
                    }
                }
            } else {
                Spacer(Modifier.height(s.dp(26)))
                Box(
                    Modifier.clickable(onClick = onStartDemo)
                        .background(c.water, RoundedCornerShape(s.dp(16)))
                        .padding(horizontal = s.dp(48), vertical = s.dp(26))
                ) { BasicText("시험 주행 시작", style = sans(48, c.onWater)) }
                Spacer(Modifier.height(s.dp(20)))
                BasicText("저장된 주행으로 알림과 흔들림을 보여 드려요", style = sans(36, c.ink2, FontWeight.Medium))
            }
        }

        // Status: air care, then the child
        Column(Modifier.offset(s.dp(rx), s.dp(800)).width(s.dp(rw))) {
            val (airHead, airSub) = airText(ui)
            BasicText(airHead, style = sans(48, c.ink))
            BasicText(airSub, style = sans(36, c.ink2, FontWeight.Medium))
            Spacer(Modifier.height(s.dp(44)))
            val child = ui.child ?: "뒷좌석"
            val rest = restText(ui.rest)
            if (driving && rest != null) {
                BasicText(rest.first, style = sans(48, c.ink))
                BasicText(rest.second, style = sans(36, if (ui.rest == RestState.LISTENING) c.told else c.ink2, FontWeight.Medium))
            } else if (!driving && !ui.armed) {
                // Parked with the profile's seat empty: say exactly what turns the alerts on
                val name = ui.childName ?: "아이"
                BasicText("뒷좌석 알림이 꺼져 있어요", style = sans(48, c.ink))
                BasicText("$name${josa(name)} ${ui.childSeat.ifEmpty { "뒷좌석" }}에 앉으면 켜져요", style = sans(36, c.told))
            } else {
                BasicText(
                    when { !driving -> "$child 탔어요"; ui.sway < 25 -> "$child 편안해요"; ui.sway < 50 -> "$child 조금 흔들려요"; else -> "$child 많이 흔들려요" },
                    style = sans(48, c.ink),
                )
                BasicText(if (ui.armed) "뒷좌석 알림 켜짐" else "${child}의 자리가 비어 있어요", style = sans(36, c.ink2, FontWeight.Medium))
            }
        }

        BasicText(
            "탑승자 설정", style = sans(40, c.told),
            modifier = Modifier.offset(s.dp(rx), s.dp(1108)).clickable(onClick = onSettings).padding(vertical = s.dp(12)),
        )
    }
}

private fun restText(r: RestState): Pair<String, String>? = when (r) {
    RestState.NONE -> null
    RestState.ASKED -> "쉬었다 갈까요?" to "휴게소가 다음 안내예요"
    RestState.LISTENING -> "쉬었다 갈까요?" to "“응” 하고 말하면 돼요"
    RestState.YES -> "휴게소에서 쉬어 가요" to "대답을 들었어요"
    RestState.NO_ANSWER -> "그대로 갈게요" to "대답이 없었어요"
    RestState.UNAVAILABLE -> "그대로 갈게요" to "음성 대답을 받을 수 없어요"
}

/** Korean subject particle: 이 after a final consonant, 가 otherwise. */
private fun josa(word: String): String {
    val last = word.lastOrNull() ?: return "이"
    return if (last in '가'..'힣' && (last - '가') % 28 != 0) "이" else "가"
}

/** Display size that keeps [text] on one line inside [widthPx]; Paperlogy runs about 0.92 em per Hangul syllable. */
private fun fitSize(text: String, widthPx: Int, maxPx: Int): Int {
    val ems = text.sumOf { ch -> when { ch == ' ' -> 0.3; ch.isDigit() || ch == '.' -> 0.62; ch.code < 128 -> 0.75; else -> 0.94 } }
    return minOf(maxPx, (widthPx / ems).toInt())
}

private fun airText(ui: UiState): Pair<String, String> = when (ui.air) {
    AirState.WAITING -> "공조 케어 대기 중" to "흔들리면 허락한 만큼 공조를 바꿔요"
    AirState.ANNOUNCED -> ui.airLine.ifEmpty { "공조를 확인하고 있어요" } to "말씀드린 뒤에 바꿔요"
    AirState.DONE -> (if ("바깥 공기" in ui.airApplied) "바깥 공기로 바꿨어요" else "공조를 조금 바꿨어요") to
        (ui.airArea + " " + ui.airApplied.joinToString(", ")).trim()
    AirState.FAILED -> "공조를 바꾸지 못했어요" to "차가 공조 요청을 받지 않았어요"
    AirState.NOT_NEEDED -> "공조는 그대로 둘게요" to "허락한 범위에서 바꿀 것이 없어요"
    AirState.ADVISED -> "공조 케어 안내만 했어요" to "“${AirCare.ADVICE}”"
    AirState.MANUAL -> "직접 바꾸신 대로 둘게요" to "이번 주행은 공조 케어를 쉬어요"
}

/** Headline distance steps by 100 m (static between steps). */
private fun distanceStep(m: Double): String = when {
    m < 100 -> "곧"
    m < 1000 -> "${(m / 100).toInt() * 100}m 앞"
    else -> "${"%.1f".format(m / 1000)}km 앞"
}

private fun distanceText(m: Double): String = if (m < 1000) "${(m / 10).toInt() * 10}m" else "${"%.1f".format(m / 1000)}km"

private fun rowText(e: RouteEvent): String = when (e.kind) {
    EventKind.RIGHT -> "오른쪽으로 돌아요"
    EventKind.LEFT -> "왼쪽으로 돌아요"
    EventKind.UTURN -> "유턴해요"
    EventKind.BUMP -> "${e.label}${josa(e.label)} 있어요"
    EventKind.REST -> "${e.label}에서 쉬어요"
    EventKind.OTHER -> e.label
}
