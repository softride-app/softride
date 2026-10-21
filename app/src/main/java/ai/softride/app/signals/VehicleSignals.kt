package ai.softride.app.signals

import ai.pleos.playground.navi.constants.CautionPointType
import ai.pleos.playground.navi.constants.TurnType

enum class Turn { OFF, LEFT, RIGHT }
enum class GearPos { PARK, REVERSE, NEUTRAL, DRIVE, UNKNOWN }

/** What the child should feel next. Only these kinds produce a cue. */
enum class EventKind(val phrase: String?, val side: Turn) {
    RIGHT("곧 오른쪽", Turn.RIGHT),
    LEFT("곧 왼쪽", Turn.LEFT),
    UTURN("곧 유턴", Turn.LEFT),
    BUMP("곧 덜컹", Turn.OFF),
    REST(null, Turn.OFF),
    OTHER(null, Turn.OFF);

    companion object {
        /** Direction from the TurnType name (navi-data 3.0.4 constants/TurnType.kt). Straight-ahead (_C) kinds give no cue. */
        fun of(t: TurnType): EventKind = when (t) {
            TurnType.TURN_RIGHT, TurnType.TURN_SHARP_R, TurnType.TURN_KEEP_R,
            TurnType.TURN_ENTER_IC_R, TurnType.TURN_EXIT_IC_R,
            TurnType.TURN_ENTER_UNDER_R, TurnType.TURN_AVOID_UNDER_R,
            TurnType.TURN_ENTER_OVER_R, TurnType.TURN_AVOID_OVER_R,
            TurnType.TURN_ENTER_ROUNDABOUT_02, TurnType.TURN_ENTER_ROUNDABOUT_03, TurnType.TURN_ENTER_ROUNDABOUT_04 -> RIGHT
            TurnType.TURN_LEFT, TurnType.TURN_SHARP_L, TurnType.TURN_KEEP_L,
            TurnType.TURN_ENTER_IC_L, TurnType.TURN_EXIT_IC_L,
            TurnType.TURN_ENTER_UNDER_L, TurnType.TURN_AVOID_UNDER_L,
            TurnType.TURN_ENTER_OVER_L, TurnType.TURN_AVOID_OVER_L,
            TurnType.TURN_ENTER_ROUNDABOUT_08, TurnType.TURN_ENTER_ROUNDABOUT_09, TurnType.TURN_ENTER_ROUNDABOUT_10 -> LEFT
            TurnType.TURN_UTURN, TurnType.TURN_ENTER_ROUNDABOUT_06 -> UTURN
            TurnType.TURN_ENTER_SERVICEAREA, TurnType.TURN_RESTAREA -> REST
            else -> OTHER
        }

        fun of(c: CautionPointType): EventKind = when (c) {
            CautionPointType.SPEED_BUMP, CautionPointType.RAILROAD_CROSSING -> BUMP
            else -> OTHER
        }
    }
}

/** An upcoming route event with the distance still to go. [tbt] = next-turn info, else a caution point. */
data class RouteEvent(val id: String, val kind: EventKind, val distanceM: Double, val label: String, val tbt: Boolean = false)

/**
 * What NaviHelper really lets us know ahead: the NEXT turn only (nearest TBT) plus caution points.
 * Later turns are dropped so neither the UI nor the cues claim more than the SDK gives.
 */
fun nextTbtPlusCautions(tbts: List<RouteEvent>, cautions: List<RouteEvent>): List<RouteEvent> =
    listOfNotNull(tbts.filter { it.distanceM > -20 }.minByOrNull { it.distanceM }) + cautions.filter { it.distanceM > -20 }

data class Snapshot(
    val speedMps: Float,
    val headingDeg: Float?,
    val brake: Boolean,
    val steeringDeg: Float,
    val turnSignal: Turn,
    val gear: GearPos,
    val rearLeft: Boolean,
    val rearRight: Boolean,
    val events: List<RouteEvent>,
    val destination: String?,
    val remainSec: Int?,
    val simulated: Boolean,
    val finished: Boolean = false,
)

/** One source of vehicle + route input. Real reads the car; Replay plays a bundled trip. */
interface VehicleSignals {
    fun start()
    fun stop()
    fun read(nowMs: Long): Snapshot
}
