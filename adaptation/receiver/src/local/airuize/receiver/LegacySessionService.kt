package local.airuize.receiver

import android.app.Service
import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Application-owned session survives activity destruction and foreground/background transitions. */
class LegacySessionService : Service() {
    inner class LocalBinder : Binder() { val service get() = this@LegacySessionService }
    @Volatile var controller: LegacyWirelessController? = null; private set
    @Volatile var sink: LegacyMediaSink? = null; private set
    @Volatile var microphoneEnabled = false; private set
    @Volatile var observer: ((String)->Unit)? = null
    @Volatile var frameObserver: (()->Unit)? = null
    private val history = StringBuilder("DiPlay Legacy 0.21; SDK18\n")
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { Thread(it,"legacy-session-lifecycle").apply { isDaemon=true } }
    private val generation = AtomicInteger()
    @Volatile private var surface: android.view.Surface? = null
    fun setSurface(value: android.view.Surface?,force:Boolean=false) { if((surface !== value || force) && value != null) nextFrame.set(true); surface=value; sink?.setSurface(value,force) }
    private val nextFrame = java.util.concurrent.atomic.AtomicBoolean(true)
    val hasConnectionRequest get() = running && desiredPhone != null
    private var attempt = 0
    @Volatile private var connectingAt = 0L
    @Volatile private var reconnectPending = false
    @Volatile private var desiredPhone: BluetoothDevice? = null
    @Volatile private var running = false
    private val retry = Runnable { desiredPhone?.let { connect(it, false) } }
    private val health = object: Runnable { override fun run() {
        if(!running) return
        val current=controller
        current?.let { log(it.diagnosticSummary()); if(it.activeSession != null) attempt=0 }
        if(current != null && current.activeSession == null && !reconnectPending && android.os.SystemClock.elapsedRealtime()-connectingAt>120000) failed("Initial connection deadline exceeded")
        sink?.let { log(it.diagnosticSummary()) }
        handler.postDelayed(this,15000)
    } }
    fun report(): String = synchronized(history) { history.toString() }
    fun log(text: String) {
        synchronized(history) { history.append(android.os.SystemClock.elapsedRealtime()).append("ms ").append(text).append('\n'); if(history.length>90000) history.delete(0,history.length-72000) }
        observer?.invoke(text)
    }
    override fun onCreate() { super.onCreate(); instance=this }
    override fun onBind(intent: Intent): IBinder = LocalBinder()
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        foreground()
        if(intent?.action == STOP) { disconnect(); stopSelf(); return START_NOT_STICKY }
        val preferences=getSharedPreferences("receiver-ui",MODE_PRIVATE)
        val restore=intent==null && ConnectionStartPolicy.onServiceRestore(preferences.getBoolean("autoOpenConnect",false),ReceiverSettings.read(this).bootConnect,preferences.getBoolean("sessionWanted",false))
        if(intent?.action==CONNECT || restore) autoConnect()
        else if(intent==null) log("Session restart: automatic connection disabled; waiting for user selection")
        return if(running) START_STICKY else START_NOT_STICKY
    }
    private fun foreground() {
        val open = PendingIntent.getActivity(this,0,Intent(this,ReceiverActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this,1,Intent(this,LegacySessionService::class.java).setAction(STOP),PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = Notification.Builder(this).setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("DiPlay CarPlay")
            .setContentText("CarPlay session service").setContentIntent(open).setOngoing(true).addAction(android.R.drawable.ic_menu_close_clear_cancel,"Stop",stop)
        startForeground(43,builder.build())
    }
    fun autoConnect() {
        if(LegacyWiredService.instance != null || running && controller != null) return
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        val saved = getSharedPreferences("receiver-ui",MODE_PRIVATE).getString("phoneAddress",null)
        val phones = adapter.bondedDevices.toList()
        val phone = phones.firstOrNull { it.address==saved } ?: saved?.let { runCatching { adapter.getRemoteDevice(it) }.getOrNull() } ?: phones.singleOrNull()
        if(phone != null) connect(phone) else log("Select and pair an iPhone first")
    }
    fun connect(phone: BluetoothDevice, reset: Boolean = true) {
        if(reset) attempt=0
        desiredPhone=phone; running=true; reconnectPending=false; connectingAt=android.os.SystemClock.elapsedRealtime(); foreground(); handler.removeCallbacks(retry)
        getSharedPreferences("receiver-ui",MODE_PRIVATE).edit().putString("phoneAddress",phone.address).putBoolean("sessionWanted",true).commit()
        val id=generation.incrementAndGet()
        worker.execute {
            controller?.close(); controller=null; sink?.close(); sink=null
            if(id!=generation.get() || !running) return@execute
            val settings=ReceiverSettings.read(this)
            val display=settings.display()
            var created: LegacyWirelessController? = null
            val media=LegacyMediaSink(::log, { created?.frameRendered(); if(nextFrame.compareAndSet(true,false)) frameObserver?.invoke() }, display.widthPixels,display.heightPixels,settings,this)
            val next=LegacyWirelessController(applicationContext,media,::log,display.widthPixels,display.heightPixels,settings.gps,
                { reason -> handler.post { if(controller===created && id==generation.get()) failed(reason) } },settings,
                { handler.post { startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); log("OEM return to head-unit launcher") } })
            created=next
            microphoneEnabled=settings.microphone && packageManager.checkPermission(android.Manifest.permission.RECORD_AUDIO,packageName)==android.content.pm.PackageManager.PERMISSION_GRANTED
            nextFrame.set(true)
            sink=media; media.setSurface(surface); controller=next
            log("Display ${display.widthPixels}x${display.heightPixels} fps=${display.fps} physical=${display.widthPhysicalMm}x${display.heightPhysicalMm} safeArea=${display.safeArea}; HEVC=${settings.hevc&&CodecCapabilities.hevc()} Opus=${settings.opus&&CodecCapabilities.opus()}")
            next.start(phone,microphoneEnabled)
        }
        handler.removeCallbacks(health); handler.postDelayed(health,15000)
    }
    private fun failed(reason: String) {
        if(!running || reconnectPending) return
        reconnectPending=true
        log("Connection ended: $reason")
        val id=generation.incrementAndGet()
        worker.execute { controller?.close(); controller=null; sink?.close(); sink=null
            handler.post {
                if(id==generation.get() && running && ReceiverSettings.read(this).reconnect) {
                    val delay=ReconnectPolicy.delay(attempt++)
                    log("Reconnect scheduled in ${delay/1000}s; attempt=$attempt")
                    handler.postDelayed(retry,delay)
                }
            }
        }
    }
    fun disconnect(after: (()->Unit)? = null) {
        running=false; desiredPhone=null; val id=generation.incrementAndGet(); handler.removeCallbacks(retry); handler.removeCallbacks(health)
        getSharedPreferences("receiver-ui",MODE_PRIVATE).edit().putBoolean("sessionWanted",false).commit()
        worker.execute { controller?.close(); controller=null; sink?.close(); sink=null
            handler.post { if(id==generation.get()) after?.invoke() }
        }
    }
    override fun onDestroy() { disconnect(); worker.shutdown(); observer=null; frameObserver=null; instance=null; super.onDestroy() }
    companion object {
        @Volatile var instance: LegacySessionService? = null
        const val CONNECT="local.airuize.receiver.CONNECT"
        const val STOP="local.airuize.receiver.STOP"
    }
}
object ReconnectPolicy { fun delay(attempt: Int): Long = minOf(60000L, 2000L shl attempt.coerceIn(0,5)) }
