package local.airuize.receiver
import org.junit.Test
import org.junit.Assert.*
import java.net.*
class LegacyAudioReceiverTest {
    @Test(timeout=4000) fun socketKeepsDrainingWhileConsumerIsSlowAndCountsOverflow() {
        val socket=DatagramSocket(0,InetAddress.getByName("127.0.0.1"))
        val ingress=LegacyAudioIngress();val receiver=LegacyAudioReceiver(socket,ingress,2)
        try {
            DatagramSocket().use {sender -> repeat(20) {seq ->val payload=ByteArray(12);payload[3]=seq.toByte()
                sender.send(DatagramPacket(payload,payload.size,InetAddress.getByName("127.0.0.1"),socket.localPort))}}
            val deadline=System.nanoTime()+2_000_000_000L
            while(receiver.received.get()<20 && System.nanoTime()<deadline)Thread.sleep(5)
            assertEquals(20,receiver.received.get());assertEquals(18,receiver.dropped.get())
            assertEquals(0,receiver.next()!![3].toInt());assertEquals(1,receiver.next()!![3].toInt())
            assertTrue(ingress.summary().contains("udp=20"))
        } finally {receiver.close()}
        assertTrue(socket.isClosed);assertNull(receiver.next())
    }
    @Test fun runtimeStatisticsDoNotRefreshUiButFailuresDo() {
        assertFalse(RuntimeLogPolicy.updateUi("Audio ingress type=100 udp=400"))
        assertFalse(RuntimeLogPolicy.updateUi("媒体统计：音频RTP=400"))
        assertFalse(RuntimeLogPolicy.updateUi("iap2 rx=0x5001"))
        assertTrue(RuntimeLogPolicy.updateUi("NCM IPv6 failure=IOException"))
        assertTrue(RuntimeLogPolicy.updateUi("USB connection ended: detached"))
    }
}
