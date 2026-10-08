package local.airuize.receiver

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Bounded, non-blocking producer path; callbacks execute only on the diagnostic worker. */
object AudioDiagnosticDispatcher {
    private val queue=ConcurrentLinkedQueue<()->Unit>()
    private val pending=AtomicInteger()
    private val omitted=AtomicLong()
    private val failed=AtomicLong()
    init {
        Thread({while(true) {
            val task=queue.poll()
            if(task==null) {try {Thread.sleep(20)} catch(_:InterruptedException){};continue}
            pending.decrementAndGet()
            try {task()} catch(_:Exception){failed.incrementAndGet()}
        }},"legacy-audio-diag").apply {isDaemon=true;start()}
    }
    fun submit(callback:(String)->Unit,message:String) {
        while(true) {
            val count=pending.get()
            if(count>=256){omitted.incrementAndGet();return}
            if(pending.compareAndSet(count,count+1))break
        }
        queue.offer {callback(message)}
    }
    fun summary()="Audio diagnostic queue pending=${pending.get()} omitted=${omitted.get()} callbackFailures=${failed.get()} capacity=256"
}
