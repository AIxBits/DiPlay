package local.airuize.receiver
import org.junit.Test
import org.junit.Assert.*
class LegacyAudioIngressTest {
    private fun wire(port:Int,sequence:Int):ByteArray=ByteArray(60).apply {
        this[0]=0x60;this[5]=20;this[6]=17
        this[42]=(port shr 8).toByte();this[43]=port.toByte();this[45]=20
        this[48]=0x80.toByte();this[50]=(sequence shr 8).toByte();this[51]=sequence.toByte()
    }
    @Test fun distinguishesBeforeTunGapFromSocketGapAndDecryptRejection() {
        val probe=LegacyAudioIngress();LegacyAudioIngress.register(3210,probe)
        try {
            for(seq in listOf(1,2,4)) {val w=wire(3210,seq);LegacyAudioIngress.observe(w,0,w.size,false);LegacyAudioIngress.observe(w,0,w.size,true)}
            for(seq in listOf(1,4))probe.received(wire(3210,seq).copyOfRange(48,60))
            probe.decrypted(true,4000);probe.decrypted(false,9000)
            val result=probe.summary()
            listOf("ncm=3","tunWritten=3","udp=2","decoded=1","rejected=1","ncmGaps=1","udpGaps=2","decryptMaxUs=9").forEach {assertTrue(result.contains(it))}
        } finally {LegacyAudioIngress.unregister(3210,probe)}
    }
    @Test fun ignoresOtherFlowMalformedLengthAndIpv6ExtensionWithoutRecordingAddresses() {
        val probe=LegacyAudioIngress();LegacyAudioIngress.register(3210,probe)
        try {
            LegacyAudioIngress.observe(wire(3211,1),0,60,false)
            val malformed=wire(3210,1);malformed[45]=19;LegacyAudioIngress.observe(malformed,0,60,false)
            val extension=wire(3210,1);extension[6]=44;LegacyAudioIngress.observe(extension,0,60,false)
            assertTrue(probe.summary().startsWith("ncm=0"))
        } finally {LegacyAudioIngress.unregister(3210,probe)}
    }
    @Test fun exposesOriginalSafeDiagnosticsButRejectsPayloadText() {
        assertNotNull(AirPlayDiagnostics.summary("Audio UDP receive buffer type=100 original=32768 requested=524288 actual=212992"))
        assertNotNull(AirPlayDiagnostics.summary("Receive: audio type=100 packets=9 seqGapLast=[expected=1 received=3 missing=2] ended=false"))
        assertNull(AirPlayDiagnostics.summary("Receive: audio type=100 secret=https://example.com"))
    }
    @Test(timeout=6000) fun realUdpDecryptCountersAreExportedWithoutPayload() {
        val key=ByteArray(32) { it.toByte() }
        val accepted=java.util.concurrent.CountDownLatch(2)
        val rejected=java.util.concurrent.CountDownLatch(1)
        val logs=java.util.Collections.synchronizedList(ArrayList<String>())
        val audio=com.shilapi.xcertplay.airplay.AudioStream(key,100,{logs.add(it)})
        try {
            val port=audio.listen(object:com.shilapi.xcertplay.airplay.AudioStream.Listener {
                override fun onRtp(rtp:ByteArray,sample:Int) {accepted.countDown()}
                override fun onPacket(wire:ByteArray,rtp:ByteArray?,sample:Int?,error:Throwable?) {if(error!=null)rejected.countDown()}
            }).first
            java.net.DatagramSocket().use {sender ->
                for(seq in 1..3) {
                    val header=ByteArray(12);header[0]=0x80.toByte();header[1]=100;header[3]=seq.toByte();header[7]=seq.toByte()
                    val nonce=ByteArray(12);nonce[4]=seq.toByte()
                    val sealed=com.shilapi.xcertplay.airplay.AirPlayCrypto.chachaSeal(key,nonce,ByteArray(16),header.copyOfRange(4,12))
                    if(seq==2)sealed[0]=(sealed[0].toInt() xor 1).toByte()
                    val packet=header+sealed+nonce.copyOfRange(4,12)
                    sender.send(java.net.DatagramPacket(packet,packet.size,java.net.InetAddress.getByName("127.0.0.1"),port))
                }
            }
            assertTrue(accepted.await(3,java.util.concurrent.TimeUnit.SECONDS));assertTrue(rejected.await(1,java.util.concurrent.TimeUnit.SECONDS))
        } finally {audio.close()}
        val deadline=System.nanoTime()+1_000_000_000L
        while(synchronized(logs) {logs.none { it.contains("udp=3 decoded=2 rejected=1") }} && System.nanoTime()<deadline) Thread.sleep(10)
        assertTrue(synchronized(logs) {logs.any { it.contains("udp=3 decoded=2 rejected=1") }})
        assertTrue(synchronized(logs) {logs.any {AirPlayDiagnostics.summary(it)?.startsWith("Audio UDP receive buffer") == true}})
    }
}
