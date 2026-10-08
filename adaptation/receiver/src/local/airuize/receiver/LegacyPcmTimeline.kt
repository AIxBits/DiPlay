package local.airuize.receiver

/** Compare unsigned RTP sample timestamps; do not equate sequence jumps with missing PCM. */
class LegacyPcmTimeline(private val rate:Int,private val channels:Int) {
    private var previous:Long?=null
    private var previousFrames=0L
    @Volatile var gapFrames=0L; private set
    @Volatile var discontinuities=0L; private set
    @Synchronized fun received(sample:Int,payloadBytes:Int) {
        if(payloadBytes<=0 || payloadBytes%(channels*2)!=0) return
        val current=sample.toLong() and 0xffffffffL
        val old=previous
        if(old!=null) {
            val advance=(current-old) and 0xffffffffL
            if(advance==0L || advance>=0x80000000L) return // Duplicate or late packet.
            if(advance>rate.toLong()*2) discontinuities++
            else if(advance>previousFrames) gapFrames+=advance-previousFrames
        }
        previous=current;previousFrames=(payloadBytes/(channels*2)).toLong()
    }
}
