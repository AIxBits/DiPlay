package local.airuize.receiver

/** Preserve endpoint byte-stream order even if completion notifications arrive out of order. */
class OrderedUsbPipeline<T:Any>(private val slots:List<T>,private val submit:(T)->Boolean,
    private val await:()->T?,private val copy:(T)->ByteArray) {
    init {require(slots.size in 1..16);require(slots.distinct().size==slots.size)}
    private val pending=java.util.IdentityHashMap<T,Long>()
    private val ready=java.util.TreeMap<Long,ByteArray>()
    private var submitted=0L;private var delivered=0L
    private var started=false
    var outOfOrder=0L;private set
    var completions=0L;private set
    private fun queue(slot:T) {
        if(!submit(slot))throw java.io.IOException("NCM USB pipeline queue failed")
        pending[slot]=submitted++
    }
    fun next():ByteArray {
        if(!started) {started=true;slots.forEach(::queue)}
        while(true) {
            if(ready.isNotEmpty())ready.remove(delivered)?.let {delivered++;return it}
            val slot=await() ?: throw java.io.IOException("NCM USB requestWait returned null")
            val sequence=pending.remove(slot) ?: throw java.io.IOException("NCM unexpected USB completion")
            val bytes=copy(slot) // Copy before immediately requeueing the direct buffer.
            completions++;if(sequence!=delivered)outOfOrder++
            if(sequence==delivered) {delivered++;queue(slot);return bytes}
            ready[sequence]=bytes
            if(ready.size>slots.size*2)throw java.io.IOException("NCM completion ordering exceeded bound")
            queue(slot)
        }
    }
    fun summary()="NCM pipeline depth=${slots.size} submitted=$submitted completions=$completions delivered=$delivered reordered=$outOfOrder pending=${pending.size} staged=${ready.size}"
}

object UsbReceiveDiagnostics {
    @Volatile var depth=8
    fun sanitize(value:Int)=if(value in listOf(1,8,16))value else 8
}
