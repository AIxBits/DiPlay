package local.airuize.receiver

import org.junit.Test
import org.junit.Assert.*
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class LegacyAsyncUsbReaderTest {
    @Test fun consumerInterruptionIsReportedAsIoFailureAndPreservesInterruptFlag() {
        val stop=CountDownLatch(1);val entering=CountDownLatch(1)
        val failure=java.util.concurrent.atomic.AtomicReference<Throwable>()
        val reader=LegacyAsyncUsbReader({stop.await();null},{stop.countDown()},{})
        val consumer=Thread {
            try { entering.countDown();reader.next(10000);failure.set(AssertionError("interruption lost")) }
            catch(error:IOException) {
                if(error.cause !is InterruptedException || !Thread.currentThread().isInterrupted)
                    failure.set(AssertionError("interrupt cause or flag lost",error))
            } catch(error:Throwable) { failure.set(error) }
        }
        try {
            consumer.start();assertTrue(entering.await(2,TimeUnit.SECONDS));consumer.interrupt();consumer.join(2000)
            assertFalse(consumer.isAlive);assertNull(failure.get())
        } finally { reader.close() }
    }
    @Test fun timedConsumerDoesNotCancelOrLoseAnInFlightRead() {
        val ready=CountDownLatch(1);val data=CountDownLatch(1);val cancel=AtomicInteger();val released=CountDownLatch(1)
        val reader=LegacyAsyncUsbReader({ ready.countDown();data.await();byteArrayOf(1,2,3) },{cancel.incrementAndGet();data.countDown()},{released.countDown()})
        try {
            assertTrue(ready.await(2,TimeUnit.SECONDS))
            assertNull(reader.next(20));assertEquals(0,cancel.get())
            data.countDown();assertArrayEquals(byteArrayOf(1,2,3),reader.next(2000))
        } finally { reader.close() }
        assertTrue(released.await(2,TimeUnit.SECONDS));assertEquals(1,cancel.get())
        reader.close();assertEquals(1,cancel.get())
    }
    @Test fun fullQueueAppliesBackpressureAndPreservesEveryChunkInOrder() {
        val reads=AtomicInteger();val second=CountDownLatch(1);val tail=CountDownLatch(1)
        val reader=LegacyAsyncUsbReader({
            val index=reads.incrementAndGet()
            if(index==2) second.countDown()
            if(index>8) tail.await()
            byteArrayOf(index.toByte())
        },{tail.countDown()},{},capacity=1)
        try {
            assertTrue(second.await(2,TimeUnit.SECONDS));assertEquals(2,reads.get())
            for(i in 1..8) assertArrayEquals(byteArrayOf(i.toByte()),reader.next(2000))
        } finally { reader.close() }
    }
    @Test fun readFailureReachesConsumerWithOriginalCause() {
        val failed=CountDownLatch(1)
        val reader=LegacyAsyncUsbReader({failed.countDown();throw IOException("synthetic USB completion failure")},{},{})
        try {
            assertTrue(failed.await(2,TimeUnit.SECONDS))
            try { reader.next(2000);fail("failure lost") }
            catch(error:IOException) { assertEquals("synthetic USB completion failure",error.cause?.message) }
        } finally { reader.close() }
    }
    @Test fun closingWakesPendingConsumerAndReleasesWorker() {
        val waiting=CountDownLatch(1);val stop=CountDownLatch(1);val released=CountDownLatch(1)
        val reader=LegacyAsyncUsbReader({waiting.countDown();stop.await();null},{stop.countDown()},{released.countDown()})
        assertTrue(waiting.await(2,TimeUnit.SECONDS));reader.close()
        assertTrue(released.await(2,TimeUnit.SECONDS))
        try { reader.next(20);fail("closed reader accepted") } catch(_:IOException) {}
    }
}
