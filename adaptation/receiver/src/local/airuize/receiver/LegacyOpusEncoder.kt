package local.airuize.receiver

import android.media.MediaCodec
import android.media.MediaFormat
import java.io.Closeable
import java.nio.ByteBuffer

// Same 48 kHz mono / 20 ms encoder contract as upstream OpusEncoder; API18 buffers.
class LegacyOpusEncoder(bitrate: Int?) : Closeable {
    private val codec = MediaCodec.createEncoderByType("audio/opus")
    private var outputs: Array<ByteBuffer>
    private val inputs: Array<ByteBuffer>
    private val info = MediaCodec.BufferInfo()
    private var pts = 0L
    init {
        try {
            val format = MediaFormat.createAudioFormat("audio/opus", 48000, 1)
            format.setInteger("bitrate", bitrate ?: 48000); format.setInteger("max-input-size", 4096)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); codec.start()
            inputs = codec.inputBuffers; outputs = codec.outputBuffers
        } catch (error: Exception) { codec.release(); throw error }
    }
    fun encode(pcm: ByteArray): List<ByteArray> {
        val index = codec.dequeueInputBuffer(10000)
        if(index >= 0) {
            val input = inputs[index]; input.clear()
            require(pcm.size <= input.remaining()); input.put(pcm)
            codec.queueInputBuffer(index, 0, pcm.size, pts, 0); pts += 20000
        }
        val result = ArrayList<ByteArray>()
        while (true) {
            val out = codec.dequeueOutputBuffer(info, 0)
            when {
                out == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> outputs = codec.outputBuffers
                out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {}
                out >= 0 -> { try {
                    if(info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        val bytes = ByteArray(info.size); val buffer = outputs[out]
                        buffer.position(info.offset); buffer.limit(info.offset + info.size); buffer.get(bytes); result.add(bytes)
                    }
                } finally { codec.releaseOutputBuffer(out, false) } }
                else -> return result
            }
        }
    }
    override fun close() { try { codec.stop() } finally { codec.release() } }
}
fun opusHead(channels: Int, rate: Int): ByteArray {
    val head = ByteArray(19); "OpusHead".toByteArray(Charsets.US_ASCII).copyInto(head)
    head[8] = 1; head[9] = channels.toByte(); head[10] = 0x38; head[11] = 1
    for(i in 0..3) head[12+i] = (rate ushr (i*8)).toByte()
    return head
}
