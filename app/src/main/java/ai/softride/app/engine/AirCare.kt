package ai.softride.app.engine

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.HVAC
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.capability.AreaBounds
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit
import ai.softride.app.signals.LiveVehicle.Companion.TAG
import android.os.Handler
import android.os.Looper
import android.util.Log

/** What the driver allowed while parked. Stage 1 default: all three on. */
data class AirAllowance(val freshAir: Boolean = true, val tempDownC: Float = 1f, val fanUp: Int = 1)

enum class AirState { WAITING, ANNOUNCED, DONE, MANUAL, UNSUPPORTED }

/**
 * 공조 케어 (request model): at most once per trip, only on areas returned by checkHvac*Capabilities
 * (each property picks its own area: ROW_2 when the car reports one, else the driver's row), announced
 * by voice before acting, and a manual change by the driver wins.
 */
class AirCare(private val vehicle: () -> Vehicle?, private val speak: (String) -> Boolean) {
    private class Survey(
        val recArea: SeatArea?, val rec: Boolean?,
        val tempArea: SeatArea?, val tempBounds: AreaBounds<Float>?, val temp: Float?,
        val fanArea: SeatArea?, val fanBounds: AreaBounds<Int>?, val fan: Int?,
    ) {
        fun values() = Triple(rec, temp, fan)
        /** True only when every property reached a ROW_2 area. */
        val rear get() = listOfNotNull(recArea, tempArea, fanArea).let { l -> l.isNotEmpty() && l.all { a -> a.values.any { it.name.startsWith("ROW_2") } } }
    }

    private val main = Handler(Looper.getMainLooper())
    @Volatile var state = AirState.WAITING; private set
    @Volatile var areaLabel = ""; private set
    @Volatile var applied = listOf<String>(); private set
    private var baseline: Triple<Boolean?, Float?, Int?>? = null
    private var allowance = AirAllowance()

    /** Call at trip start: remembers the HVAC values so a later manual change can be detected. */
    fun newTrip(allow: AirAllowance) {
        state = AirState.WAITING; applied = emptyList(); allowance = allow; baseline = null
        survey { baseline = it.values() }
    }

    fun trigger() {
        if (state != AirState.WAITING) return
        state = AirState.ANNOUNCED
        survey { now ->
            val base = baseline
            if (base != null && now.values() != base) { state = AirState.MANUAL; Log.i(TAG, "air care: manual change wins $base -> ${now.values()}"); return@survey }
            if (!speak("바깥 공기로 바꿀게요")) { state = AirState.WAITING; return@survey }   // no announcement, no action
            main.postDelayed({ apply(now) }, 2_200)
        }
    }

    private fun apply(p: Survey) {
        val hvac = vehicle()?.getHVAC() ?: return
        areaLabel = if (p.rear) "뒷좌석" else ""
        val done = mutableListOf<String>()
        fun ok(s: String) { synchronized(done) { done += s; applied = done.toList() } }
        if (allowance.freshAir && p.recArea != null && p.rec != false)
            hvac.setHvacRecirculation(p.recArea, false, { _, _ -> ok("바깥 공기") }, ::fail)
        if (allowance.tempDownC > 0 && p.tempArea != null && p.temp != null && p.tempBounds != null) {
            val target = (p.temp - allowance.tempDownC).coerceIn(p.tempBounds.minValue, p.tempBounds.maxValue)
            hvac.setHvacTemperature(p.tempArea, target, TemperatureUnit.CELSIUS, { _, v -> ok("${v ?: target}°") }, ::fail)
        }
        if (allowance.fanUp > 0 && p.fanArea != null && p.fan != null && p.fanBounds != null) {
            val target = (p.fan + allowance.fanUp).coerceIn(p.fanBounds.minValue, p.fanBounds.maxValue)
            hvac.setHvacFanSpeed(p.fanArea, target, { _, v -> ok("바람 ${v ?: target}단") }, ::fail)
        }
        state = AirState.DONE
        Log.i(TAG, "air care: rec=${p.recArea?.values?.toList()} temp=${p.tempArea?.values?.toList()} ${p.temp} fan=${p.fanArea?.values?.toList()} ${p.fan}")
    }

    /** Capabilities → per-property area → current values. */
    private fun survey(out: (Survey) -> Unit) {
        val hvac = runCatching { vehicle()?.getHVAC() }.getOrNull() ?: run { state = AirState.UNSUPPORTED; return }
        runCatching {
            hvac.checkHvacRecirculationCapabilities({ _, recAreas ->
                val recArea = pick(recAreas)
                hvac.checkHvacTemperatureCapabilities({ _, tb ->
                    val tempArea = pick(tb.keys)
                    hvac.checkHvacFanSpeedCapabilities({ _, fb ->
                        val fanArea = pick(fb.keys)
                        if (recArea == null && tempArea == null && fanArea == null) { state = AirState.UNSUPPORTED; return@checkHvacFanSpeedCapabilities }
                        read(hvac, recArea, tempArea, fanArea) { rec, temp, fan ->
                            out(Survey(recArea, rec, tempArea, tempArea?.let { tb[it] }, temp, fanArea, fanArea?.let { fb[it] }, fan))
                        }
                    }, { fail(it); read(hvac, recArea, tempArea, null) { r, t, _ -> out(Survey(recArea, r, tempArea, tempArea?.let { tb[it] }, t, null, null, null)) } })
                }, { fail(it); read(hvac, recArea, null, null) { r, _, _ -> out(Survey(recArea, r, null, null, null, null, null, null)) } })
            }, { fail(it); state = AirState.UNSUPPORTED })
        }.onFailure { Log.w(TAG, "air care: ${it.message}"); state = AirState.UNSUPPORTED }
    }

    private fun read(hvac: HVAC, rec: SeatArea?, temp: SeatArea?, fan: SeatArea?, out: (Boolean?, Float?, Int?) -> Unit) {
        fun fanStep(r: Boolean?, t: Float?) =
            if (fan == null) out(r, t, null) else hvac.getHvacFanSpeed(fan, { out(r, t, it) }, { out(r, t, null) })
        fun tempStep(r: Boolean?) =
            if (temp == null) fanStep(r, null) else hvac.getHvacTemperature(temp, TemperatureUnit.CELSIUS, { fanStep(r, it) }, { fanStep(r, null) })
        if (rec == null) tempStep(null) else hvac.getHvacRecirculationState(rec, { tempStep(it) }, { tempStep(null) })
    }

    private fun pick(areas: Collection<SeatArea>): SeatArea? =
        areas.firstOrNull { a -> a.values.any { it.name.startsWith("ROW_2") } }
            ?: areas.firstOrNull { a -> SeatValue.ROW_1_LEFT in a.values } ?: areas.firstOrNull()

    private fun fail(e: Exception) { Log.w(TAG, "air care: ${e.message}") }
}
