package local.airuize.receiver

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbRequest
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Timed consumers over the sole SDK18 requestWait worker. No chunk drops. */
class LegacyAsyncUsbReader(private val read:()->ByteArray?, private val cancel:()->Unit,
                         private val release:()->Unit, capacity:Int=8) : Closeable {
    private val running=AtomicBoolean(true)
    private val closed=AtomicBoolean(false)
    private val chunks=ArrayBlockingQueue<ByteArray>(capacity)
    private val readChunks=java.util.concurrent.atomic.AtomicLong()
    private val backpressureWaits=java.util.concurrent.atomic.AtomicLong()
    private val enqueueMaxMs=java.util.concurrent.atomic.AtomicLong()
    private val highWater=java.util.concurrent.atomic.AtomicLong()
    private fun maximum(counter:java.util.concurrent.atomic.AtomicLong,value:Long) {
        var old=counter.get();while(value>old && !counter.compareAndSet(old,value))old=counter.get()
    }
    @Volatile private var failure:Throwable?=null
    private val worker=Thread({
        try {
            while(running.get()) {
                val bytes=read()
                if(bytes==null) { if(running.get()) Thread.sleep(1); continue }
                readChunks.incrementAndGet()
                val begin=System.nanoTime()
                while(running.get() && !chunks.offer(bytes,100,TimeUnit.MILLISECONDS)) {backpressureWaits.incrementAndGet() /* Never discard NTB bytes. */ }
                maximum(enqueueMaxMs,(System.nanoTime()-begin)/1000000L)
                maximum(highWater,chunks.size.toLong())
            }
        } catch(error:Exception) { if(running.get()) failure=error }
        finally { running.set(false); runCatching(release) }
    },"legacy-ncm-request").apply { isDaemon=true;start() }
    fun next(timeoutMillis:Long):ByteArray? {
        require(timeoutMillis>0)
        fun checkState() {
            failure?.let { throw IOException("NCM asynchronous USB read failed",it) }
            if(!running.get()) throw IOException("NCM asynchronous USB reader closed")
        }
        checkState()
        val result=try { chunks.poll(timeoutMillis,TimeUnit.MILLISECONDS) }
        catch(error:InterruptedException) { Thread.currentThread().interrupt();throw IOException("NCM USB consumer interrupted",error) }
        checkState()
        return result
    }
    fun summary()="NCM receive queue chunks=${readChunks.get()} queued=${chunks.size} highWater=${highWater.get()} capacity=${chunks.size+chunks.remainingCapacity()} backpressureWaits=${backpressureWaits.get()} enqueueMaxMs=${enqueueMaxMs.get()}"
    override fun close() {
        if(!closed.compareAndSet(false,true)) return
        running.set(false)
        runCatching(cancel)
        worker.interrupt()
        if(Thread.currentThread()!==worker) try { worker.join(250) }
        catch(_:InterruptedException) { Thread.currentThread().interrupt() }
        chunks.clear()
    }
}

/** Original asynchronous/direct-buffer receive strategy, adapted to API18 queue(buffer,length). */
class LegacyUsbReadPump(connection:UsbDeviceConnection, endpoint:UsbEndpoint, report:(String)->Unit) : Closeable {
    private val buffers=java.util.IdentityHashMap<UsbRequest,ByteBuffer>()
    private val requests=ArrayList<UsbRequest>()
    private lateinit var pipeline:OrderedUsbPipeline<UsbRequest>
    private val released=AtomicBoolean(false)
    private val reader:LegacyAsyncUsbReader
    @Volatile private var pipelineSnapshot="NCM pipeline initializing"
    init {
        try {repeat(UsbReceiveDiagnostics.sanitize(UsbReceiveDiagnostics.depth)) {
            val request=UsbRequest();requests.add(request)
            if(!request.initialize(connection,endpoint))throw IOException("NCM USB request initialization failed")
            buffers[request]=ByteBuffer.allocateDirect(16384)
        }}catch(error:Exception){requests.forEach {runCatching {it.close()}};throw error}
        pipeline=OrderedUsbPipeline(requests,{request -> val buffer=buffers[request]!!;buffer.clear();request.queue(buffer,buffer.capacity())},
            {connection.requestWait()},{request -> val buffer=buffers[request]!!;val bytes=ByteArray(buffer.position());buffer.flip();buffer.get(bytes);bytes})
        report("NCM receive backend: SDK18 UsbRequest/direct buffers; depth=${requests.size} bytesPerRequest=16384; sole requestWait worker; ordered delivery")
        var prioritySet=false
        var lastSnapshot=0L
        reader=LegacyAsyncUsbReader({
            if(!prioritySet) {prioritySet=true
                val applied=runCatching {android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_MORE_FAVORABLE);android.os.Process.getThreadPriority(android.os.Process.myTid())}.getOrNull()
                report("NCM receive priority nice=${applied ?: 999} requested=-1 applied=${applied == -1}")
            }
            val bytes=pipeline.next()
            val now=System.nanoTime()
            if(lastSnapshot==0L || now-lastSnapshot>=5000000000L) {lastSnapshot=now;pipelineSnapshot=pipeline.summary()}
            bytes.takeIf {it.isNotEmpty()}
        },{requests.forEach {runCatching {it.cancel()}};Unit},{
            if(released.compareAndSet(false,true))requests.forEach {request -> runCatching {request.cancel()};runCatching {request.close()}}
        })
    }
    fun next(timeoutMillis:Long)=reader.next(timeoutMillis)
    fun summary()=reader.summary()+"\n"+pipelineSnapshot
    override fun close()=reader.close()
}
