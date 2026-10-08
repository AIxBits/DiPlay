package local.airuize.receiver

import com.shilapi.xcertplay.transport.Ntb16Codec
import org.junit.Test
import org.junit.Assert.*
import java.io.IOException

class LegacyNtb16AssemblerTest {
    private fun frame(size:Int)=ByteArray(size) { (it%251+1).toByte() }
    @Test fun exactBoundaryWithoutPadIsDeliveredImmediatelyThenAcceptsNextHeader() {
        val assembler=LegacyNtb16Assembler();val a=frame(484);val b=frame(83)
        val block=Ntb16Codec.build(a,1).copyOf(512)
        assertArrayEquals(a,assembler.accept(block).single())
        assertArrayEquals(b,assembler.accept(Ntb16Codec.build(b,2)).single())
    }
    @Test fun bothPaddedAndUnpaddedBlocksSurviveEveryTwoChunkSplit() {
        val a=frame(484);val b=frame(484);val c=frame(73)
        for(pad in listOf(true,false)) {
            val first=Ntb16Codec.build(a,1).let { if(pad) it else it.copyOf(512) }
            val second=Ntb16Codec.build(b,2).let { if(pad) it else it.copyOf(512) }
            val wire=first+second+Ntb16Codec.build(c,3)
            for(split in 0..wire.size) {
                val assembler=LegacyNtb16Assembler()
                val frames=assembler.accept(wire.copyOfRange(0,split))+assembler.accept(wire.copyOfRange(split,wire.size))
                assertEquals(3,frames.size);assertArrayEquals(a,frames[0]);assertArrayEquals(b,frames[1]);assertArrayEquals(c,frames[2])
            }
        }
    }
    @Test fun mixedPaddingAndByteAtATimeInputKeepFrameOrder() {
        val a=frame(484);val b=frame(996);val c=frame(21)
        val wire=Ntb16Codec.build(a,1)+Ntb16Codec.build(b,2).copyOf(1024)+Ntb16Codec.build(c,3)
        val assembler=LegacyNtb16Assembler();val frames=ArrayList<ByteArray>()
        wire.forEach { frames.addAll(assembler.accept(byteArrayOf(it))) }
        assertEquals(3,frames.size);assertArrayEquals(a,frames[0]);assertArrayEquals(b,frames[1]);assertArrayEquals(c,frames[2])
    }
    @Test fun malformedDataAfterBoundaryIsRejectedRatherThanSilentlySkipped() {
        val assembler=LegacyNtb16Assembler()
        assembler.accept(Ntb16Codec.build(frame(484),1).copyOf(512))
        try { assembler.accept(byteArrayOf(1,2,3,4));fail("corruption accepted") } catch(_:IOException) {}
    }
    @Test fun oversizedInputAndInvalidHeaderLengthsAreRejected() {
        try { LegacyNtb16Assembler().accept(ByteArray(131073));fail("unbounded input") } catch(_:IOException) {}
        val block=Ntb16Codec.build(frame(21),1);block[4]=8
        try { LegacyNtb16Assembler().accept(block);fail("invalid NTH length") } catch(_:IOException) {}
    }
    @Test fun failureCapturesBoundaryAndHeaderContextWithoutPayload() {
        val logs=ArrayList<String>();val assembler=LegacyNtb16Assembler(logs::add)
        val secret="PRIVATE_AUDIO_CONTENT".toByteArray()
        val audio=ByteArray(484);secret.copyInto(audio)
        assembler.accept(Ntb16Codec.build(audio,42).copyOf(512))
        try { assembler.accept(byteArrayOf(9,8,7,6));fail("invalid header accepted") } catch(_:IOException) {}
        val evidence=logs.last()
        assertTrue(evidence.contains("evidence failure"))
        assertTrue(evidence.contains("sequence=42"))
        assertTrue(evidence.contains("firstLength=484"))
        assertTrue(evidence.contains("optionalPad=true"))
        assertFalse(logs.joinToString().contains("PRIVATE_AUDIO_CONTENT"))
    }
    @Test fun failureContextIsBoundedAfterLongSession() {
        val logs=ArrayList<String>();val assembler=LegacyNtb16Assembler(logs::add)
        repeat(100) { assembler.accept(Ntb16Codec.build(frame(21),it)) }
        try { assembler.accept(ByteArray(12));fail("invalid header accepted") } catch(_:IOException) {}
        assertEquals(32,logs.last().lines().count { it.startsWith("NCM context:") })
        assertTrue(logs.last().contains("sequence=99"))
        assertFalse(logs.last().contains("sequence=0 "))
    }
}
