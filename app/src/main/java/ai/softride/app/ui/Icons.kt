package ai.softride.app.ui

import ai.softride.app.signals.EventKind
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale

/** Authored icons on a 100-unit grid, round caps, one stroke weight. */
@Composable
fun EventIcon(kind: EventKind, color: Color, modifier: Modifier) = Canvas(modifier) {
    val u = size.minDimension / 100f
    val stroke = Stroke(width = 16f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val mirror = kind == EventKind.LEFT || kind == EventKind.UTURN
    scale(if (mirror) -1f else 1f, 1f) {
        val p = Path()
        when (kind) {
            EventKind.BUMP -> {
                p.moveTo(8 * u, 72 * u); p.lineTo(26 * u, 72 * u)
                p.cubicTo(36 * u, 72 * u, 38 * u, 40 * u, 50 * u, 40 * u)
                p.cubicTo(62 * u, 40 * u, 64 * u, 72 * u, 74 * u, 72 * u); p.lineTo(92 * u, 72 * u)
            }
            EventKind.UTURN -> {
                p.moveTo(72 * u, 92 * u); p.lineTo(72 * u, 40 * u)
                p.cubicTo(72 * u, 10 * u, 28 * u, 10 * u, 28 * u, 40 * u); p.lineTo(28 * u, 70 * u)
                p.moveTo(12 * u, 56 * u); p.lineTo(28 * u, 72 * u); p.lineTo(44 * u, 56 * u)
            }
            else -> {   // right turn (mirrored for left)
                p.moveTo(22 * u, 94 * u); p.lineTo(22 * u, 52 * u)
                p.cubicTo(22 * u, 36 * u, 32 * u, 28 * u, 48 * u, 28 * u); p.lineTo(84 * u, 28 * u)
                p.moveTo(66 * u, 10 * u); p.lineTo(84 * u, 28 * u); p.lineTo(66 * u, 46 * u)
            }
        }
        drawPath(p, color, style = stroke)
    }
}

@Composable
fun SpeakerIcon(color: Color, modifier: Modifier) = Canvas(modifier) {
    val u = size.minDimension / 100f
    val body = Path().apply {
        moveTo(12 * u, 38 * u); lineTo(30 * u, 38 * u); lineTo(52 * u, 18 * u); lineTo(52 * u, 82 * u)
        lineTo(30 * u, 62 * u); lineTo(12 * u, 62 * u); close()
    }
    drawPath(body, color)
    val wave = Path().apply { moveTo(68 * u, 34 * u); quadraticBezierTo(80 * u, 50 * u, 68 * u, 66 * u) }
    drawPath(wave, color, style = Stroke(width = 9 * u, cap = StrokeCap.Round))
}
