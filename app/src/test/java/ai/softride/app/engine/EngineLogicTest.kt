package ai.softride.app.engine

import ai.pleos.playground.navi.constants.TurnType
import ai.softride.app.signals.EventKind
import ai.softride.app.signals.RouteEvent
import ai.softride.app.signals.Turn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineLogicTest {

    @Test fun turnTypeMapsToTheSideTheCarTurns() {
        val expect = mapOf(
            TurnType.TURN_RIGHT to EventKind.RIGHT, TurnType.TURN_LEFT to EventKind.LEFT,
            TurnType.TURN_SHARP_R to EventKind.RIGHT, TurnType.TURN_SHARP_L to EventKind.LEFT,
            TurnType.TURN_KEEP_R to EventKind.RIGHT, TurnType.TURN_KEEP_L to EventKind.LEFT,
            TurnType.TURN_UTURN to EventKind.UTURN,
            TurnType.TURN_ENTER_ROUNDABOUT_03 to EventKind.RIGHT, TurnType.TURN_ENTER_ROUNDABOUT_09 to EventKind.LEFT,
            TurnType.TURN_ENTER_ROUNDABOUT_06 to EventKind.UTURN, TurnType.TURN_ENTER_ROUNDABOUT_12 to EventKind.OTHER,
            TurnType.TURN_EXIT_ROUNDABOUT to EventKind.OTHER,
            TurnType.TURN_ENTER_IC_R to EventKind.RIGHT, TurnType.TURN_EXIT_IC_L to EventKind.LEFT, TurnType.TURN_ENTER_IC_C to EventKind.OTHER,
            TurnType.TURN_ENTER_UNDER_L to EventKind.LEFT, TurnType.TURN_AVOID_OVER_R to EventKind.RIGHT,
            TurnType.TURN_ENTER_SERVICEAREA to EventKind.REST, TurnType.TURN_RESTAREA to EventKind.REST,
            TurnType.TURN_STRAIGHT to EventKind.OTHER, TurnType.TURN_ARRIVE to EventKind.OTHER,
        )
        expect.forEach { (t, k) -> assertEquals(t.name, k, EventKind.of(t)) }
        // every _R/_L kind cues the same side as its suffix
        TurnType.entries.filter { it.name.endsWith("_R") }.forEach { assertEquals(it.name, Turn.RIGHT, EventKind.of(it).side) }
        TurnType.entries.filter { it.name.endsWith("_L") }.forEach { assertEquals(it.name, Turn.LEFT, EventKind.of(it).side) }
    }

    private fun turn(d: Double, id: String = "r") = RouteEvent(id, EventKind.RIGHT, d, "우회전")

    @Test fun cueFiresAboutOneAndHalfSecondsBefore() {
        val p = CuePlanner()
        assertNull(p.plan(0, 10f, listOf(turn(20.0)), Turn.OFF))     // 2.0 s away
        assertNotNull(p.plan(0, 10f, listOf(turn(14.0)), Turn.OFF))  // 1.4 s away
    }

    @Test fun eightSecondsBetweenCuesAndSignalAdvances() {
        val p = CuePlanner()
        p.markCued(turn(10.0, "a"), 0, Turn.OFF)
        assertNull(p.plan(5_000, 10f, listOf(turn(10.0, "b")), Turn.OFF))   // within 8 s: skipped
        assertNotNull(p.plan(9_000, 10f, listOf(turn(35.0, "c")), Turn.RIGHT))  // 3.5 s away: signal advances it
        assertNull(p.plan(9_000, 10f, listOf(turn(35.0, "d")), Turn.LEFT))      // other side: no
        assertNull(p.plan(9_000, 10f, listOf(turn(60.0, "e")), Turn.RIGHT))     // 6 s away: normal 1.5 s timing
        assertNull(p.plan(9_000, 30f, listOf(turn(140.0, "f")), Turn.RIGHT))    // < 150 m but 4.7 s away
    }

    @Test fun otherPromptsKeepEightSecondsFromTurnCues() {
        val p = CuePlanner()
        assertTrue(p.quiet(0, 10f, listOf(turn(200.0))))           // turn cue due in 18.5 s
        assertTrue(!p.quiet(0, 10f, listOf(turn(80.0))))           // turn cue due in 6.5 s: wait
        p.markSpoken(20_000)
        assertTrue(!p.quiet(25_000, 10f, emptyList()))             // 5 s after another prompt
        assertNull(p.plan(25_000, 10f, listOf(turn(14.0, "g")), Turn.OFF))  // a turn cue keeps the gap too
        assertTrue(p.quiet(28_100, 10f, emptyList()))
    }

    @Test fun windowGazeOnlyOnAStraightAfterARoughStretchOncePerTenMinutes() {
        val g = WindowGaze()
        val far = listOf(RouteEvent("t", EventKind.RIGHT, 900.0, "우회전", tbt = true))
        val near = listOf(RouteEvent("t", EventKind.RIGHT, 300.0, "우회전", tbt = true))
        // winding and rough for 20 s: never due
        for (i in 0..200) assertTrue(!g.due(i * 100L, if (i % 40 < 20) 20f else 340f, 60f, far))
        // straight from 20.1 s, calm: due once 15 s have passed
        assertTrue(!g.due(20_100, 0f, 10f, far))
        assertTrue(!g.due(34_000, 0f, 10f, far))
        assertTrue(!g.due(36_000, 0f, 10f, near))              // a turn 300 m ahead: wait
        assertTrue(g.due(36_000, 2f, 10f, far))
        g.said(36_000)
        assertTrue(!g.due(37_000, 1f, 10f, far))               // said: not again within 10 min
        // straight but never rough: not due
        val calm = WindowGaze()
        for (i in 0..300) assertTrue(!calm.due(i * 100L, 0f, 20f, far))
    }

    @Test fun swayRisesWithSteeringAndStaysLowWhenStraight() {
        val calm = SwayMeter(); val curvy = SwayMeter()
        repeat(100) { calm.step(0.1f, 15f, 0f, 0f, false); curvy.step(0.1f, 15f, 0f, 8f, false) }
        assertEquals(0, calm.index)
        assertTrue("curvy ${curvy.index}", curvy.index > 40)
    }
}
