package local.airuize.receiver

import java.util.concurrent.atomic.AtomicLong

/** Counts data flow; never stores coordinates, media payloads or user identifiers. */
class MediaDiagnostics {
    val videoReceived = AtomicLong()
    val videoRendered = AtomicLong()
    val decoderStarts = AtomicLong()
    val videoDropped = AtomicLong()
    val recoveryRequests = AtomicLong()
    val audioPackets = AtomicLong()
    val audioWrittenBytes = AtomicLong()
    val audioDropped = AtomicLong()
    val microphoneReadBytes = AtomicLong()
    val microphoneZeroReads = AtomicLong()
    val microphoneSentPackets = AtomicLong()
    val microphoneNonZeroFrames = AtomicLong()
    fun summary(): String = "媒体统计：视频接收=${videoReceived.get()}；显示缓冲释放=${videoRendered.get()}；解码器启动=${decoderStarts.get()}；视频入队拒绝=${videoDropped.get()}；关键帧请求=${recoveryRequests.get()}；音频RTP=${audioPackets.get()}；AudioTrack写入字节=${audioWrittenBytes.get()}；音频入队拒绝=${audioDropped.get()}；麦克风读取字节=${microphoneReadBytes.get()}；麦克风零字节读取次数=${microphoneZeroReads.get()}；麦克风非零PCM帧=${microphoneNonZeroFrames.get()}；麦克风UDP发送包=${microphoneSentPackets.get()}（计数不证明实际听到或手机已接收）"
}
