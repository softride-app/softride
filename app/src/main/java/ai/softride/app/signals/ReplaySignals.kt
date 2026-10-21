package ai.softride.app.signals

import ai.pleos.playground.navi.constants.CautionPointType
import ai.pleos.playground.navi.constants.TurnType
import android.content.Context
import org.json.JSONObject

/**
 * Replay input for the demo: a bundled trip (timestamps with speed and heading, TBT list, caution points).
 * Live Virtual Console values still flow in: brake, steering, turn signal and gear come from the car,
 * and a live speed above walking pace overrides the recorded speed (so the console can speed the trip up).
 */
class ReplaySignals(context: Context, private val live: LiveVehicle, asset: String = "trip_demo.json") : VehicleSignals {
    private class Sample(val t: Double, val speed: Float, val heading: Float)
    private class Marker(val at: Double, val kind: EventKind, val id: String, val label: String)

    private val json = JSONObject(context.assets.open(asset).bufferedReader().use { it.readText() })
    val route: String = json.getString("route")
    private val destination = json.getString("destination")
    private val remainBase = json.getInt("remainBaseSec")
    private val row2 = json.optBoolean("row2Occupied", false)
    private val samples = json.getJSONArray("samples").let { a ->
        List(a.length()) { i -> a.getJSONObject(i).let { Sample(it.getDouble("t"), it.getDouble("speed").toFloat(), it.getDouble("heading").toFloat()) } }
    }
    private val markers = buildList {
        val tbt = json.getJSONArray("tbt")
        for (i in 0 until tbt.length()) tbt.getJSONObject(i).let {
            add(Marker(it.getDouble("at"), EventKind.of(TurnType.valueOf(it.getString("type"))), "tbt-$i", it.getString("description")))
        }
        val c = json.getJSONArray("cautions")
        for (i in 0 until c.length()) c.getJSONObject(i).let {
            add(Marker(it.getDouble("at"), EventKind.of(CautionPointType.valueOf(it.getString("type"))), "caution-$i", it.getString("description")))
        }
    }

    private var t = 0.0
    private var travelled = 0.0
    private var lastMs = 0L

    override fun start() { t = 0.0; travelled = 0.0; lastMs = 0L }
    override fun stop() {}

    override fun read(nowMs: Long): Snapshot {
        val dt = if (lastMs == 0L) 0.0 else (nowMs - lastMs) / 1000.0
        lastMs = nowMs
        t += dt
        val (recSpeed, heading) = at(t)
        val liveSpeed = live.speedMps ?: 0f
        val speed = if (liveSpeed > 1.5f) liveSpeed else recSpeed
        travelled += speed * dt
        val finished = t >= samples.last().t
        return Snapshot(
            speedMps = if (finished) 0f else speed,
            headingDeg = heading,
            brake = live.brake,
            steeringDeg = live.steeringDeg,
            turnSignal = live.turn,
            gear = if (finished) GearPos.PARK else GearPos.DRIVE,
            rearLeft = row2 || live.rearLeft,
            rearRight = row2 || live.rearRight,
            events = markers.map { RouteEvent(it.id, it.kind, it.at - travelled, it.label, tbt = it.id.startsWith("tbt")) }
                .partition { it.tbt }.let { (t, c) -> nextTbtPlusCautions(t, c) },
            destination = destination,
            remainSec = (remainBase - t).toInt().coerceAtLeast(0),
            simulated = true,
            finished = finished,
        )
    }

    private fun at(time: Double): Pair<Float, Float> {
        val i = samples.indexOfFirst { it.t >= time }
        if (i <= 0) return (if (i == 0) samples[0] else samples.last()).let { it.speed to it.heading }
        val a = samples[i - 1]; val b = samples[i]
        val f = ((time - a.t) / (b.t - a.t)).toFloat()
        var dh = b.heading - a.heading
        if (dh > 180) dh -= 360; if (dh < -180) dh += 360
        return (a.speed + (b.speed - a.speed) * f) to ((a.heading + dh * f + 360) % 360)
    }
}
