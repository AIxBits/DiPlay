package local.airuize.receiver
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
class AudioDiagnosticDispatcherTest {
    @Test fun blockingCallbackDoesNotRunOnOrBlockProducer() {
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val finished=CountDownLatch(1)
        val caller=Thread.currentThread();var worker:Thread?=null
        try {
            AudioDiagnosticDispatcher.submit({worker=Thread.currentThread();entered.countDown();release.await(3,TimeUnit.SECONDS);finished.countDown()},"synthetic diagnostic")
            assertTrue(entered.await(2,TimeUnit.SECONDS))
            assertNotSame(caller,worker)
            assertEquals(1L,finished.count)
        } finally {release.countDown()}
        assertTrue(finished.await(2,TimeUnit.SECONDS))
    }
}
