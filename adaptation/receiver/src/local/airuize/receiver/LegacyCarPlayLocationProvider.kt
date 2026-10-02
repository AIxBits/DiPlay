package local.airuize.receiver

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import com.shilapi.xcertplay.transport.CarPlayLocationFix
import com.shilapi.xcertplay.transport.Iap2LocationProvider

/** One provider per iAP link; stopping Bluetooth must not stop the Wi-Fi provider. */
class LegacyCarPlayLocationProvider(context: Context, private val link: String,
    private val log: (String) -> Unit, private val vehicleSpeed: () -> Double? = { null }) : Iap2LocationProvider {
    private val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    private var running = false
    private var closed = false
    private var latest: Location? = null
    private var updates = 0L
    private var encoded = 0L
    private var seeded = 0L
    private var rejected = 0L
    private var canSpeedUsed = 0L
    private fun age(location: Location): Long = if (location.elapsedRealtimeNanos > 0)
        (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000 else -1
    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) = synchronized(this@LegacyCarPlayLocationProvider) {
            if (running && !closed) {
                if (GpsDiagnostics.freshGps(location.provider, location.latitude, location.longitude, location.isFromMockProvider, age(location)) && location.time > 0) {
                    latest = Location(location); updates++
                    if (updates == 1L || updates % 30 == 0L) log("车载GPS[$link]有效更新=$updates；ageMs=${age(location)}；有方向=${location.hasBearing()}（不记录位置）")
                } else { latest = null; rejected++ }
            }
        }
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) = synchronized(this@LegacyCarPlayLocationProvider) { latest = null }
        override fun onStatusChanged(provider: String, status: Int, extras: Bundle?) {}
    }
    override fun onRequested(components: Set<Int>) { log("车载GPS[$link]手机定位订阅已收到；请求项数量=${components.size}；提供GGA/RMC；CAN车速由车辆信息读取层提供") }
    @Synchronized override fun start(): Boolean {
        if (closed) return false
        if (running) return true
        val gps = manager ?: return false
        try {
            if (!gps.isProviderEnabled(LocationManager.GPS_PROVIDER)) { log("车载GPS[$link]未启用，暂不发送位置"); return false }
            // Match upstream seedLastKnownLocations, but accept only fresh real GPS.
            // This lets StartLocationInformation receive a fix immediately rather than
            // waiting for a one-second callback; STOP still cancels all subsequent data.
            try {
                val cached = gps.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                if (cached != null && cached.time > 0 && GpsDiagnostics.freshGps(cached.provider, cached.latitude,
                    cached.longitude, cached.isFromMockProvider, age(cached))) {
                    latest = Location(cached); seeded++
                    log("车载GPS[$link]按原仓库预置新鲜GPS缓存；ageMs=${age(cached)}；缓存采用=$seeded（不等于新回调）")
                }
            } catch (error: Exception) { log("车载GPS[$link]缓存读取异常=${error.javaClass.simpleName}；继续等待实时GPS") }
            running = true
            gps.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
            log("车载GPS[$link]已开始订阅；仅新鲜GPS缓存或实时更新，不用network/模拟位置")
            return true
        } catch (error: Exception) {
            running = false; latest = null
            log("车载GPS[$link]订阅失败=${error.javaClass.simpleName}")
            return false
        }
    }
    @Synchronized override fun latestNmea(): String? {
        if (!running || closed) return null
        val location = latest ?: return null
        if (!GpsDiagnostics.freshGps(location.provider, location.latitude, location.longitude, location.isFromMockProvider, age(location))) {
            latest = null; rejected++; log("车载GPS[$link]位置过期，暂停发送等待新位置"); return null
        }
        val canSpeed=vehicleSpeed()
        if(canSpeed!=null) canSpeedUsed++
        return LegacyLocationEncoder.encode(CarPlayLocationFix(location.latitude, location.longitude,
            altitudeMeters = null, // Android altitude datum is not established as NMEA MSL altitude.
            bearingDegrees = if (location.hasBearing()) location.bearing.toDouble() else null,
            speedMetersPerSecond = canSpeed ?: if (location.hasSpeed()) location.speed.toDouble() else null,
            timestampMillis = location.time)).also {
                encoded++
                if (encoded == 1L || encoded % 30 == 0L) log("车载GPS[$link]有效NMEA提供=$encoded；CAN速度采用=$canSpeedUsed；本次速度来源=${if(canSpeed!=null) "ABS/CAN" else "GPS或缺失"}；手机实际使用仍需验证")
            }
    }
    @Synchronized override fun stop() {
        val wasRunning = running
        running = false; latest = null
        if (wasRunning) {
            try { manager?.removeUpdates(listener) } catch (error: Exception) { log("车载GPS[$link]停止异常=${error.javaClass.simpleName}") }
            log("车载GPS[$link]停止；有效更新=$updates；缓存采用=$seeded；NMEA提供=$encoded；拒绝=$rejected")
        }
    }
    @Synchronized override fun close() { closed = true; stop() }
}
