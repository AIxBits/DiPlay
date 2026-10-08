package local.airuize.receiver
import java.util.concurrent.atomic.AtomicLong

/** Session-local diagnostic TCP read pause: bounded to 30 s, never discards encrypted frames. */
object VideoDiagnosticGate {
    @Volatile private var until=0L
    private val ports=java.util.concurrent.ConcurrentHashMap<Int,Boolean>()
    private val tcpPackets=AtomicLong();private val tcpBytes=AtomicLong();private val unknown=AtomicLong()
    fun paused(now:Long=System.nanoTime()):Boolean=until!=0L && now<until
    fun toggle(now:Long=System.nanoTime()):Boolean {until=if(paused(now))0L else now+30000000000L;return paused(now)}
    fun reset(){until=0L}
    fun register(port:Int){ports[port]=true}
    fun unregister(port:Int){ports.remove(port)}
    fun observe(frame:ByteArray,parsed:Ipv6TransportView?) {
        val view=parsed ?: run {unknown.incrementAndGet();return}
        if(view.protocol!=6) {if(view.protocol!=17 && view.protocol!=58)unknown.incrementAndGet();return}
        if(view.offset+20>view.end)return
        val port=((frame[view.offset+2].toInt() and 255) shl 8) or (frame[view.offset+3].toInt() and 255)
        if(!ports.containsKey(port))return
        val header=((frame[view.offset+12].toInt() ushr 4) and 15)*4
        if(header<20 || view.offset+header>view.end)return
        tcpPackets.incrementAndGet();tcpBytes.addAndGet((view.end-view.offset-header).toLong())
    }
    fun summary()="NCM video traffic scope=process packets=${tcpPackets.get()} payloadBytes=${tcpBytes.get()} tcpReadPaused=${paused()} unclassifiedIPv6=${unknown.get()}"
}
