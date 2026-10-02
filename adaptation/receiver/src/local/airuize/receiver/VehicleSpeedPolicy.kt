package local.airuize.receiver

import com.shilapi.xcertplay.transport.VehicleGear

/** CarinfoService already scales ABS raw values by 0.0625; never scale twice. */
object VehicleSpeedPolicy {
    fun metersPerSecond(result: Int, kmh: Double): Double? =
        if (result == 0 && kmh.isFinite() && kmh in 0.0..300.0) kmh / 3.6 else null
    fun fresh(now: Long, received: Long): Boolean = received >= 0 && now - received in 0..1500
    fun gearMap(text: String): Map<Int, VehicleGear> {
        val values = text.split(',').map { it.trim().toIntOrNull() }
        if (values.size != 4 || values.any { it == null || it !in 0..255 } || values.toSet().size != 4) return emptyMap()
        return values.map { it!! }.zip(listOf(VehicleGear.PARK, VehicleGear.REVERSE, VehicleGear.NEUTRAL, VehicleGear.DRIVE)).toMap()
    }
}
