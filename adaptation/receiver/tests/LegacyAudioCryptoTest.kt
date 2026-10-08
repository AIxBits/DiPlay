package local.airuize.receiver
import org.junit.Test
import org.junit.Assert.*
import com.shilapi.xcertplay.airplay.AirPlayCrypto

class LegacyAudioCryptoTest {
    private fun packet(key:ByteArray,plain:ByteArray,nonce:ByteArray,header:ByteArray):ByteArray=
        header+AirPlayCrypto.chachaSeal(key,nonce,plain,header.copyOfRange(4,12))+nonce.copyOfRange(4,12)
    @Test fun nativeMatchesOriginalCryptoForPacketSizesAndIndependentNonces() {
        val random=java.util.Random(827391)
        repeat(8) {
            val key=ByteArray(32).also(random::nextBytes);val crypto=LegacyAudioCrypto(key)
            assertEquals("ARM-native RFC8439",crypto.backend)
            for(size in listOf(0,1,15,16,17,63,64,65,354,1416,4060)) {
                val nonce=ByteArray(12);random.nextBytes(nonce);nonce.fill(0,0,4)
                val plain=ByteArray(size).also(random::nextBytes)
                val header=ByteArray(12).also(random::nextBytes);header[0]=0x80.toByte()
                val wire=packet(key,plain,nonce,header)
                assertArrayEquals(header+plain,crypto.open(wire))
            }
        }
    }
    @Test fun rejectsCiphertextAadTagAndNonceTamperingAndRecoversNextPacket() {
        val key=ByteArray(32) {it.toByte()};val crypto=LegacyAudioCrypto(key)
        val nonce=ByteArray(12);nonce[4]=1
        val wire=packet(key,ByteArray(1416){(it%251).toByte()},nonce,ByteArray(12))
        for(index in listOf(4,12,wire.size-24,wire.size-1)) {
            val modified=wire.copyOf();modified[index]=(modified[index].toInt() xor 1).toByte()
            try {crypto.open(modified);fail("unauthenticated data accepted")} catch(_:java.io.IOException) {}
        }
        assertEquals(1428,crypto.open(wire).size)
    }
    @Test fun concurrentStreamsKeepIndependentKeysAndState() {
        val done=java.util.concurrent.CountDownLatch(4)
        val errors=java.util.Collections.synchronizedList(ArrayList<Throwable>())
        repeat(4) {stream -> Thread({
            try {
                val key=ByteArray(32){(it+stream).toByte()};val crypto=LegacyAudioCrypto(key)
                repeat(100) {index -> val nonce=ByteArray(12);nonce[4]=index.toByte();val plain=ByteArray(1416){stream.toByte()}
                    assertArrayEquals(ByteArray(12)+plain,crypto.open(packet(key,plain,nonce,ByteArray(12))))}
            } catch(error:Throwable) {errors.add(error)} finally {done.countDown()}
        }).start() }
        assertTrue(done.await(5,java.util.concurrent.TimeUnit.SECONDS));assertTrue(errors.toString(),errors.isEmpty())
    }
}
