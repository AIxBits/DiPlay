package local.airuize.receiver

import android.content.Context
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pManager
import android.os.Looper
import java.io.Closeable
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Public API14 P2P group owner plus API18 vendor hotspot fallback. Never logs credentials. */
class LegacyNetwork(private val context: Context, private val settings: ReceiverSettings,
    private val stopped: ()->Boolean, private val log: (String)->Unit) : Closeable {
    data class Endpoint(val ssid: String, val password: String, val channel: Int, val iface: NetworkInterface, val address: Inet4Address)
    private val wifi = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var p2p: WifiP2pManager? = null
    private var p2pChannel: WifiP2pManager.Channel? = null
    private var changedAp = false
    private var oldConfig: WifiConfiguration? = null
    private var stationWasEnabled = false
    private var apWasEnabled = false
    private var directEnabledWifi = false
    private fun state() = (wifi.javaClass.getMethod("getWifiApState").invoke(wifi) as Number).toInt()
    private fun setAp(config: WifiConfiguration?, enabled: Boolean): Boolean =
        wifi.javaClass.getMethod("setWifiApEnabled", WifiConfiguration::class.java, java.lang.Boolean.TYPE).invoke(wifi, config, enabled) == true
    private fun unquote(value: String) = value.removeSurrounding("\"")
    fun prepare(): Endpoint {
        var ssid: String; var password: String; var channel = 0
        when(settings.networkMode) {
            "direct" -> {
                val manager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager ?: error("Wi-Fi Direct service unavailable")
                p2p = manager
                stationWasEnabled=wifi.isWifiEnabled
                oldConfig=runCatching { wifi.javaClass.getMethod("getWifiApConfiguration").invoke(wifi) as? WifiConfiguration }.getOrNull()
                apWasEnabled=runCatching { state()==13 }.getOrDefault(false)
                if(apWasEnabled) { changedAp=true; check(setAp(oldConfig,false)) }
                if (!wifi.isWifiEnabled) { directEnabledWifi=true; check(wifi.setWifiEnabled(true)) }
                val ready = CountDownLatch(1); var reason: Int? = null
                val link = manager.initialize(context, Looper.getMainLooper(), null); p2pChannel = link
                manager.createGroup(link, object: WifiP2pManager.ActionListener {
                    override fun onSuccess() { ready.countDown() }
                    override fun onFailure(value: Int) { reason = value; ready.countDown() }
                })
                check(ready.await(15, TimeUnit.SECONDS) && reason == null && !stopped()) { "Wi-Fi Direct group failed reason=$reason" }
                var credentials: Pair<String,String>? = null
                val deadline = System.nanoTime() + 10_000_000_000L
                while (!stopped() && credentials == null && System.nanoTime() < deadline) {
                    val found = CountDownLatch(1)
                    manager.requestGroupInfo(link) { group ->
                        if (group != null && group.isGroupOwner && !group.passphrase.isNullOrEmpty()) credentials = group.networkName to group.passphrase
                        found.countDown()
                    }
                    found.await(1, TimeUnit.SECONDS)
                }
                val pair = credentials ?: error("Wi-Fi Direct group credentials unavailable")
                ssid = pair.first; password = pair.second
            }
            "router" -> {
                check(wifi.isWifiEnabled) { "Connect head unit and iPhone to the same WPA2 router first" }
                val p = context.getSharedPreferences("receiver-ui", Context.MODE_PRIVATE)
                ssid = unquote(wifi.connectionInfo.ssid ?: "")
                password = p.getString("routerPassword", "")!!
                check(ssid.isNotBlank() && ssid != "<unknown ssid>" && password.length in 8..63) { "Router WPA2 password must be configured" }
                // Read current frequency is API21; channel 0 is the upstream unknown-channel value.
            }
            else -> {
                oldConfig = wifi.javaClass.getMethod("getWifiApConfiguration").invoke(wifi) as? WifiConfiguration
                apWasEnabled = state() == 13; stationWasEnabled = wifi.isWifiEnabled
                var ap = oldConfig ?: WifiConfiguration()
                if(settings.networkMode == "app") {
                    val p = context.getSharedPreferences("receiver-network", Context.MODE_PRIVATE)
                    val saved = p.getString("password", null) ?: UUID.randomUUID().toString().replace("-", "").take(16).also { p.edit().putString("password", it).commit() }
                    ap = WifiConfiguration().apply { SSID = "DiPlay-Legacy"; preSharedKey = saved; allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK) }
                }
                check(!ap.SSID.isNullOrBlank() && !ap.preSharedKey.isNullOrEmpty() && ap.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA_PSK)) { "Configure a WPA2 hotspot first" }
                if (!apWasEnabled || settings.networkMode == "app") {
                    changedAp = true
                    if(apWasEnabled) check(setAp(oldConfig, false))
                    if(stationWasEnabled) check(wifi.setWifiEnabled(false))
                    check(!stopped() && setAp(ap, true)) { "Hotspot rejected by firmware" }
                    val deadline = System.nanoTime() + 10_000_000_000L
                    while (!stopped() && state() != 13 && System.nanoTime() < deadline) Thread.sleep(250)
                    check(!stopped() && state() == 13) { "Hotspot startup timed out" }
                }
                ssid = unquote(ap.SSID); password = unquote(ap.preSharedKey)
                channel = try { ap.javaClass.getField("apChannel").getInt(ap).coerceIn(0,255) } catch(_:Exception) { 0 }
            }
        }
        val deadline = System.nanoTime() + 10_000_000_000L
        do {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces()).filter {
                it.isUp && !it.isLoopback && if(settings.networkMode == "direct") it.name.startsWith("p2p") else it.name.startsWith("wlan") || it.name.startsWith("ap")
            }
            for(ni in interfaces) {
                val address = Collections.list(ni.inetAddresses).filterIsInstance<Inet4Address>().firstOrNull { !it.isLoopbackAddress && !it.isAnyLocalAddress }
                if(address != null) { log("Network mode=${settings.networkMode} interface=${ni.name} ready=true"); return Endpoint(ssid,password,channel,ni,address) }
            }
            Thread.sleep(250)
        } while(!stopped() && System.nanoTime() < deadline)
        error("Network IPv4 address unavailable")
    }
    override fun close() {
        p2pChannel?.let { channel ->
            val removed=CountDownLatch(1)
            p2p?.removeGroup(channel,object:WifiP2pManager.ActionListener {
                override fun onSuccess() { removed.countDown() }
                override fun onFailure(reason:Int) { log("Wi-Fi Direct cleanup reason=$reason"); removed.countDown() }
            })
            if(Looper.myLooper() != Looper.getMainLooper()) runCatching { removed.await(2,TimeUnit.SECONDS) }
        }; p2pChannel = null
        if(directEnabledWifi && !stationWasEnabled) runCatching { wifi.setWifiEnabled(false) }
        if (changedAp) {
            runCatching { setAp(null, false); if(oldConfig != null) wifi.javaClass.getMethod("setWifiApConfiguration", WifiConfiguration::class.java).invoke(wifi, oldConfig)
                if(apWasEnabled) setAp(oldConfig, true) else if(stationWasEnabled) wifi.setWifiEnabled(true); Unit }
            .onFailure { log("Hotspot state restore failed=${it.javaClass.simpleName}") }
            changedAp = false
        }
    }
}
