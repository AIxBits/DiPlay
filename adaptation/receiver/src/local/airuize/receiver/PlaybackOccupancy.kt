package local.airuize.receiver

/** One diagnostic observer owns the playback-head state; playback only publishes byte counters. */
class PlaybackOccupancy(private val rate:Int,private val channels:Int) {
    init {require(rate>0 && channels in 1..2)}
    private var lastHead=0L
    private var playedFrames=0L
    fun deviceBytes(writtenBytes:Long,rawHead:Int):Long {
        val head=rawHead.toLong() and 0xffffffffL
        playedFrames+=(head-lastHead) and 0xffffffffL
        lastHead=head
        return (writtenBytes-playedFrames*channels*2).coerceAtLeast(0)
    }
    fun millis(bytes:Long):Long=bytes.coerceAtLeast(0)*1000/(rate.toLong()*channels*2)
}
