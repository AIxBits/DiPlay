package local.airuize.receiver
import com.shilapi.xcertplay.airplay.*
import org.junit.Test
import org.junit.Assert.*

class VideoPauseTransportTest {
    @Test(timeout=6000) fun tcpPauseKeepsEncryptedFramesInOrderWhileAudioContinues() {
        VideoDiagnosticGate.reset();VideoDiagnosticGate.toggle()
        val frames=java.util.Collections.synchronizedList(ArrayList<ByteArray>())
        val videoReady=java.util.concurrent.CountDownLatch(2)
        val audioReady=java.util.concurrent.CountDownLatch(1)
        val key=ByteArray(32)
        val screen=ScreenStream(key)
        val audio=AudioStream(key)
        try {
            val videoPort=screen.listen(object:ScreenStream.Listener {
                override fun onFrame(naluBytes:ByteArray){frames.add(naluBytes);videoReady.countDown()}
            })
            val audioPort=audio.listen(object:AudioStream.Listener {
                override fun onRtp(rtp:ByteArray,sample:Int){audioReady.countDown()}
            }).first
            java.net.Socket("127.0.0.1",videoPort).use {socket ->
                val payloads=List(2){byteArrayOf(0,0,0,1,0x65,it.toByte())}
                payloads.forEachIndexed {index,payload ->
                    val header=ByteArray(128)
                    java.nio.ByteBuffer.wrap(header).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(payload.size+16)
                    val sealed=AirPlayCrypto.chachaSeal(key,AirPlayCrypto.nonce64(index.toLong()),payload,header)
                    socket.getOutputStream().write(header+sealed)
                }
                socket.getOutputStream().flush()
                assertFalse(videoReady.await(200,java.util.concurrent.TimeUnit.MILLISECONDS));assertTrue(frames.isEmpty())
                val header=ByteArray(12);header[0]=0x80.toByte();header[3]=1
                val nonce=AirPlayCrypto.nonce64(1)
                val wire=header+AirPlayCrypto.chachaSeal(key,nonce,ByteArray(16),header.copyOfRange(4,12))+nonce.copyOfRange(4,12)
                java.net.DatagramSocket().use {sender ->sender.send(java.net.DatagramPacket(wire,wire.size,java.net.InetAddress.getByName("127.0.0.1"),audioPort))}
                assertTrue(audioReady.await(1,java.util.concurrent.TimeUnit.SECONDS));assertTrue(frames.isEmpty())
                VideoDiagnosticGate.reset()
                assertTrue(videoReady.await(2,java.util.concurrent.TimeUnit.SECONDS))
                for(index in 0..1)assertArrayEquals(payloads[index],frames[index])
            }
        }finally{audio.close();screen.close();VideoDiagnosticGate.reset()}
    }
}
