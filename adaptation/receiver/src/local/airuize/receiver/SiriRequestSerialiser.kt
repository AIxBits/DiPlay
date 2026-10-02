package local.airuize.receiver

import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/** Serialize direct upstream invokeSiri calls; discard requests for ended sessions. */
class SiriRequestSerialiser : Closeable {
    private val closed = AtomicBoolean(false)
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "legacy-siri-control").apply { isDaemon = true }
    }
    fun submit(current: () -> Boolean, invoke: () -> Unit,
        result: (String?) -> Unit, discarded: () -> Unit = {}): Boolean {
        if (closed.get()) return false
        return try {
            worker.execute {
                if (closed.get() || !current()) { discarded(); return@execute }
                var failure: String? = null
                try { invoke() } catch (error: Exception) { failure = error.javaClass.simpleName }
                result(failure)
            }
            true
        } catch (_: RejectedExecutionException) { false }
    }
    override fun close() { if (closed.compareAndSet(false, true)) worker.shutdownNow() }
}
