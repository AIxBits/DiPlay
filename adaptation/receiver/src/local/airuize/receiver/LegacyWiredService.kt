package local.airuize.receiver

import android.app.Notification
import android.app.PendingIntent
import android.content.*
import android.hardware.usb.*
import android.net.VpnService
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.view.Surface
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.transport.*
import com.shilapi.xcertplay.iap2.session.Iap2Session
import com.shilapi.xcertplay.mfi.*
import com.shilapi.xcertplay.network.Ipv6NcmBridge
import java.io.Closeable
import java.io.File
import java.net.*
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Upstream USBMUX -> Lockdown -> carkit iAP2, with NCM -> IPv6 VPN AirPlay. */
class LegacyWiredService : VpnService() {
    @Volatile var activeSession:AirPlaySession?=null; private set
    private val main=Handler(Looper.getMainLooper())
    private val worker=Executors.newSingleThreadExecutor { Thread(it,"legacy-wired-lifecycle").apply { isDaemon=true } }
    private val running=AtomicBoolean(false)
    private val generation=java.util.concurrent.atomic.AtomicInteger()
    @Volatile private var workingGeneration=0
    private val resources=Collections.synchronizedList(ArrayList<Closeable>())
    private lateinit var usb:UsbManager
    @Volatile private var media:LegacyMediaSink?=null
    @Volatile private var surface:Surface?=null
    private var selectedName:String?=null
    private var pendingPermission:String?=null
    private var denied=false
    private var transitionDevice:String?=null
    private var transitionTime=0L
    private val nextFrame=AtomicBoolean(true)
    private val logHistory=StringBuilder("USB CarPlay 0.21\n")
    fun report()=synchronized(logHistory) { logHistory.toString() }
    private fun log(message:String) { synchronized(logHistory) { logHistory.append(message).append('\n'); if(logHistory.length>60000) logHistory.delete(0,12000) }; LegacySessionService.instance?.log(message) }
    fun setSurface(value:Surface?,force:Boolean=false) { if((surface !== value || force) && value != null) nextFrame.set(true); surface=value; media?.setSurface(value,force) }
    private fun <T:Closeable> own(value:T, expected:Int=workingGeneration):T { synchronized(resources) { if(!running.get() || expected!=generation.get()) { value.close(); error("USB stopped") }; resources.add(value) }; return value }
    private val poll=object:Runnable { override fun run() {
        if(!running.get()) return
        if(selectedName==null && !denied) discover()
        media?.let { log(it.diagnosticSummary()) }
        main.postDelayed(this,5000)
    } }
    private val receiver=object:BroadcastReceiver() { override fun onReceive(context:Context,intent:Intent) {
        val device=intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) ?: return
        if(device.vendorId!=0x05ac) return
        when(intent.action) {
            permissionAction -> { pendingPermission=null
                if(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED,false) && usb.hasPermission(device)) queue(device)
                else { denied=true; log("USB permission denied; reconnect cable or select USB to retry") }
            }
            UsbManager.ACTION_USB_DEVICE_ATTACHED -> if(selectedName==null && !denied) discover()
            UsbManager.ACTION_USB_DEVICE_DETACHED -> if(device.deviceName==selectedName) {
                log("USB detached"); generation.incrementAndGet(); cleanup(); selectedName=null; activeSession=null
            }
        }
    } }
    private val permissionAction get()="$packageName.USB_PERMISSION"
    override fun onCreate() {
        super.onCreate(); instance=this; surface=videoSurface; usb=getSystemService(USB_SERVICE) as UsbManager
        registerReceiver(receiver,IntentFilter(permissionAction).apply { addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED); addAction(UsbManager.ACTION_USB_DEVICE_DETACHED) })
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(!running.compareAndSet(false,true)) { denied=false; discover(); return START_NOT_STICKY }
        if(prepare(this)!=null) { log("USB requires system VPN consent"); stopSelf(); return START_NOT_STICKY }
        LegacySessionService.instance?.disconnect()
        val open=PendingIntent.getActivity(this,0,Intent(this,ReceiverActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT)
        startForeground(44,Notification.Builder(this).setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("DiPlay USB CarPlay").setContentText("USB / NCM session").setContentIntent(open).setOngoing(true).build())
        log("Firmware path: USB Host / descriptor configuration / NCM NTB16 / IPv6 TUN")
        main.post(poll); return START_NOT_STICKY
    }
    private fun discover() {
        val devices=usb.deviceList.values.filter { it.vendorId==0x05ac }
        log("USB enumeration: hostFeature=${packageManager.hasSystemFeature("android.hardware.usb.host")} total=${usb.deviceList.size} apple=${devices.size}")
        val device=devices.firstOrNull() ?: return
        if(!usb.hasPermission(device)) {
            if(pendingPermission==device.deviceName) return
            pendingPermission=device.deviceName
            usb.requestPermission(device,PendingIntent.getBroadcast(this,0,Intent(permissionAction).setPackage(packageName),PendingIntent.FLAG_UPDATE_CURRENT))
            log("USB permission requested"); return
        }
        queue(device)
    }
    private fun queue(device:UsbDevice) {
        if(!running.get() || selectedName!=null) return
        selectedName=device.deviceName
        val id=generation.incrementAndGet()
        worker.execute {
            if(id!=generation.get() || !running.get()) return@execute
            workingGeneration=id
            try { startStack(device,id) }
            catch(error:Exception) {
                if(id==generation.get() && running.get()) { log("USB stage failed: ${error.javaClass.simpleName}"); FailureDiagnostics.lines("USB",error).forEach(::log) }
                cleanup(); if(id==generation.get()) selectedName=null
            }
        }
    }
    private fun startStack(device:UsbDevice,id:Int) {
        val first=usb.openDevice(device) ?: error("USB open failed")
        var transferred=false
        try {
        val power=getSystemService(POWER_SERVICE) as android.os.PowerManager
        val wake=power.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK,"DiPlay:USB").apply { setReferenceCounted(false); acquire() }
        own(Closeable { if(wake.isHeld) wake.release() })
            val descriptors=LegacyUsbPlatform.configurations(first)
            val configuration=IphoneCarPlayConfiguration.find(descriptors)
            log("USB descriptors configurations=${descriptors.map { it.id }} CarPlay=${configuration?.id}")
            if(configuration==null) {
                val now=android.os.SystemClock.elapsedRealtime()
                if(transitionDevice==device.deviceName && now-transitionTime<30000) { log("USB waiting for CarPlay re-enumeration"); return }
                transitionDevice=device.deviceName; transitionTime=now
                val response=ByteArray(1)
                val result=first.controlTransfer(0xc0,0x52,0,4,response,1,1000)
                log("USB Apple vendor transition request result=$result; waiting for re-enumeration")
                check(result>=0) { "USB vendor transition failed" }
                return
            }
            check(LegacyUsbPlatform.selectConfiguration(first,configuration.id)) { "USB SET_CONFIGURATION failed" }
            val muxInterface=IphoneCarPlayConfiguration.usbMuxInterface(configuration) ?: error("USBMUX interface absent")
            val endpoints=IphoneCarPlayConfiguration.usbMuxEndpoints(muxInterface) ?: error("USBMUX endpoints absent")
            check(first.claimInterface(muxInterface,true)) { "USBMUX claim failed" }
            log("USB configuration=${configuration.id}; USBMUX interface=${muxInterface.id}; bulk endpoints selected")
            val pipe=own(Iap2UsbSession(first,endpoints.first,endpoints.second)); transferred=true
            val mux=own(Iap2UsbMuxHost.open(pipe)); log("USBMUX ready")
            val ncmConnection=usb.openDevice(device) ?: error("NCM open failed")
            val function=NcmFunctionDiscovery.find(configuration)
            if(function==null) { ncmConnection.close(); error("CDC NCM function absent") }
            val ncm=own(NcmUsbBridge.open(ncmConnection,function)); log("NCM interfaces claimed; data alternate setting selected")
            val prefs=getSharedPreferences("receiver-usb",MODE_PRIVATE)
            val hostId=prefs.getString("hostId",null) ?: UUID.randomUUID().toString().toUpperCase(Locale.US).also { prefs.edit().putString("hostId",it).commit() }
            val buid=prefs.getString("buid",null) ?: UUID.randomUUID().toString().toUpperCase(Locale.US).also { prefs.edit().putString("buid",it).commit() }
            log("Lockdown pairing: unlock iPhone and confirm Trust if prompted")
            val records=LegacyPairRecords(this)
            fun pair():LockdownPairRecord = LockdownPairingClient(mux).pair("DiPlay Legacy",hostId,buid,120000) { !running.get() || id!=generation.get() }.pairRecord.also { records.save(it) }
            val saved=records.load()
            val pairRecord=saved ?: pair()
            val carkit=own(try { LockdownCarKitClient(mux).open(pairRecord,"DiPlay Legacy") } catch(error:IphoneUsbException) {
                // Upstream retries only explicit saved-record rejection, never generic TLS/I/O failure.
                val rejected=listOf("InvalidHostID", "InvalidPairRecord", "InvalidPairingRecord", "MissingPairRecord").any { error.message?.contains(it)==true }
                if(saved==null || !rejected) throw error
                records.clear(); log("Saved USB pair record rejected; pairing again")
                LockdownCarKitClient(mux).open(pair(),"DiPlay Legacy")
            }); log("Lockdown carkit ready")
            val iap=own(Iap2Session.open(carkit)); log("Wired iAP2 link ready")
            val settings=ReceiverSettings.read(this); val display=settings.display(); nextFrame.set(true)
            val hostMac=ncm.hostMac ?: byteArrayOf(2,0,0,0,0,43)
            val literal="fe80::2"
            val tun=own(Builder().addAddress(literal,64).addRoute("fe80::",64).setSession("DiPlay USB").setMtu(1500).establish() ?: error("VPN TUN rejected"))
            own(Ipv6NcmBridge(ncm,tun,hostMac, { error -> log("NCM IPv6 failure=${error.javaClass.simpleName}"); main.post { if(id==generation.get()) { generation.incrementAndGet(); cleanup(); selectedName=null } } }, ::log)).start()
            log("NCM IPv6 TUN established (SDK18 nonblocking mode)")
            val identity=LegacyWirelessController.loadIdentity(this)
            val authDir=File(filesDir,"offline-mfi").apply { mkdirs() }
            for(name in listOf("identity.pk8","certificate.p7b")) assets.open("offline-mfi/$name").use { input -> File(authDir,name).outputStream().use { input.copyTo(it) } }
            val mfi=LocalMfiAuthenticationClient.load(authDir)
            val deviceId=hostMac.joinToString(":") { "%02X".format(it.toInt() and 255) }
            val config=AirPlayConfig("DiPlay Legacy",deviceId,android.bluetooth.BluetoothAdapter.getDefaultAdapter()?.address ?: deviceId,"950.7.1",display,
                rightHandDrive=settings.rightHandDrive, hevc=settings.hevc&&CodecCapabilities.hevc(), opus=settings.opus&&CodecCapabilities.opus(),microphone=settings.microphone,icons=LegacyOem.icons(),oemLabel="DiPlay",manufacturer="DiPlay Legacy",model="iMX6-Android43")
            val sink=own(LegacyMediaSink(::log,{ if(nextFrame.compareAndSet(true,false)) { log("USB first video frame rendered"); LegacySessionService.instance?.frameObserver?.invoke() } },display.widthPixels,display.heightPixels,settings,this)); media=sink; sink.setSurface(surface)
            val engine=CarPlayMediaEngine(sink,microphoneEnabled=settings.microphone)
            val pairPrefs=getSharedPreferences("receiver-pairings",MODE_PRIVATE)
            val pairings=PairingStore { id,key -> pairPrefs.edit().putString(id,android.util.Base64.encodeToString(key,2)).commit() }
            pairPrefs.all.forEach { (id,value)-> if(value is String) runCatching { pairings.save(id,android.util.Base64.decode(value,2)) } }
            val server=ServerSocket(); server.reuseAddress=true; server.bind(InetSocketAddress(InetAddress.getByName("::"),config.port))
            own(Closeable { server.close() })
            Thread({ try { while(running.get() && id==generation.get()) {
                val socket=server.accept(); socket.tcpNoDelay=true
                own(AirPlaySession(socket,config,identity,pairings,mfi,object:AirPlaySessionListener {
                    override fun onSessionActive(session:AirPlaySession) { if(id==generation.get()) { activeSession=session; log("USB AirPlay active") } }
                    override fun onSessionEnded(session:AirPlaySession) { if(activeSession===session) activeSession=null; log("USB AirPlay ended") }
                    override fun onDebugLog(message:String) { AirPlayDiagnostics.summary(message)?.let(::log) }
                    override fun onHostUiRequested(session:AirPlaySession) { startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    override fun onControlFailure(stage:String,error:Throwable) { log("USB AirPlay failure stage=$stage class=${error.javaClass.simpleName}") }
                },engine),id).start()
            } } catch(error:Exception) { if(running.get()) log("USB AirPlay listener ended ${error.javaClass.simpleName}") } },"legacy-wired-airplay").apply { isDaemon=true; start() }
            val location=if(settings.gps) own(LegacyVehicleLocationProvider(this,"USB",::log)) else null
            val identification=Iap2IdentificationConfig("DiPlay Legacy","iMX6-Android43","DiPlay Legacy",identity.pairingId,"0.21","iMX6", carPlayUsbInterfaceNumber=muxInterface.id).copy(locationInformationEnabled=settings.gps,vehicleSpeedEnabled=settings.gps && VehicleSpeedPolicy.gearMap(getSharedPreferences("receiver-ui",0).getString("vehicleGearMap", "") ?: "").isNotEmpty())
            val endpoint=Iap2WiredCarPlayEndpoint(listOf(literal),config.port,identity.publicKeyHex,config.sourceVersion,deviceId)
            log("Wired iAP2 authentication / StartCarPlaySession")
            Iap2WiredControlClient(iap,Iap2MfiAuthenticationClient(mfi)).run(identification,endpoint,availableCurrentMilliAmps=500,
                timeoutMillis=24*60*60*1000L,locationProvider=location,onProgress={ log(FailureDiagnostics.progress(it)) })
        } finally {
            if(!transferred) first.close()
            cleanup(); if(id==generation.get()) selectedName=null
        }
    }
    private fun cleanup() {
        val values=synchronized(resources) { resources.toList().reversed().also { resources.clear() } }
        values.forEach { runCatching { it.close() } }; media=null; activeSession=null
    }
    override fun onRevoke() { log("VPN permission revoked"); stopSelf(); super.onRevoke() }
    override fun onDestroy() { running.set(false); generation.incrementAndGet(); main.removeCallbacks(poll); unregisterReceiver(receiver); cleanup(); worker.shutdownNow(); instance=null; super.onDestroy() }
    companion object {
        @Volatile var instance:LegacyWiredService?=null
        @Volatile private var videoSurface:Surface?=null
        fun setVideoSurface(value:Surface?,force:Boolean=false) { videoSurface=value; instance?.setSurface(value,force) }
    }
}
