package ai.softride.app.signals

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.util.Log

/**
 * Live car values. Vehicle SDK: brake, steering angle, turn signal, gear, ROW_2 seat occupancy.
 * Speed: AAOS CarPropertyManager PERF_VEHICLE_SPEED (Vehicle SDK has no speed API).
 * Used by both Real and Replay, so Virtual Console changes always reach the app.
 */
class LiveVehicle(context: Context) {
    val vehicle = Vehicle(context.applicationContext)
    private val appContext = context.applicationContext
    private var car: Car? = null

    @Volatile var brake = false; private set
    @Volatile var steeringDeg = 0f; private set
    @Volatile var turn = Turn.OFF; private set
    @Volatile var gear = GearPos.UNKNOWN; private set
    @Volatile private var carSpeed: Float? = null
    @Volatile private var carSpeedAtMs = 0L
    @Volatile private var fusedSpeed: Float? = null
    @Volatile var headingDeg: Float? = null; private set
    @Volatile var speedSource = "none"; private set

    /** PERF_VEHICLE_SPEED when it is arriving; otherwise speed from FusedLocation position change. */
    val speedMps: Float?
        get() {
            val car = carSpeed
            return if (car != null && System.currentTimeMillis() - carSpeedAtMs < 5_000) car.also { speedSource = "car" }
            else fusedSpeed?.also { speedSource = "fused" }
        }
    private val rearSeats = mutableMapOf<SeatValue, Boolean>()
    /**
     * Vehicle 2.3.2 turns SEAT_OCCUPANCY into `value == 1` (PropertyCallback / SeatImpl), but the AAOS values are
     * 0 UNKNOWN, 1 VACANT, 2 OCCUPIED: on the AVD the SDK says true for an empty seat and false for an occupied one
     * (Virtual Console rear_right=true → value 2 → SDK false). So "false" from the SDK means occupied here.
     * Version guard: checked on Vehicle 2.3.2 only. If a later SDK maps OCCUPIED to true, flip [SDK_OCCUPIED];
     * the raw SDK value is logged as "seat ROW_2_* sdk=…" to check this on a new SDK or a real car.
     */
    private fun occupied(sdk: Boolean?) = sdk == SDK_OCCUPIED
    val rearLeft: Boolean get() = synchronized(rearSeats) { rearSeats[SeatValue.ROW_2_LEFT] == true }
    val rearRight: Boolean get() = synchronized(rearSeats) { rearSeats[SeatValue.ROW_2_RIGHT] == true }

    private val fused = FusedLocation(appContext)
    private var lastFix: LocationData? = null
    private val fusedL = object : FusedLocationListener {
        override fun onChanged(location: LocationData?) {
            location ?: return
            headingDeg = location.heading
            lastFix?.let { p ->
                val dt = (location.timestampMillis - p.timestampMillis) / 1000f
                if (dt in 0.2f..10f) fusedSpeed = SpeedMath.metres(p.latitude, p.longitude, location.latitude, location.longitude).toFloat() / dt
            }
            lastFix = location
        }
        override fun onError(exception: Exception) = log(exception)
    }
    @Volatile var ready = false; private set

    private val brakeL = object : BrakePedalStatusListener {
        override fun onBrakePedalStatusUpdated(status: Boolean?) { brake = status == true }
        override fun onFailed(e: Exception) = log(e)
    }
    private val steerL = object : SteeringWheelAngleListener {
        override fun onSteeringWheelAngleUpdated(angle: Float?) { steeringDeg = angle ?: 0f }
        override fun onFailed(e: Exception) = log(e)
    }
    private val turnL = object : TurnSignalStateListener {
        override fun onTurnSignalStateUpdated(state: TurnSignalValue?) { turn = state.toTurn() }
        override fun onFailed(e: Exception) = log(e)
    }
    private val gearL = object : GearSelectionListener {
        override fun onSelected(gear: VehicleGear?) { this@LiveVehicle.gear = gear.toPos() }
        override fun onFailed(e: Exception) = log(e)
    }
    private val seatLs = listOf(SeatValue.ROW_2_LEFT, SeatValue.ROW_2_RIGHT).associateWith { v ->
        object : SeatOccupancyListener {
            override fun onSeatOccupancyUpdated(occupancy: Boolean?) { Log.i(TAG, "seat $v sdk=$occupancy"); synchronized(rearSeats) { rearSeats[v] = occupied(occupancy) } }
            override fun onFailed(e: Exception) = log(e)
        }
    }
    private val speedCb = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            (value.value as? Float)?.let { carSpeed = kotlin.math.abs(it); carSpeedAtMs = System.currentTimeMillis() }
        }
        override fun onErrorEvent(propId: Int, zone: Int) {}
    }

    fun start() {
        runCatching { vehicle.initialize(); ready = true }.onFailure(::log)
        if (ready) {
            safe { vehicle.getBrake().getCurrentBrakePedalStatus({ brake = it == true }, ::log); vehicle.getBrake().registerBrakePedalStatus(brakeL) }
            safe { vehicle.getSteeringWheel().getCurrentSteeringWheelAngle({ steeringDeg = it ?: 0f }, ::log); vehicle.getSteeringWheel().registerSteeringWheelAngle(steerL) }
            safe { vehicle.getTurnSignal().getCurrentTurnSignalState({ turn = it.toTurn() }, ::log); vehicle.getTurnSignal().registerTurnSignalState(turnL) }
            safe { vehicle.getGear().getCurrentGearSelectionState({ gear = it.toPos() }, ::log); vehicle.getGear().registerGearSelection(gearL) }
            seatLs.forEach { (v, l) ->
                safe {
                    val area = SeatArea(v)
                    vehicle.getSeat().getCurrentSeatOccupancy(area, { o -> Log.i(TAG, "seat $v sdk current=$o"); synchronized(rearSeats) { rearSeats[v] = occupied(o) } }, ::log)
                    vehicle.getSeat().registerSeatOccupancy(area, l)
                }
            }
        }
        safe {
            car = Car.createCar(appContext)
            val cpm = car?.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
            cpm?.registerCallback(speedCb, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_NORMAL)
        }
        safe {
            fused.initialize()
            if (fused.isSupported()) { fused.registerFusedLocationCallback(fusedL); Log.i(TAG, "fused location registered") }
            else Log.i(TAG, "fused location not supported")
        }
    }

    fun stop() {
        if (ready) {
            safe { vehicle.getBrake().unregisterBrakePedalStatus(brakeL) }
            safe { vehicle.getSteeringWheel().unregisterSteeringWheelAngle(steerL) }
            safe { vehicle.getTurnSignal().unregisterTurnSignalState(turnL) }
            safe { vehicle.getGear().unregisterGearSelection(gearL) }
            seatLs.forEach { (v, l) -> safe { vehicle.getSeat().unregisterSeatOccupancy(SeatArea(v), l) } }
            safe { vehicle.release() }
            ready = false
        }
        safe { (car?.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager)?.unregisterCallback(speedCb); car?.disconnect() }
        safe { fused.unregisterFusedLocationCallback(fusedL); fused.release() }
    }

    private inline fun safe(block: () -> Unit) = runCatching(block).onFailure(::log)
    private fun log(e: Throwable) { Log.w(TAG, "vehicle: ${e.message}") }

    companion object {
        const val TAG = "SoftRide"
        /** SDK seat-occupancy value that means "someone sits here" (Vehicle 2.3.2 reports it inverted; see [occupied]). */
        const val SDK_OCCUPIED = false
        private fun TurnSignalValue?.toTurn() = when (this) { TurnSignalValue.LEFT -> Turn.LEFT; TurnSignalValue.RIGHT -> Turn.RIGHT; else -> Turn.OFF }
        private fun VehicleGear?.toPos() = when (this) {
            VehicleGear.GEAR_PARK -> GearPos.PARK; VehicleGear.GEAR_REVERSE -> GearPos.REVERSE
            VehicleGear.GEAR_NEUTRAL -> GearPos.NEUTRAL; null, VehicleGear.GEAR_UNKNOWN -> GearPos.UNKNOWN
            else -> GearPos.DRIVE
        }
    }
}
