package local.airuize.receiver

import android.content.*
import android.os.*
import com.shilapi.xcertplay.transport.*
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** Read-only AIDL transactions verified against the supplied CarinfoService and Music APKs. */
class LegacyVehicleSpeed(context: Context, private val link: String, private val log: (String)->Unit) : VehicleSpeedSource {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val mapping = VehicleSpeedPolicy.gearMap(app.getSharedPreferences("receiver-ui",0).getString("vehicleGearMap", "") ?: "")
    private var generation = 0
    private var executor: ScheduledExecutorService? = null
    private var connection: ServiceConnection? = null
    private var bound = false
    private var remote: IBinder? = null
    private var latest: VehicleSpeedSample? = null
    private var gear: VehicleGear? = null
    private var gearAt = -1L
    private val samples = ArrayList<VehicleSpeedSample>()
    private var reads = 0L
    private var accepted = 0L
    private var rejected = 0L
    private var sentences = 0L
    private var lastRawGear: Int? = null
    @Synchronized fun hasGearMapping() = mapping.isNotEmpty()
    @Synchronized override fun start() {
        if (executor != null) return
        val id = ++generation
        latest=null; gear=null; samples.clear()
        val conn = object: ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) = synchronized(this@LegacyVehicleSpeed) {
                if (id != generation) return@synchronized
                try { check(service.interfaceDescriptor == DESCRIPTOR); remote=service; log("CAN车速[$link]车辆信息服务已连接；只读；挡位映射=${mapping.isNotEmpty()}") }
                catch(e:Exception) { remote=null; log("CAN车速[$link]接口校验失败=${e.javaClass.simpleName}") }
            }
            override fun onServiceDisconnected(name: ComponentName) = synchronized(this@LegacyVehicleSpeed) {
                if(id==generation) { remote=null; latest=null; gear=null; samples.clear(); log("CAN车速[$link]服务断开，停报") }
            }
        }
        connection=conn
        main.post { synchronized(this) {
            if(id==generation) try {
                bound=app.bindService(Intent("com.iflytek.autofly.carinfoservice").setComponent(ComponentName("com.iflytek.autofly.carinfoservice","com.iflytek.autofly.carinfoservice.CarinfoService")),conn,Context.BIND_AUTO_CREATE)
                log("CAN车速[$link]绑定结果=$bound；来源=ABS/CAN；不是GPS速度")
            } catch(e:Exception) { log("CAN车速[$link]绑定异常=${e.javaClass.simpleName}") }
        } }
        executor=Executors.newSingleThreadScheduledExecutor { Thread(it,"legacy-can-speed").apply { isDaemon=true } }.also {
            it.scheduleWithFixedDelay({ poll(id) },0,250,TimeUnit.MILLISECONDS)
        }
    }
    private fun <T> read(binder: IBinder, code: Int, decode:(Parcel)->T): T? {
        val data=Parcel.obtain(); val reply=Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            if(!binder.transact(code,data,reply,0)) return null
            reply.readException()
            if(reply.readInt()==0) return null
            return decode(reply)
        } finally { data.recycle(); reply.recycle() }
    }
    private fun poll(id: Int) {
        val binder=synchronized(this) { if(id==generation) remote else null } ?: return
        try {
            val started=SystemClock.elapsedRealtime()
            val speed=read(binder,16) { p ->
                p.readFloat(); val kmh=p.readFloat().toDouble()
                repeat(3) { p.readFloat() }; repeat(2) { p.readInt() }
                VehicleSpeedPolicy.metersPerSecond(p.readInt(),kmh)
            }
            val raw=if(reads%4==0L) read(binder,11) { p ->
                val value=p.readInt(); repeat(3) { p.readInt() }; if(p.readInt()==0) value else null
            } else null
            val now=SystemClock.elapsedRealtime()
            synchronized(this) {
                if(id!=generation) return
                reads++
                if(raw!=null) {
                    val next=mapping[raw]
                    if(gear!=next) samples.clear()
                    gear=next; gearAt=now
                    if(raw!=lastRawGear) { lastRawGear=raw; log("CAN车速[$link]原始挡位=$raw；已映射=${gear?.letter ?: "未知"}") }
                }
                if(speed!=null && now-started<=1000) {
                    latest=VehicleSpeedSample(now,speed); accepted++
                    if(samples.size==8) samples.removeAt(0)
                    samples.add(latest!!)
                } else { latest=null; samples.clear(); rejected++ }
                if(reads==1L || reads%120==0L) log("CAN车速[$link]读取=$reads；有效=$accepted；拒绝=$rejected；当前kmh=${latest?.metersPerSecond?.let { String.format(java.util.Locale.US,"%.1f",it*3.6) } ?: "无效"}；原始挡位=$lastRawGear；独立PASCD=$sentences；API不提供CAN帧时间戳")
            }
        } catch(e:Exception) { synchronized(this) {
            if(id==generation) { latest=null; gear=null; samples.clear(); rejected++; if(rejected==1L || rejected%120==0L) log("CAN车速[$link]读取失败=${e.javaClass.simpleName}；停报，不用零值替代") }
        } }
    }
    @Synchronized fun latestMetersPerSecond(): Double? = latest?.takeIf { VehicleSpeedPolicy.fresh(SystemClock.elapsedRealtime(),it.elapsedMillis) }?.metersPerSecond
    @Synchronized override fun drain(): VehicleSpeedReading? {
        val now=SystemClock.elapsedRealtime()
        val current=gear
        val fresh=samples.filter { VehicleSpeedPolicy.fresh(now,it.elapsedMillis) }
        samples.clear()
        if(current==null || !VehicleSpeedPolicy.fresh(now,gearAt) || fresh.isEmpty()) return null
        sentences++
        return VehicleSpeedReading(current,fresh)
    }
    @Synchronized override fun stop() {
        generation++; executor?.shutdownNow(); executor=null; remote=null; latest=null; gear=null; samples.clear()
        val conn=connection; val wasBound=bound; connection=null; bound=false
        if(conn!=null && wasBound) main.post { try { app.unbindService(conn) } catch(_:Exception) {} }
        log("CAN车速[$link]停止；有效=$accepted；拒绝=$rejected；PASCD提供=$sentences")
    }
    companion object { private const val DESCRIPTOR="com.iflytek.autofly.carinfoservice.aidl.ICarInfoServiceAIDL" }
}

/** Uses original PASCD encoder when a verified gear map exists; CAN speed also feeds original RMC. */
class LegacyVehicleLocationProvider(context:Context, link:String, private val log:(String)->Unit) : Iap2LocationProvider {
    private val speed=LegacyVehicleSpeed(context,link,log)
    private val gps=LegacyCarPlayLocationProvider(context,link,log) { speed.latestMetersPerSecond() }
    private val combined=VehicleSpeedLocationProvider(gps,speed)
    private var requestedSpeed=false
    override fun onRequested(components:Set<Int>) {
        requestedSpeed=Iap2LocationMessages.VEHICLE_SPEED_DATA in components
        log("CAN车速定位：手机请求独立车速=$requestedSpeed；挡位映射=${speed.hasGearMapping()}；无映射仅提供有效GPS定位中的RMC车速")
        combined.onRequested(if(speed.hasGearMapping()) components else components-Iap2LocationMessages.VEHICLE_SPEED_DATA)
    }
    override fun start():Boolean {
        speed.start()
        return combined.start().also { if(!it) speed.stop() }
    }
    override fun latestNmea():String? = combined.latestNmea()
    override fun stop() { combined.stop() }
    override fun close() { stop(); gps.close() }
}
