package ai.softride.app.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.tan

/**
 * 흔들림 지수 0–100.
 * Lateral accel (speed × heading rate, or speed² × tan(steer)/wheelbase when that is larger),
 * longitudinal accel (speed change) and brake pedal, weighted toward lateral.
 * Each channel is split into a 0.1–0.5 Hz band (the band that makes people sick) and a slow part;
 * the band is boosted. The index is the rolling mean of the last 5 minutes of 1-second samples.
 */
class SwayMeter(private val wheelbaseM: Float = 2.9f) {
    private class Band {
        private var lp1 = 0f; private var lp2 = 0f; private var slow = 0f
        /** Returns (|band part|, low-passed level). */
        fun step(x: Float, dt: Float): Pair<Float, Float> {
            lp1 += alpha(0.5f, dt) * (x - lp1)          // < 0.5 Hz
            lp2 += alpha(0.1f, dt) * (lp1 - lp2)        // < 0.1 Hz
            slow += alpha(0.5f, dt) * (abs(x) - slow)
            return abs(lp1 - lp2) to slow               // 0.1–0.5 Hz band, magnitude
        }
        private fun alpha(fc: Float, dt: Float) = (1 - exp(-2 * PI * fc * dt)).toFloat()
    }

    private val lat = Band()
    private val lon = Band()
    private var prevSpeed: Float? = null
    private var prevHeading: Float? = null

    private val seconds = ArrayDeque<Float>()   // 1-second samples, newest last
    private var secSum = 0f; private var secN = 0; private var secClock = 0f

    var instant = 0f; private set

    fun reset() {
        seconds.clear(); secSum = 0f; secN = 0; secClock = 0f; prevSpeed = null; prevHeading = null
    }

    /** dt in s, speed m/s, heading deg (null if unknown), front-wheel steering deg. */
    fun step(dt: Float, speed: Float, headingDeg: Float?, steeringDeg: Float, brake: Boolean): Float {
        val yaw = if (headingDeg != null && prevHeading != null) {
            var d = headingDeg - prevHeading!!
            if (d > 180) d -= 360; if (d < -180) d += 360
            Math.toRadians(d.toDouble()).toFloat() / dt
        } else 0f
        prevHeading = headingDeg
        val steer = Math.toRadians(steeringDeg.coerceIn(-35f, 35f).toDouble())
        val aLat = maxOf(abs(speed * yaw), abs(speed * speed * tan(steer).toFloat() / wheelbaseM)).coerceAtMost(8f)
        val aLon = prevSpeed?.let { abs(speed - it) / dt }?.coerceAtMost(6f) ?: 0f
        prevSpeed = speed

        val (latBand, latSlow) = lat.step(aLat, dt)
        val (lonBand, lonSlow) = lon.step(aLon, dt)
        val eLat = 0.5f * latSlow + 1.0f * latBand
        val eLon = 0.5f * lonSlow + 1.0f * lonBand
        val eBrake = if (brake && speed > 0.5f) 1f else 0f
        instant = ((1.0f * eLat + 0.5f * eLon + 0.8f * eBrake) / 4f * 100f).coerceIn(0f, 100f)

        secSum += instant; secN++; secClock += dt
        if (secClock >= 1f) {
            seconds.addLast(secSum / secN); secSum = 0f; secN = 0; secClock -= 1f
            while (seconds.size > WINDOW_S) seconds.removeFirst()
        }
        return instant
    }

    /** Rolling 5-minute index (shorter at the start of a trip). */
    val index: Int get() = if (seconds.isEmpty()) instant.toInt() else seconds.average().toInt()

    /** Mean of the last [n] seconds. */
    fun recent(n: Int): Float = seconds.takeLast(n).let { if (it.isEmpty()) instant else it.average().toFloat() }

    companion object { const val WINDOW_S = 300 }
}
