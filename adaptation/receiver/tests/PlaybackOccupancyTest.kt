package local.airuize.receiver
import org.junit.Test
import org.junit.Assert.*
class PlaybackOccupancyTest {
    @Test fun convertsStereoAndMonoFramesToMilliseconds() {
        val stereo=PlaybackOccupancy(48000,2)
        assertEquals(500L,stereo.millis(stereo.deviceBytes(96000,0)))
        assertEquals(300L,stereo.millis(stereo.deviceBytes(96000,9600)))
        val mono=PlaybackOccupancy(8000,1)
        assertEquals(256L,mono.millis(4096))
    }
    @Test fun tracksUnsignedHeadWrapAndClampsPlayedBeyondWritten() {
        val probe=PlaybackOccupancy(48000,2)
        val written=(0xffffffffL+200)*4
        probe.deviceBytes(written,-16)
        assertEquals(184L*4,probe.deviceBytes(written,15))
        assertEquals(0L,probe.deviceBytes(0,16))
    }
    @Test fun gradualDrainAndRefillAreObservableWithoutChangingPlayback() {
        val probe=PlaybackOccupancy(48000,2)
        assertEquals(100L,probe.millis(probe.deviceBytes(19200,0)))
        assertEquals(50L,probe.millis(probe.deviceBytes(19200,2400)))
        assertEquals(0L,probe.millis(probe.deviceBytes(19200,4800)))
        assertEquals(100L,probe.millis(probe.deviceBytes(38400,4800)))
        assertFalse(RuntimeLogPolicy.updateUi("Audio health packetPcmMs=0 deviceQueuedMs=0"))
    }
}
