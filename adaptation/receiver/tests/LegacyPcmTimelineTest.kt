package local.airuize.receiver
import org.junit.Test
import org.junit.Assert.*

class LegacyPcmTimelineTest {
    @Test fun contiguousSamplesAcrossUnsignedWrapHaveNoGap() {
        val timeline=LegacyPcmTimeline(48000,2)
        timeline.received(-100,400);timeline.received(0,400);timeline.received(100,400)
        assertEquals(0L,timeline.gapFrames);assertEquals(0L,timeline.discontinuities)
    }
    @Test fun shortSampleGapIsCountedWhileLatePacketsDoNotResetTimeline() {
        val timeline=LegacyPcmTimeline(48000,2)
        timeline.received(0,400);timeline.received(300,400);timeline.received(100,400);timeline.received(400,400)
        assertEquals(200L,timeline.gapFrames)
    }
    @Test fun LongPauseIsSeparateFromShortGapsAndMalformedPcmIsIgnored() {
        val timeline=LegacyPcmTimeline(48000,2)
        timeline.received(0,400);timeline.received(200000,400);timeline.received(99999,3);timeline.received(200100,400)
        assertEquals(0L,timeline.gapFrames);assertEquals(1L,timeline.discontinuities)
    }
}
