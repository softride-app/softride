package ai.softride.app.engine

import ai.softride.app.signals.EventKind
import ai.softride.app.signals.RouteEvent
import ai.softride.app.signals.Turn

/**
 * 멀미 예보 timing. Time-to-event = distance ÷ speed; cue ~[leadS] before.
 * A turn signal in the same direction confirms a cued turn or advances a pending one.
 * At most one cue per [minGapMs].
 */
class CuePlanner(private val leadS: Float = 1.5f, private val minGapMs: Long = 8_000) {
    private val done = mutableSetOf<String>()
    private var lastCueMs = Long.MIN_VALUE / 2
    var lastCued: RouteEvent? = null; private set
    var confirmed = false; private set

    fun reset() { done.clear(); lastCueMs = Long.MIN_VALUE / 2; lastCued = null; confirmed = false }

    /** Returns the event to announce now, or null. Call [markCued] when the cue was actually played. */
    fun plan(nowMs: Long, speedMps: Float, events: List<RouteEvent>, signal: Turn): RouteEvent? {
        val last = lastCued
        if (last != null && signal != Turn.OFF && last.kind.side == signal) confirmed = true
        if (speedMps < 1f) return null
        for (e in events.sortedBy { it.distanceM }) {
            if (e.kind.phrase == null || e.id in done) continue
            val tte = e.distanceM / speedMps
            if (tte < 0.3) { done += e.id; continue }            // too late: skip silently
            val advanced = signal != Turn.OFF && signal == e.kind.side && e.distanceM < ADVANCE_M
            if (tte > leadS && !advanced) return null           // nearest cueable event is not due yet
            if (nowMs - lastCueMs < minGapMs) { done += e.id; return null }
            return e
        }
        return null
    }

    fun markCued(e: RouteEvent, nowMs: Long, signal: Turn) {
        done += e.id; lastCueMs = nowMs; lastCued = e
        confirmed = signal != Turn.OFF && signal == e.kind.side
    }

    /** The event was due but could not be played (focus busy): never play it late. */
    fun markSkipped(e: RouteEvent) { done += e.id }

    companion object { const val ADVANCE_M = 150.0 }
}

fun EventKind.isTurn() = this == EventKind.LEFT || this == EventKind.RIGHT || this == EventKind.UTURN
