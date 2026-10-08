package local.airuize.receiver

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.iap2.session.Iap2Session
import com.shilapi.xcertplay.mfi.*
import com.shilapi.xcertplay.transport.*
import java.io.File
import java.io.Closeable
import java.net.*
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import javax.jmdns.*

/** A wireless-only controller: existing car AP -> RFCOMM -> iAP2 -> AirPlay media/tunnel. */
class LegacyWirelessController(private val context: Context, private val sink: LegacyMediaSink,
    private val log: (String) -> Unit, private val displayWidth: Int = 1024, private val displayHeight: Int = 600, private val gpsEnabled: Boolean = true,
    private val onConnectionFailure: (String) -> Unit = {},
    private val settings: ReceiverSettings = ReceiverSettings(), private val onHostUi: ()->Unit = {}) : Closeable {
    private val closed = CloseGate()
    private val resources = ArrayList<() -> Unit>()
    private val lock = Any()
    @Volatile var activeSession: AirPlaySession? = null
        private set
    private val sessions = Collections.synchronizedList(ArrayList<AirPlaySession>())
    private var multicastLock: WifiManager.MulticastLock? = null
    private val firstFrame = AtomicBoolean(false)
    private val tunnelAccepted = AtomicBoolean(false)
    private val handoff = WirelessHandoff()
    private val handoffTimerStarted = AtomicBoolean(false)
    @Volatile private var closeBluetoothBootstrap: (() -> Unit)? = null
    private val acceptedConnections = java.util.concurrent.atomic.AtomicInteger()
    private val audioSetupRequests = java.util.concurrent.atomic.AtomicInteger()
    private val microphoneNegotiatedRequests = java.util.concurrent.atomic.AtomicInteger()
    @Volatile private var microphoneNegotiation = "未收到音频SETUP"
    private val discoveredControls = java.util.concurrent.atomic.AtomicInteger()
    private val resolvedControls = java.util.concurrent.atomic.AtomicInteger()
    @Volatile private var listenerSocket: ServerSocket? = null
    @Volatile private var starter: Thread? = null
    private fun progress(text: String) { log(FailureDiagnostics.progress(text)) }
    private fun failure(stage: String, error: Throwable) {
        FailureDiagnostics.lines(stage, error).forEach(log)
        log("$stage 失败：${error.javaClass.simpleName}")
    }
    private fun startupFailed(stage: String, error: Throwable) {
        if (closed.get()) return
        failure(stage, error)
        if (activeSession != null || tunnelAccepted.get()) log("蓝牙通道结束，保留已经建立的 Wi-Fi 会话")
        else { close(); onConnectionFailure("启动失败：$stage") }
    }
    fun frameRendered() { if (firstFrame.compareAndSet(false, true)) log("已输出首帧：CarPlay 视频接收和解码链路已运行") }
    fun diagnosticSummary(): String = "连接统计：已停止=${closed.get()}；TCP接入=${acceptedConnections.get()}；会话激活=${activeSession != null}；已输出首帧=${firstFrame.get()}；iAP隧道曾接入=${tunnelAccepted.get()}；${handoff.summary()}；车机GPS上报启用=$gpsEnabled（实际发送看定位日志）；CAN车速=只读接入（实际有效性见日志）；独立车速需要已核对挡位映射；音频焦点/持续重连=后台管理；方向盘语音=MCU回调实机通过；音频SETUP请求=${audioSetupRequests.get()}；已协商收音流累计=${microphoneNegotiatedRequests.get()}；最近收音协商=$microphoneNegotiation"
    private fun armHandoffWatchdog() {
        if (!handoffTimerStarted.compareAndSet(false, true)) return
        Thread({
            try {
                Thread.sleep(45000)
                when (handoff.timeoutAction(closed.get(), firstFrame.get())) {
                    WirelessHandoff.TimeoutAction.IGNORE -> {}
                    WirelessHandoff.TimeoutAction.KEEP_VIDEO -> log("无线交接超时：已有视频首帧，按原仓库保留活动CarPlay；隧道未就绪，不能标记完整交接")
                    WirelessHandoff.TimeoutAction.FAIL -> {
                        log("无线交接超时且没有视频首帧：按原仓库关闭无线栈并恢复可重试状态")
                        close()
                        onConnectionFailure("无线交接45秒超时，未取得视频首帧")
                    }
                }
            } catch (_: InterruptedException) {}
        }, "legacy-handoff-watchdog").apply {
            isDaemon = true
            own(this) { if (it !== Thread.currentThread()) it.interrupt() }
            start()
        }
    }
    private fun bluetoothProfileSnapshot(stage:String) {
        try {
            val adapter=BluetoothAdapter.getDefaultAdapter()
            log("蓝牙配置[$stage]：enabled=${adapter?.isEnabled}；HFP=${adapter?.getProfileConnectionState(android.bluetooth.BluetoothProfile.HEADSET)}；A2DP=${adapter?.getProfileConnectionState(android.bluetooth.BluetoothProfile.A2DP)}；0=断开/1=连接中/2=已连接/3=断开中；状态为系统汇总，不记录设备地址")
        } catch(error:Exception) { log("蓝牙配置[$stage]读取失败：${error.javaClass.simpleName}") }
    }
    private fun completeHandoff() {
        if (closed.get()) return
        val release = closeBluetoothBootstrap ?: return
        if (!handoff.claimCompletion()) return
        Thread({
            if (!closed.get()) {
                try { bluetoothProfileSnapshot("交接前"); release(); log("无线交接完成：只释放应用RFCOMM/iAP2启动通道，保留Wi-Fi；未关闭蓝牙适配器或主动断开HFP/A2DP"); bluetoothProfileSnapshot("交接后") }
                catch (e: Exception) { if (!closed.get()) failure("释放蓝牙启动通道", e) }
            }
        }, "legacy-wireless-handoff").apply { isDaemon = true; start() }
    }
    private fun <T> own(value: T, dispose: (T) -> Unit): T = synchronized(lock) {
        if (closed.get()) { dispose(value); throw IllegalStateException("Receiver stopped") }
        resources.add { dispose(value) }; value
    }
    fun start(phone: BluetoothDevice, microphone: Boolean) {
        Thread({ bootstrap(phone, microphone) }, "legacy-carplay-start").apply { isDaemon = true; starter = this; start() }
    }
    private fun bootstrap(phone: BluetoothDevice, microphone: Boolean) {
        var stage = "检查车机热点和认证素材"
        fun step(value: String) { stage = value; log("启动阶段：$value") }
        try {
            step(stage)
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: error("Android 蓝牙不可用")
            check(adapter.isEnabled) { "请开启蓝牙" }
            val wifi = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val power = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            val wake = power.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "DiPlay:wireless").apply { setReferenceCounted(false) }
            own(wake) { if(it.isHeld) it.release() }; wake.acquire()
            val radio = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "DiPlay:wireless").apply { setReferenceCounted(false) }
            own(radio) { if(it.isHeld) it.release() }; radio.acquire()
            val network = own(LegacyNetwork(context, settings, { closed.get() }, log)) { it.close() }
            val access = network.prepare()
            val ni = access.iface; val host = access.address
            val btMac = adapter.address ?: error("无法读取蓝牙地址")
            val preferences = context.getSharedPreferences("receiver-identity", Context.MODE_PRIVATE)
            val deviceId = ni.hardwareAddress?.takeIf { it.size == 6 }?.joinToString(":") { "%02X".format(it.toInt() and 255) }
                ?: preferences.getString("deviceId", null) ?: "02:" + UUID.randomUUID().toString().replace("-", "").take(10).chunked(2).joinToString(":")
            preferences.edit().putString("deviceId", deviceId).commit()
            val identity = loadIdentity(context)
            val pairingPreferences = context.getSharedPreferences("receiver-pairings", Context.MODE_PRIVATE)
            val pairings = PairingStore { id, bytes -> pairingPreferences.edit().putString(id, android.util.Base64.encodeToString(bytes, 2)).commit() }
            pairingPreferences.all.forEach { (id, value) -> if (value is String) runCatching { pairings.save(id, android.util.Base64.decode(value, 2)) } }
            val authenticationDirectory = File(context.filesDir, "offline-mfi")
            authenticationDirectory.mkdirs()
            for (name in listOf("identity.pk8", "certificate.p7b")) {
                val destination = File(authenticationDirectory, name)
                context.assets.open("offline-mfi/$name").use { input -> destination.outputStream().use { input.copyTo(it) } }
            }
            val mfi = LocalMfiAuthenticationClient.load(authenticationDirectory)
            val config = AirPlayConfig("DiPlay Legacy", deviceId, btMac, "950.7.1",
                settings.display(), rightHandDrive = settings.rightHandDrive, hevc = settings.hevc && CodecCapabilities.hevc(), microphone = microphone,
                opus = settings.opus && CodecCapabilities.opus(), icons = LegacyOem.icons(),
                manufacturer = "DiPlay Legacy", model = "iMX6-Android43", oemLabel = "DiPlay")
            val media = CarPlayMediaEngine(sink, microphoneEnabled = microphone)
            val identification = Iap2IdentificationConfig("DiPlay Legacy", "iMX6-Android43", "DiPlay Legacy",
                identity.pairingId, "0.2.36", "iMX6", Iap2WirelessIdentification(btMac, access.ssid)).copy(locationInformationEnabled = gpsEnabled, vehicleSpeedEnabled = gpsEnabled && VehicleSpeedPolicy.gearMap(context.getSharedPreferences("receiver-ui",0).getString("vehicleGearMap", "") ?: "").isNotEmpty())
            val channel = access.channel
            if (channel == 0) log("热点信道未知，将按上游允许的未知信道值尝试连接")
            step("准备无线启动参数")
            val endpoint = Iap2WirelessCarPlayEndpoint(access.ssid, access.password, channel,
                Iap2WirelessSecurity.WPA_WPA2, listOf(host.hostAddress), config.port, deviceId, identity.publicKeyHex, config.sourceVersion)
            val locationRequest = own(Iap2LocationRequest()) { it.components = null }
            media.setIapTunnelHandler { stream ->
                if (closed.get()) false else {
                    tunnelAccepted.set(true)
                    own(stream) { it.close() }
                    Thread({
                        try {
                            val tunnel = own(Iap2Session.openTunnel(stream)) { it.close() }
                            log("Wi-Fi iAP2 通道已建立")
                            val gps = if (gpsEnabled) own(LegacyVehicleLocationProvider(context, "Wi-Fi", log)) { it.close() } else null
                            try { Iap2WirelessControlClient(tunnel, Iap2MfiAuthenticationClient(mfi)).run(identification, endpoint,
                                timeoutMillis = Iap2WirelessControlClient.NO_TIMEOUT_MILLIS,
                                locationProvider = gps, locationRequest = locationRequest, continueLocationRequest = true,
                                onReady = {
                                    if (!closed.get()) {
                                        handoff.tunnelReady()
                                        log("Wi-Fi iAP2 已完成认证和订阅，控制隧道就绪")
                                        completeHandoff()
                                    }
                                }, onProgress = { progress("Wi-Fi: $it") }) } finally { gps?.close() }
                        } catch (e: Exception) { if (!closed.get()) failure("Wi-Fi iAP2", e) }
                    }, "legacy-iap-tunnel").apply { isDaemon = true; start() }
                    true
                }
            }
            step("绑定 AirPlay TCP 监听端口")
            val server = own(LegacyListenerBinder.bind(InetSocketAddress(host, config.port), { closed.get() }, { attempt ->
                if (attempt == 1) log("AirPlay端口被占用，最多等待5秒；不修改端口、不终止其他进程")
            })) { it.close() }
            listenerSocket = server
            Thread({
                try {
                    while (!closed.get()) {
                        val socket = own(server.accept()) { it.close() }
                        acceptedConnections.incrementAndGet()
                        log("收到 AirPlay TCP 连接")
                        socket.tcpNoDelay = true; socket.keepAlive = true
                        val session = AirPlaySession(socket, config, identity, pairings, mfi,
                            object : AirPlaySessionListener {
                                override fun onSessionActive(session: AirPlaySession) { activeSession = session; log("AirPlay 会话已激活，等待画面") }
                                override fun onSessionEnded(session: AirPlaySession) {
                                    val wasActive = activeSession === session
                                    if(wasActive) activeSession = null
                                    sessions.remove(session); log("AirPlay 会话结束")
                                    if(wasActive && !closed.get()) onConnectionFailure("Session disconnected")
                                }
                                override fun onHostUiRequested(session: AirPlaySession) { if(activeSession === session) onHostUi() }
                                override fun onTransportError(message: String) { log("AirPlay 传输错误") }
                                override fun onDebugLog(message: String) {
                                    AirPlayDiagnostics.summary(message)?.let { safe ->
                                        if (safe.startsWith("手机音频SETUP请求：")) audioSetupRequests.incrementAndGet()
                                        if (safe.startsWith("麦克风已协商：")) microphoneNegotiatedRequests.incrementAndGet()
                                        if (safe.startsWith("麦克风已协商：") || safe.startsWith("麦克风未建立：")) microphoneNegotiation = safe
                                        log(safe)
                                    }
                                }
                                override fun onCommand(session: AirPlaySession, type: String, params: Map<String, Any?>) {
                                    AirPlayDiagnostics.command(type, params)?.let(log)
                                    if (!closed.get() && activeSession === session && handoff.request(type)) {
                                        log("手机请求释放蓝牙启动通道；等待 Wi-Fi iAP2 认证就绪")
                                        completeHandoff()
                                        armHandoffWatchdog()

                                    }
                                }
                                override fun onControlFailure(stage: String, error: Throwable) { failure("AirPlay $stage", error) }
                            }, media)
                        own(session) { it.close() }; sessions.add(session); session.start()
                    }
                } catch (e: Exception) { if (!closed.get()) failure("AirPlay 监听", e) }
            }, "legacy-airplay-server").apply { isDaemon = true; start() }
            step("获取 Wi-Fi 组播锁")
            val multicast = wifi.createMulticastLock("legacy-carplay-mdns").apply { setReferenceCounted(false) }
            own(multicast) { if (it.isHeld) it.release() }; multicast.acquire(); multicastLock = multicast
            step("初始化 mDNS")
            val dns = own(JmDNS.create(host, "DiPlay-Legacy")) { it.close() }
            val records = linkedMapOf("deviceid" to deviceId, "features" to "0x44540380,0x61", "flags" to "0x4",
                "model" to config.model, "srcvers" to config.sourceVersion, "protovers" to "1.1", "pi" to identity.pairingId, "pk" to identity.publicKeyHex)
            step("创建 AirPlay mDNS 服务记录")
            val service = ServiceInfo.create("_airplay._tcp.local.", config.deviceName, config.port, 0, 0, records)
            step("发布 AirPlay mDNS 服务")
            dns.registerService(service)
            step("监听 iPhone 控制服务")
            dns.addServiceListener("_carplay-ctrl._tcp.local.", object : ServiceListener {
                override fun serviceAdded(event: ServiceEvent) {
                    if (!closed.get()) {
                        discoveredControls.incrementAndGet()
                        log("发现 iPhone 控制服务，正在解析地址")
                        event.dns.requestServiceInfo(event.type, event.name, true)
                    }
                }
                override fun serviceRemoved(event: ServiceEvent) {}
                override fun serviceResolved(event: ServiceEvent) {
                    val peer = event.info.inet4Addresses.firstOrNull() ?: return
                    val port = event.info.port
                    if (port !in 1..65535 || closed.get()) return
                    resolvedControls.incrementAndGet()
                    log("iPhone 控制服务 IPv4 解析成功")
                    Thread({
                        try {
                            val socket = own(Socket()) { it.close() }
                            try {
                                socket.bind(InetSocketAddress(host, 0)); socket.connect(InetSocketAddress(peer, port), 2000); socket.soTimeout = 2000
                                val request = "GET /ctrl-int/1/connect HTTP/1.1\r\nHost: ${peer.hostAddress}:$port\r\nUser-Agent: AirPlay/${config.sourceVersion}\r\nAirPlay-Receiver-Device-ID: ${deviceId.replace(":", "")}\r\nConnection: close\r\n\r\n"
                                socket.getOutputStream().write(request.toByteArray(Charsets.US_ASCII)); socket.getOutputStream().flush()
                                val status = socket.getInputStream().bufferedReader(Charsets.US_ASCII).readLine()
                                log("发现 iPhone 控制服务；HTTP 状态=" + (Regex("^HTTP/[^ ]+ ([0-9]{3})").find(status ?: "")?.groupValues?.get(1) ?: "unknown"))
                            } finally { socket.close() }
                        } catch (e: Exception) { if (!closed.get()) failure("iPhone 控制服务探测", e) }
                    }, "legacy-control-discovery").apply { isDaemon = true; start() }
                }
            })
            Thread({
                try {
                    repeat(6) {
                        Thread.sleep(15000)
                        if (closed.get() || firstFrame.get()) return@Thread
                        val neighbours = try {
                            File("/proc/net/arp").readLines().drop(1).count { row ->
                                val fields = row.trim().split(Regex("\\s+"))
                                fields.size >= 6 && fields[5] == ni.name && fields[2] == "0x2"
                            }.toString()
                        } catch (_: Exception) { "不可读" }
                        log("Wi-Fi 等待状态：ARP邻居=$neighbours；控制服务发现=${discoveredControls.get()}；IPv4解析=${resolvedControls.get()}；TCP连接=${acceptedConnections.get()}；会话激活=${activeSession != null}")
                    }
                } catch (_: InterruptedException) {}
            }, "legacy-wifi-diagnostics").apply { isDaemon = true; own(this) { it.interrupt() }; start() }
            log("接收端已监听 ${ni.name}；开始蓝牙握手")
            bluetoothProfileSnapshot("新增RFCOMM前")
            step("连接蓝牙 RFCOMM")
            val socket = own(phone.createRfcommSocketToServiceRecord(UUID.fromString("00000000-deca-fade-deca-deafdecacafe"))) { it.close() }
            val connected = AtomicBoolean(false)
            val timeout = Thread({ try { Thread.sleep(12000); if (!connected.get()) socket.close() } catch (_: InterruptedException) {} }, "legacy-bt-timeout")
            timeout.isDaemon = true; timeout.start()
            try { socket.connect(); connected.set(true) } finally { timeout.interrupt() }
            val stream = own(BluetoothRfcommDuplexStream(socket)) { it.close() }
            step("建立蓝牙 iAP2 通道")
            val bootstrap = own(Iap2Session.openWireless(stream)) { it.close() }
            closeBluetoothBootstrap = {
                try { bootstrap.close() } finally { try { stream.close() } finally { socket.close() } }
            }
            completeHandoff()
            log("蓝牙 iAP2 已建立；开始认证")
            step("蓝牙 iAP2 认证和无线启动")
            val gps = if (gpsEnabled) own(LegacyVehicleLocationProvider(context, "蓝牙", log)) { it.close() } else null
            log("蓝牙控制策略：GPS启用=$gpsEnabled；超时=${if (gpsEnabled) "24小时" else "5分钟"}；定位只在手机订阅后开始")
            val result = try { Iap2WirelessControlClient(bootstrap, Iap2MfiAuthenticationClient(mfi)).run(identification, endpoint,
                timeoutMillis = if (gpsEnabled) 24 * 60 * 60 * 1000L else 300000L,
                locationProvider = gps, locationRequest = locationRequest, onProgress = ::progress)
            } finally { gps?.close() }
            log("蓝牙阶段结束：terminal=${result.terminal}；stage=${result.stage}；Wi-Fi配置发送=${result.wifiConfigurationsSent}；CarPlay启动发送=${result.carPlayStartSessionsSent}；继续等待 Wi-Fi 会话")
            // RFCOMM closing during the Wi-Fi handoff must not tear down the receiver.
        } catch (e: Exception) { startupFailed(stage, e) }
        catch (e: LinkageError) { startupFailed(stage, e) }
    }
    override fun close() {
        closed.close {
            activeSession = null
            val startup = starter?.takeIf { it !== Thread.currentThread() }
            startup?.interrupt()
            // Release the listening port before slower mDNS and Bluetooth cleanup.
            try { listenerSocket?.close() } catch (_: Exception) {}
            val all = synchronized(lock) { resources.toList().reversed().also { resources.clear() } }
            all.forEach { try { it() } catch (_: Exception) {} }
            try { startup?.join() } finally {
                sink.close()
                sessions.clear(); listenerSocket = null; closeBluetoothBootstrap = null
                handoff.reset(); firstFrame.set(false); tunnelAccepted.set(false)
                log("无线栈已释放：定位订阅/共享请求、RFCOMM、隧道、监听、mDNS和媒体已清理")
            }
        }
    }
    companion object {
        private fun unquote(value: String): String = value.removeSurrounding("\"")
        fun loadIdentity(context: Context): AirPlayIdentity {
            val preferences = context.getSharedPreferences("receiver-identity", Context.MODE_PRIVATE)
            val saved = preferences.getString("private", null)
            if (saved != null) return AirPlayIdentity(android.util.Base64.decode(saved, 2),
                android.util.Base64.decode(preferences.getString("public", ""), 2), preferences.getString("pairingId", "")!!)
            return AirPlayIdentity.generate().also {
                preferences.edit().putString("private", android.util.Base64.encodeToString(it.privateKey, 2))
                    .putString("public", android.util.Base64.encodeToString(it.publicKey, 2)).putString("pairingId", it.pairingId).commit()
            }
        }
    }
}
