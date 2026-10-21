package ai.softride.app.signals

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.data.CautionPoint
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.util.Log

/**
 * Real input: live car values + NaviHelper next-turn (TBT) and caution-point callbacks.
 * Distances arrive in bursts; between updates they are dead-reckoned with the current speed.
 */
class RealSignals(context: Context, private val live: LiveVehicle) : VehicleSignals {
    private val navi = NaviHelper(context.applicationContext)
    private data class Reported(val events: List<RouteEvent>, val atMs: Long)
    @Volatile private var tbt = Reported(emptyList(), 0)
    @Volatile private var cautions = Reported(emptyList(), 0)
    @Volatile private var dest: DestinationInfo? = null
    private var travelled = 0.0
    private var lastMs = 0L

    private val listener = object : NaviHelperEventListener {
        override fun onTBTInfo(tbtInfoList: List<TBTInfo>) {
            // Only the HEAD of the list is the next turn with the remaining distance. Later items are in route order
            // but their distances are not "distance from here" (seen on the AVD: 80, 390, 1000, 70 m), so picking the
            // nearest by distance chose a later right turn ("곧 오른쪽") while the navigation showed a left turn.
            tbt = Reported(tbtInfoList.take(1).map { t ->
                // id = type + absolute route position, so the same turn keeps its id as it gets closer
                RouteEvent("tbt-${t.type}-${((travelled + t.distance) / 40).toInt()}", EventKind.of(t.type), t.distance, t.description, tbt = true)
            }, System.currentTimeMillis()).also { travelledAt = travelled }
            // Raw log whenever the head of the list changes (type or 50 m step), plus every 50th call
            val sig = tbtInfoList.take(2).joinToString { "${it.type}/${(it.distance / 50).toInt()}" }
            if (sig != lastSig || tbtLogs++ % 50 == 0) {
                lastSig = sig
                Log.i(LiveVehicle.TAG, "navi onTBTInfo n=${tbtInfoList.size} " + tbtInfoList.take(4).joinToString(" | ") {
                    "${it.type.name}(#${it.type.ordinal},v${it.type.value}) ${"%.0f".format(it.distance)}m '${it.description}' → ${EventKind.of(it.type)}"
                })
            }
        }
        override fun onCautionPointInfo(cautionPointList: List<CautionPoint>) {
            cautions = Reported(cautionPointList.map { c ->
                RouteEvent("caution-${c.id}", EventKind.of(c.type), c.remainDistance.toDouble(), cautionLabel(c.type.name))
            }, System.currentTimeMillis()).also { cautionAt = travelled }
            Log.i(LiveVehicle.TAG, "navi onCautionPointInfo n=${cautionPointList.size} first=${cautionPointList.firstOrNull()}")
        }
        override fun onDestinationInfo(destinationInfo: DestinationInfo) { dest = destinationInfo; Log.i(LiveVehicle.TAG, "navi onDestinationInfo ${destinationInfo.name} ${destinationInfo.remainDistance}m") }
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) { Log.i(LiveVehicle.TAG, "navi onRouteStarted $routeStartInfo") }
        override fun onRoutingCompleted() { Log.i(LiveVehicle.TAG, "navi onRoutingCompleted"); runCatching { navi.getTBTInfo(); navi.getDestinationInfo() } }
        override fun onNavigationStatus(status: NavigationStatus) { Log.i(LiveVehicle.TAG, "navi onNavigationStatus $status") }
        override fun onError(errorCode: NaviErrorCode) { Log.w(LiveVehicle.TAG, "navi onError $errorCode") }
    }
    private var tbtLogs = 0
    private var lastSig = ""

    private fun cautionLabel(type: String) = when (type) {
        "SPEED_BUMP" -> "과속방지턱"; "RAILROAD_CROSSING" -> "철도건널목"; else -> type
    }

    /** Stage-2 probe: ask the built-in navigation app for a route so its TBT/caution events can be observed. */
    fun requestTestRoute() {
        runCatching {
            navi.requestRoute(RouteInfo(latitude = 37.8314, longitude = 127.5105, poiName = "가평역", poiId = "0", address = "경기 가평군 가평읍", poiSubId = "0"))
            Log.i(LiveVehicle.TAG, "navi requestRoute sent")
        }.onFailure { Log.w(LiveVehicle.TAG, "navi requestRoute: ${it.message}") }
    }
    @Volatile private var travelledAt = 0.0
    @Volatile private var cautionAt = 0.0

    override fun start() {
        runCatching {
            navi.initialize({ navi.getTBTInfo(); navi.getDestinationInfo() }, { Log.w(LiveVehicle.TAG, "navi: ${it.message}") })
            navi.addListener(listener)
        }.onFailure { Log.w(LiveVehicle.TAG, "navi: ${it.message}") }
    }

    override fun stop() { runCatching { navi.removeListener(listener); navi.release() } }

    override fun read(nowMs: Long): Snapshot {
        val v = live.speedMps ?: 0f
        if (lastMs != 0L) travelled += v * (nowMs - lastMs) / 1000.0
        lastMs = nowMs
        val events = nextTbtPlusCautions(
            tbt.events.map { it.copy(distanceM = it.distanceM - (travelled - travelledAt)) },
            cautions.events.map { it.copy(distanceM = it.distanceM - (travelled - cautionAt)) },
        )
        return Snapshot(
            speedMps = v, headingDeg = live.headingDeg, brake = live.brake, steeringDeg = live.steeringDeg,
            turnSignal = live.turn, gear = live.gear, rearLeft = live.rearLeft, rearRight = live.rearRight,
            events = events, destination = dest?.name,
            remainSec = dest?.remainTime, simulated = false,
        )
    }
}
