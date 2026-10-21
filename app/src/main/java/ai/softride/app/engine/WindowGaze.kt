package ai.softride.app.engine

import ai.softride.app.signals.RouteEvent
import kotlin.math.abs

/**
 * 창밖 시선 안내: after a rough stretch (10-s sway ≥ [swayAt] within the last [roughWindowMs]), once the road
 * runs straight (heading within ±[headingTolDeg] for [straightMs]) and no turn info (TBT) lies within [clearM],
 * it is time to say "창밖 먼 곳을 봐요". At most once per [intervalMs]; the caller still applies the spoken-prompt gap.
 */
class WindowGaze(
    private val swayAt: Float = 50f, private val straightMs: Long = 15_000, private val headingTolDeg: Float = 5f,
    private val clearM: Double = 400.0, private val intervalMs: Long = 10 * 60_000, private val roughWindowMs: Long = 60_000,
) {
    private var refHeading: Float? = null
    private var straightSince = 0L
    private var roughAt = Long.MIN_VALUE / 2
    private var lastSaid = Long.MIN_VALUE / 2

    /** Call every tick. Returns true when the line is due now. */
    fun due(nowMs: Long, headingDeg: Float?, sway10s: Float, events: List<RouteEvent>): Boolean {
        if (sway10s >= swayAt) roughAt = nowMs
        val ref = refHeading
        if (headingDeg == null) { refHeading = null; straightSince = nowMs }
        else if (ref == null || abs(((headingDeg - ref + 540) % 360) - 180) > headingTolDeg) { refHeading = headingDeg; straightSince = nowMs }
        return nowMs - roughAt <= roughWindowMs && nowMs - straightSince >= straightMs &&
            events.none { it.tbt && it.distanceM < clearM } && nowMs - lastSaid >= intervalMs
    }

    fun said(nowMs: Long) { lastSaid = nowMs }

    /** Per trip: forget the road so far (the 10-minute limit is kept across trips). */
    fun newTrip() { refHeading = null; straightSince = 0L; roughAt = Long.MIN_VALUE / 2 }

    companion object { const val PHRASE = "창밖 먼 곳을 봐요" }
}
