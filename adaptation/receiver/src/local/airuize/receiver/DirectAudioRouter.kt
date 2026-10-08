package local.airuize.receiver

import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Wired-session ingress only. Control/video stay on TUN; authentication is unchanged. */
object DirectAudioRouter {
    private val sessions=ConcurrentHashMap<String,Session>()
    private fun key(address:ByteArray)=address.joinToString("") { "%02x".format(it.toInt() and 255) }
    fun open(local:InetAddress,enabled:Boolean):Session {
        require(local.address.size==16)
        val session=Session(local.address.copyOf(),enabled)
        check(sessions.putIfAbsent(key(local.address),session)==null) {"Direct audio session already active"}
        return session
    }
    fun bind(local:InetAddress?,peer:InetAddress?,port:Int,deliver:(ByteArray)->Unit):Binding? {
        if(local==null || peer==null || local.address.size!=16 || peer.address.size!=16)return null
        return sessions[key(local.address)]?.bind(peer.address,port,deliver)
    }
    class Binding internal constructor(private val owner:Session,val port:Int,internal val peer:ByteArray,private val deliver:(ByteArray)->Unit):java.io.Closeable {
        private var active=true
        val packets=AtomicLong();val invalidLength=AtomicLong();val badChecksum=AtomicLong()
        @Synchronized internal fun offer(bytes:ByteArray):Boolean {
            if(!active)return false
            packets.incrementAndGet();deliver(bytes);return true
        }
        override fun close(){synchronized(this){active=false};owner.remove(this)}
        fun summary()="directPackets=${packets.get()} directInvalidLength=${invalidLength.get()} directBadChecksum=${badChecksum.get()}"
    }
    class Session internal constructor(private val local:ByteArray,val enabled:Boolean):java.io.Closeable {
        private val active=AtomicBoolean(true)
        private val flows=ConcurrentHashMap<Int,Binding>()
        internal fun bind(peer:ByteArray,port:Int,deliver:(ByteArray)->Unit):Binding? {
            if(!enabled || !active.get())return null
            val binding=Binding(this,port,peer.copyOf(),deliver)
            check(flows.putIfAbsent(port,binding)==null){"Direct audio port already registered"}
            if(!active.get()){binding.close();return null}
            return binding
        }
        internal fun remove(binding:Binding){flows.remove(binding.port,binding)}
        /** True means consumed: never send it to TUN a second time. Unsupported IP uses socket fallback. */
        fun route(frame:ByteArray,start:Int,length:Int):Boolean {
            if(!enabled || !active.get())return false
            val view=Ipv6TransportView.parse(frame,start,length) ?: return false
            // Extended headers/fragments retain kernel processing, including reassembly/checksums.
            if(view.protocol!=17 || (frame[start+6].toInt() and 255)!=17 || view.offset+4>view.end)return false
            fun u16(at:Int)=((frame[at].toInt() and 255) shl 8) or (frame[at+1].toInt() and 255)
            val at=view.offset;val flow=flows[u16(at+2)] ?: return false
            for(i in 0..15)if(frame[start+8+i]!=flow.peer[i] || frame[start+24+i]!=local[i])return false
            if(at+8>view.end){flow.invalidLength.incrementAndGet();return true}
            val size=u16(at+4)
            if(size<8 || at+size>view.end){flow.invalidLength.incrementAndGet();return true}
            if(u16(at+6)==0 || !validChecksum(frame,start,at,size)){flow.badChecksum.incrementAndGet();return true}
            return flow.offer(frame.copyOfRange(at+8,at+size))
        }
        override fun close(){
            if(!active.compareAndSet(true,false))return
            sessions.remove(key(local),this)
            flows.values.toList().forEach {it.close()};flows.clear()
        }
    }
    /** IPv6 UDP pseudoheader plus the UDP header/payload, including odd byte padding. */
    internal fun validChecksum(frame:ByteArray,start:Int,udp:Int,size:Int):Boolean {
        var sum=size+17
        fun add(at:Int,end:Int){var i=at;while(i+1<end){sum+=((frame[i].toInt() and 255) shl 8) or (frame[i+1].toInt() and 255);i+=2};if(i<end)sum+=(frame[i].toInt() and 255) shl 8}
        add(start+8,start+40);add(udp,udp+size)
        while(sum ushr 16!=0)sum=(sum and 65535)+(sum ushr 16)
        return sum==65535
    }
}
