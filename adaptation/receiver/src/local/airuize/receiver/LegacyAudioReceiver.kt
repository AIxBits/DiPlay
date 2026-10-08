package local.airuize.receiver

import java.net.DatagramSocket
import java.net.DatagramPacket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Only the receiver touches the UDP socket. Decryption stays serial and ordered. */
class LegacyAudioReceiver(private val socket:DatagramSocket,private val ingress:LegacyAudioIngress,capacity:Int=128,local:java.net.InetAddress?=null,peer:java.net.InetAddress?=null):java.io.Closeable {
    private val queue=ArrayBlockingQueue<ByteArray>(capacity)
    @Volatile private var running=true
    @Volatile private var failure:Exception?=null
    val dropped=AtomicLong()
    val received=AtomicLong()
    val highWater=AtomicLong()
    @Volatile private var readerPriority=999
    private val direct=DirectAudioRouter.bind(local,peer,socket.localPort) {wire ->
        if(running) {ingress.directReceived(wire);enqueue(wire)}
    }
    private fun enqueue(wire:ByteArray) {
        received.incrementAndGet()
        if(!queue.offer(wire))dropped.incrementAndGet()
        val size=queue.size.toLong();var old=highWater.get()
        while(size>old && !highWater.compareAndSet(old,size))old=highWater.get()
    }
    private val reader=Thread({
        runCatching {android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_MORE_FAVORABLE);readerPriority=android.os.Process.getThreadPriority(android.os.Process.myTid())}
        val buffer=ByteArray(4096)
        try {
            while(running) {
                val packet=DatagramPacket(buffer,buffer.size)
                socket.receive(packet)
                val wire=buffer.copyOf(packet.length)
                ingress.received(wire);enqueue(wire)
            }
        } catch(error:Exception) {if(running)failure=error}
    },"legacy-audio-udp").apply {isDaemon=true;start()}
    fun next(timeoutMs:Long=100):ByteArray? {
        failure?.let {throw java.io.IOException("Audio UDP receive failed",it)}
        if(!running)return null
        return queue.poll(timeoutMs,TimeUnit.MILLISECONDS)
    }
    fun summary()="Audio queue received=${received.get()} rejected=${dropped.get()} queued=${queue.size} highWater=${highWater.get()} capacity=${queue.remainingCapacity()+queue.size} readerNice=$readerPriority mode=${if(direct==null)"socket" else "direct-with-socket-fallback"} ${direct?.summary() ?: "directPackets=0"}"
    override fun close() {direct?.close();running=false;socket.close();reader.interrupt();queue.clear()}
}
