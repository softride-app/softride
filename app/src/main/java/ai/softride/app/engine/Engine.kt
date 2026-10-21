package ai.softride.app.engine

import ai.softride.app.signals.EventKind
import ai.softride.app.signals.GearPos
import ai.softride.app.signals.LiveVehicle
import ai.softride.app.signals.LiveVehicle.Companion.TAG
import ai.softride.app.signals.RealSignals
import ai.softride.app.signals.ReplaySignals
import ai.softride.app.signals.RouteEvent
import ai.softride.app.signals.Snapshot
import ai.softride.app.signals.VehicleSignals
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToInt

enum class Screen { IDLE, DRIVING, REPORT }

data class TripReport(
    val route: String, val durationS: Int, val samples: List<Float>,
    val mostFromS: Int, val mostToS: Int, val avg: Int, val score: Int,
    val cues: Int, val airCares: Int, val rests: Int, val child: String?,
)

data class UiState(
    val screen: Screen = Screen.IDLE,
    val parked: Boolean = true,
    val sway: Int = 0,
    val next: RouteEvent? = null,
    val nextTold: Boolean = false,
    val nextConfirmed: Boolean = false,
    val cautions: List<RouteEvent> = emptyList(),
    val destination: String? = null,
    val remainMin: Int? = null,
    val air: AirState = AirState.WAITING,
    val airApplied: List<String> = emptyList(),
    val airArea: String = "",
    val airLine: String = "",
    val childName: String? = null,
    val childSeat: String = "",
    val child: String? = null,
    val armed: Boolean = false,
    val simulated: Boolean = false,
    val rest: RestState = RestState.NONE,
    val report: TripReport? = null,
)

/** Runs the 10 Hz loop: input → sway → cues → air care → rest → trip record. Lives as long as the foreground service. */
class Engine(private val context: Context) {
    val settings = SettingsStore(context)
    val live = LiveVehicle(context)
    private val real = RealSignals(context, live)
    private var replay: ReplaySignals? = null
    private val signals: VehicleSignals get() = replay ?: real

    val speaker = Speaker(context)
    private val sway = SwayMeter()
    private val planner = CuePlanner()
    private val gaze = WindowGaze()
    /** Every non-turn prompt goes through here so it counts for the 8-s gap between spoken prompts. */
    private fun prompt(text: String, withEarcon: Boolean = true, onDone: (() -> Unit)? = null): Boolean =
        speaker.cue(text, withEarcon, onDone).also { if (it) planner.markSpoken(System.currentTimeMillis()) }
    private val listener = Listener(context) { rests++; prompt("좋아요, 휴게소에서 쉬어 가요", withEarcon = false) }
    val air = AirCare(
        { if (live.ready) live.vehicle else null },
        { context.checkSelfPermission(CONTROL_CLIMATE) == android.content.pm.PackageManager.PERMISSION_GRANTED },
        { prompt(it) },
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    private var thread: HandlerThread? = null
    private lateinit var handler: Handler
    private var lastMs = 0L
    private var tripActive = false
    private val tripSamples = mutableListOf<Float>()
    private var secAcc = 0f; private var secN = 0
    private var cues = 0; private var rests = 0; private var restAsked = false
    private var shownSway = 0; private var lastStepMs = 0L; private var lastUiMs = 0L
    private var report: TripReport? = null
    private var route = "주행"

    private companion object { const val CONTROL_CLIMATE = "pleos.car.permission.CONTROL_CAR_CLIMATE" }

    private val childLabel: String? get() = settings.value.child?.let { "${it.name}(${it.age}세)" }

    fun start() {
        if (thread != null) return
        live.start(); real.start(); speaker.start(); listener.start()
        thread = HandlerThread("softride-engine").also { it.start(); handler = Handler(it.looper) }
        handler.post(::tick)
    }

    fun stop() {
        thread?.quitSafely(); thread = null
        real.stop(); live.stop(); speaker.stop(); listener.stop()
    }

    /** Parked-only: start the bundled demo trip. */
    fun startReplay() = handler.post {
        replay = ReplaySignals(context, live).also { it.start(); route = it.route }
        endTrip(build = false)
    }

    fun requestTestRoute() = real.requestTestRoute()

    private fun tick() {
        val now = System.currentTimeMillis()
        val dt = if (lastMs == 0L) 0.1f else ((now - lastMs) / 1000f).coerceIn(0.02f, 0.5f)
        lastMs = now
        runCatching { step(now, dt) }.onFailure { Log.e(TAG, "tick", it) }
        handler.postDelayed(::tick, 100)
    }

    private fun armed(s: Snapshot): Boolean {
        val child = settings.value.child ?: return false
        return if (child.seat == Seat.LEFT) s.rearLeft else s.rearRight
    }

    private fun step(now: Long, dt: Float) {
        val s = signals.read(now)
        val moving = if (s.simulated) !s.finished else (s.gear != GearPos.PARK && s.gear != GearPos.UNKNOWN && s.speedMps > 0.5f)
        if (moving && !tripActive) beginTrip(now, s)
        if (tripActive && !moving && (s.finished || s.gear == GearPos.PARK)) {
            endTrip(build = true)
            if (s.finished) replay = null
        }

        if (tripActive) {
            val set = settings.value
            val sens = set.child?.sensitivity ?: Sensitivity.MID
            sway.step(dt, s.speedMps, s.headingDeg, s.steeringDeg, s.brake)
            secAcc += sway.instant; secN++
            if (secN >= 10) { tripSamples += secAcc / secN; secAcc = 0f; secN = 0 }

            if (set.cuesOn && armed(s)) planner.plan(now, s.speedMps, s.events, s.turnSignal)?.let { e ->
                Log.i(TAG, "cue for ${e.id} ${e.kind} '${e.label}' at ${"%.0f".format(e.distanceM)}m, ${"%.1f".format(s.speedMps * 3.6f)}km/h")
                if (speaker.cue(e.kind.phrase!!)) { planner.markCued(e, now, s.turnSignal); cues++ } else planner.markSkipped(e)
            }
            // Non-turn prompts: never within 8 s of another prompt or of a coming turn cue, never over the mic.
            fun quiet() = planner.quiet(System.currentTimeMillis(), s.speedMps, s.events) &&
                listener.state != RestState.LISTENING && air.state != AirState.ANNOUNCED   // air care speaks after its async survey
            val gazeDue = gaze.due(now, s.headingDeg, sway.recent(10), s.events)
            if (gazeDue && quiet() && set.cuesOn && armed(s)) {
                Log.i(TAG, "window gaze: straight 15 s after sway ≥ 50, no turn within 400 m")
                if (prompt(WindowGaze.PHRASE)) gaze.said(now)
            } else if (quiet() && air.state == AirState.WAITING && sway.recent(10) >= sens.airAt) air.trigger()
            // Rest: only when the NEXT TBT is a service area / rest area and sway has piled up; asked once, answer by voice.
            val rest = s.events.firstOrNull { it.tbt && it.kind == EventKind.REST && it.distanceM < 3_000 }
            if (quiet() && set.restOn && !restAsked && rest != null && sway.index >= sens.restAt) {
                val m100 = (rest.distanceM / 100).roundToInt() * 100
                val ahead = if (m100 < 1_000) "${m100}미터" else "${"%.1f".format(rest.distanceM / 1000).removeSuffix(".0")}킬로미터"
                if (prompt("$ahead 앞 휴게소에서 쉬었다 갈까요?", withEarcon = false) { listener.listen() }) {
                    restAsked = true; listener.asked()   // 휴식 counts only a "yes"
                }
            }
            if (now - lastStepMs >= 5_000) {   // stepped, at most every 5 s
                shownSway = sway.index; lastStepMs = now
                Log.i(TAG, "sway index $shownSway, 10 s ${"%.0f".format(sway.recent(10))}")
            }
        }

        if (now - lastUiMs >= 1_000) { lastUiMs = now; publish(s) }
    }

    private fun publish(s: Snapshot) {
        val next = s.events.firstOrNull { it.tbt }
        _ui.value = UiState(
            screen = when { tripActive -> Screen.DRIVING; report != null -> Screen.REPORT; else -> Screen.IDLE },
            parked = !tripActive && (s.simulated || s.gear == GearPos.PARK || s.gear == GearPos.UNKNOWN),
            sway = shownSway,
            next = next,
            nextTold = next != null && planner.lastCued?.id == next.id,
            nextConfirmed = planner.confirmed,
            cautions = s.events.filter { !it.tbt && it.kind == EventKind.BUMP && it.distanceM >= 10 }.sortedBy { it.distanceM }.take(2),
            destination = s.destination,
            remainMin = s.remainSec?.let { (it / 60.0).roundToInt() },
            air = air.state, airApplied = air.applied, airArea = air.areaLabel, airLine = air.line,
            child = childLabel, childName = settings.value.child?.name, childSeat = settings.value.child?.seat?.label ?: "",
            armed = armed(s),
            simulated = s.simulated, rest = listener.state, report = report,
        )
    }

    private fun beginTrip(now: Long, s: Snapshot) {
        tripActive = true
        sway.reset(); planner.reset(); listener.reset(); tripSamples.clear(); secAcc = 0f; secN = 0
        cues = 0; rests = 0; restAsked = false; shownSway = 0; lastStepMs = now; report = null
        if (!s.simulated) route = s.destination?.let { "$it 가는 길" } ?: "이번 주행"
        air.newTrip(settings.value.air)
        gaze.newTrip()
        speaker.voice = settings.value.child?.voice ?: Voice.WARM
        speaker.warmUp()
        Log.i(TAG, "trip start (simulated=${s.simulated}, speed via ${live.speedSource})")
    }

    private fun endTrip(build: Boolean) {
        if (!tripActive) return
        tripActive = false
        if (!build || tripSamples.size < 5) return
        val n = tripSamples.size
        val w = maxOf(10, n / 8).coerceAtMost(n)
        var best = 0; var bestSum = -1f
        for (i in 0..n - w) { val sum = tripSamples.subList(i, i + w).sum(); if (sum > bestSum) { bestSum = sum; best = i } }
        val avg = tripSamples.average().roundToInt()
        report = TripReport(
            route = route, durationS = n, samples = tripSamples.toList(), mostFromS = best, mostToS = best + w,
            avg = avg, score = (100 - avg).coerceIn(0, 100), cues = cues,
            airCares = if (air.applied.isNotEmpty()) 1 else 0,   // only after a setHvac* call succeeded
            rests = rests, child = childLabel,
        )
        Log.i(TAG, "trip end: $n s, avg $avg, cues $cues")
    }
}
