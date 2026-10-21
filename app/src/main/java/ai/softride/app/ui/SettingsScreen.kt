package ai.softride.app.ui

import ai.softride.app.engine.Profile
import ai.softride.app.engine.Seat
import ai.softride.app.engine.Sensitivity
import ai.softride.app.engine.Settings
import ai.softride.app.engine.SettingsStore
import ai.softride.app.engine.Voice
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight

/**
 * 탑승자 설정 (mockup A_05). Editable only while parked; while driving everything is shown but locked.
 * Changes are saved immediately (no save button) and stay in the car.
 */
@Composable
fun SettingsScreen(st: Settings, locked: Boolean, store: SettingsStore, onClose: () -> Unit) {
    val c = LocalPalette.current
    val s = LocalScale.current
    val child = st.child ?: return
    fun edit(f: (Profile) -> Profile) { if (!locked) store.updateChild(f) }

    Box(Modifier.fillMaxSize().background(c.ground)) {
        BasicText("탑승자 설정", style = disp(96, c.ink), modifier = Modifier.offset(s.dp(56), s.dp(40)))
        if (locked) Row(Modifier.offset(s.dp(900), s.dp(48)).width(s.dp(724)), horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically) {
            LockIcon(c.ink, Modifier.size(s.dp(56)))
            Spacer(Modifier.width(s.dp(20)))
            Column(horizontalAlignment = Alignment.End) {
                BasicText("운전 중에는 보기만 할 수 있어요", style = sans(44, c.ink))
                BasicText("차가 멈추면 바꿀 수 있어요", style = sans(36, c.ink2, FontWeight.Medium))
            }
        }

        // Profiles: selected = filled with water
        Column(Modifier.offset(s.dp(56), s.dp(190)).width(s.dp(444))) {
            st.profiles.forEachIndexed { i, p ->
                val sel = i == st.selected
                Column(
                    Modifier.fillMaxWidth().height(s.dp(200))
                        .then(if (sel) Modifier.background(c.water, RoundedCornerShape(s.dp(16)))
                              else Modifier.border(s.dp(3), c.rule, RoundedCornerShape(s.dp(16))))
                        .clickable(enabled = !locked) { store.update { it.copy(selected = i) } }
                        .padding(horizontal = s.dp(40), vertical = s.dp(30)),
                ) {
                    val nameStyle = disp(72, if (sel) c.onWater else c.ink)
                    if (sel && !locked) BasicTextField(
                        value = p.name, singleLine = true, textStyle = nameStyle, cursorBrush = SolidColor(c.onWater),
                        onValueChange = { v -> if (v.isNotBlank() && v.length <= 6) edit { it.copy(name = v.trim()) } },
                    ) else BasicText(p.name, style = nameStyle)
                    Spacer(Modifier.height(s.dp(10)))
                    BasicText("${p.age}세, ${p.seat.label}", style = sans(36, if (sel) c.onWater2 else c.ink2, FontWeight.Medium))
                }
                Spacer(Modifier.height(s.dp(24)))
            }
            Spacer(Modifier.height(s.dp(40)))
            Option("돌아가기", selected = false, enabled = true, onClick = onClose)
        }

        // Profile rows
        Column(Modifier.offset(s.dp(548), s.dp(176)).width(s.dp(1076))) {
            SettingRow("나이") {
                if (!locked) { Option("−", false, child.age > 3) { edit { it.copy(age = it.age - 1) } }; Spacer(Modifier.width(s.dp(28))) }
                BasicText("${child.age}세", style = disp(72, c.ink), modifier = Modifier.width(s.dp(170)))
                if (!locked) Option("+", false, child.age < 15) { edit { it.copy(age = it.age + 1) } }
            }
            Rule()
            SettingRow("자리") { Seat.entries.forEach { v -> Option(v.label, child.seat == v, !locked) { edit { it.copy(seat = v) } }; Gap() } }
            Rule()
            SettingRow("멀미 민감도") { Sensitivity.entries.forEach { v -> Option(v.label, child.sensitivity == v, !locked) { edit { it.copy(sensitivity = v) } }; Gap() } }
            Rule()
            SettingRow("알림 목소리") { Voice.entries.forEach { v -> Option(v.label, child.voice == v, !locked) { edit { it.copy(voice = v) } }; Gap() } }
            Rule()
        }

        // Cue + rest toggles
        Column(Modifier.offset(s.dp(548), s.dp(640)).width(s.dp(500))) {
            ToggleRow("미리 알림 소리", "“곧 오른쪽”, “곧 덜컹”처럼\n1.5초 먼저 말해요", st.cuesOn, !locked) { v -> store.update { it.copy(cuesOn = v) } }
            Spacer(Modifier.height(s.dp(36)))
            ToggleRow("휴식 제안", "흔들림이 쌓이면 쉴 곳을 알려요", st.restOn, !locked) { v -> store.update { it.copy(restOn = v) } }
        }

        // 공조 케어 허용 범위
        Column(Modifier.offset(s.dp(1100), s.dp(640)).width(s.dp(524))) {
            BasicText("공조 케어 허용 범위", style = sans(44, c.ink))
            Spacer(Modifier.height(s.dp(16)))
            Row(Modifier.fillMaxWidth().height(s.dp(84)), verticalAlignment = Alignment.CenterVertically) {
                BasicText("외기 유입 켜기", style = sans(40, c.ink), modifier = Modifier.weight(1f))
                Toggle(st.air.freshAir, !locked) { v -> store.update { it.copy(air = it.air.copy(freshAir = v)) } }
            }
            Rule()
            Row(Modifier.fillMaxWidth().height(s.dp(96)), verticalAlignment = Alignment.CenterVertically) {
                BasicText("온도", style = sans(40, c.ink), modifier = Modifier.weight(1f))
                Option("그대로", st.air.tempDownC == 0f, !locked) { store.update { it.copy(air = it.air.copy(tempDownC = 0f)) } }
                Gap()
                Option("−1℃까지", st.air.tempDownC == 1f, !locked) { store.update { it.copy(air = it.air.copy(tempDownC = 1f)) } }
            }
            Rule()
            Row(Modifier.fillMaxWidth().height(s.dp(96)), verticalAlignment = Alignment.CenterVertically) {
                BasicText("바람", style = sans(40, c.ink), modifier = Modifier.weight(1f))
                Option("그대로", st.air.fanUp == 0, !locked) { store.update { it.copy(air = it.air.copy(fanUp = 0)) } }
                Gap()
                Option("+1단계까지", st.air.fanUp == 1, !locked) { store.update { it.copy(air = it.air.copy(fanUp = 1)) } }
            }
            Rule()
            Spacer(Modifier.height(s.dp(20)))
            Row(verticalAlignment = Alignment.CenterVertically) {
                SpeakerIcon(c.told, Modifier.size(s.dp(40)))
                Spacer(Modifier.width(s.dp(12)))
                BasicText("실행 전 음성으로 먼저 알려요", style = sans(36, c.told))
            }
        }
    }
}

@Composable
private fun SettingRow(label: String, content: @Composable () -> Unit) {
    val c = LocalPalette.current
    val s = LocalScale.current
    Row(Modifier.fillMaxWidth().height(s.dp(104)), verticalAlignment = Alignment.CenterVertically) {
        BasicText(label, style = sans(44, c.ink), modifier = Modifier.width(s.dp(250)))
        content()
    }
}

@Composable private fun Rule() {
    val c = LocalPalette.current; val s = LocalScale.current
    Box(Modifier.fillMaxWidth().height(s.dp(3)).background(c.rule))
}

@Composable private fun Gap() = Spacer(Modifier.width(LocalScale.current.dp(16)))

/** Segmented option: selected = filled with water. Disabled options keep their look (view-only), they just don't react. */
@Composable
private fun Option(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val c = LocalPalette.current
    val s = LocalScale.current
    val shape = RoundedCornerShape(s.dp(14))
    Box(
        Modifier.then(if (selected) Modifier.background(c.water, shape) else Modifier.border(s.dp(3), c.rule, shape))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = s.dp(24), vertical = s.dp(14)),
    ) { BasicText(label, style = sans(36, if (selected) c.onWater else c.ink, FontWeight.Medium)) }
}

@Composable
private fun ToggleRow(title: String, desc: String, on: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val c = LocalPalette.current
    Row(verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            BasicText(title, style = sans(44, c.ink))
            BasicText(desc, style = sans(34, c.ink2, FontWeight.Medium))
        }
        Toggle(on, enabled, onChange)
    }
}

@Composable
private fun Toggle(on: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val c = LocalPalette.current
    val s = LocalScale.current
    Box(
        Modifier.width(s.dp(120)).height(s.dp(64)).background(if (on) c.water else c.rule, RoundedCornerShape(s.dp(32)))
            .clickable(enabled = enabled) { onChange(!on) }.padding(s.dp(8)),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) { Box(Modifier.size(s.dp(48)).background(c.onWater, CircleShape)) }
}

@Composable
private fun LockIcon(color: androidx.compose.ui.graphics.Color, modifier: Modifier) = Canvas(modifier) {
    val u = size.minDimension / 100f
    drawRoundRect(color, topLeft = androidx.compose.ui.geometry.Offset(16 * u, 44 * u),
        size = androidx.compose.ui.geometry.Size(68 * u, 52 * u), cornerRadius = androidx.compose.ui.geometry.CornerRadius(10 * u))
    val shackle = Path().apply { moveTo(30 * u, 46 * u); lineTo(30 * u, 30 * u); cubicTo(30 * u, 4 * u, 70 * u, 4 * u, 70 * u, 30 * u); lineTo(70 * u, 46 * u) }
    drawPath(shackle, color, style = Stroke(width = 11 * u, cap = StrokeCap.Round))
}
