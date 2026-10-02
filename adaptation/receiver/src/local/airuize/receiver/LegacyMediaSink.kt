package local.airuize.receiver

import android.media.AudioTrack
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaRecorder
import android.view.Surface
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.media.*
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** API18 codecs are owned exclusively by their workers, including configure/stop/release. */
class LegacyMediaSink(private val log: (String) -> Unit, private val rendered: () -> Unit,
    private val videoWidth: Int = 800, private val videoHeight: Int = 480,
    private val settings: ReceiverSettings = ReceiverSettings(), context: android.content.Context? = null) : MediaSink, Closeable {
    private val surfaceState=VideoSurfaceState<Surface>()
    private val configuration = VideoConfigState()
    @Volatile private var videoCodec = VideoCodec.H264
    private val coordinator = LegacyAudioCoordinator(context, settings, log)
    @Volatile private var recovery: (() -> Unit)? = null
    private val video = ArrayBlockingQueue<ByteArray>(16)
    private val resync = AtomicBoolean(true)
    private val closed = AtomicBoolean(false)
    val diagnostics = MediaDiagnostics()
    private val audio = HashMap<AudioStreamId, LegacyAudio>()
    private val microphones = HashMap<AudioStreamId, LegacyMicrophone>()
    private val worker = Thread({ videoLoop() }, "legacy-avc").apply { isDaemon = true; start() }
    @Synchronized fun diagnosticSummary(): String = diagnostics.summary() + "；当前音频流=${audio.size}；当前麦克风流=${microphones.size}；Surface有效=${surfaceState.snapshot.target?.isValid == true}；视频编码=$videoCodec；视频配置就绪=${configuration.snapshot!=null}；配置字节=${configuration.snapshot?.size ?: 0}；目标=${videoWidth}x${videoHeight}；fps=${settings.fps}" +
        audio.values.joinToString("\n", prefix = if (audio.isEmpty()) "" else "\n") { it.summary() }
    fun setSurface(value: Surface?, force:Boolean=false) {
        if(surfaceState.attach(value,force)) {
            resync.set(true)
            log("视频Surface更新：generation=${surfaceState.snapshot.generation}；有效=${value?.isValid==true}；强制恢复=$force")
        }
    }
    override fun setVideoRecoveryHandler(type: Int, handler: () -> Unit) { recovery = handler }
    override fun onVideoCodec(type: Int, codec: VideoCodec) {
        require(codec == VideoCodec.H264 || CodecCapabilities.hevc()) { "No HEVC decoder on this firmware" }
        if (videoCodec != codec) { videoCodec = codec; resync.set(true) }
    }
    override fun onVideoConfig(type: Int, codecData: ByteArray) { if (configuration.update(codecData)) resync.set(true) }
    override fun onVideoFrame(type: Int, naluBytes: ByteArray) {
        if (closed.get()) return
        diagnostics.videoReceived.incrementAndGet()
        if (naluBytes.size > 2 * 1024 * 1024 || !video.offer(naluBytes.copyOf())) {
            diagnostics.videoDropped.incrementAndGet()
            video.clear(); resync.set(true)
        }
    }
    @Synchronized override fun onAudioStarted(id: AudioStreamId, format: com.shilapi.xcertplay.airplay.AudioFormat, firstSample: Int) {
        audio.remove(id)?.close()
        if (format.codec == AudioCodecKind.OPUS && !CodecCapabilities.opus()) { log("Opus codec unavailable"); return }
        log("音频流开始：type=${id.type}；codec=${format.codec}；rate=${format.sampleRate}；channels=${format.channels}；用途=${AudioDiagnostics.purpose(format.audioType)}")
        audio[id] = LegacyAudio(format, firstSample, log, diagnostics, settings, coordinator)
    }
    @Synchronized override fun onAudioRtp(id: AudioStreamId, format: com.shilapi.xcertplay.airplay.AudioFormat, rtp: ByteArray, sample: Int) {
        audio[id]?.offer(rtp, sample)
    }
    @Synchronized override fun onAudioStopped(id: AudioStreamId) { audio.remove(id)?.close() }
    @Synchronized override fun onMicrophoneStarted(id: AudioStreamId, config: MicrophoneConfig) {
        microphones.remove(id)?.close()
        if (config.codec == AudioCodecKind.OPUS && !CodecCapabilities.opus()) { log("Opus encoder unavailable"); return }
        log("麦克风选路：用途=${AudioDiagnostics.purpose(config.audioType)}；AudioSource=${microphoneAudioSource(config.audioType)}（按原仓库用途选择）；codec=${config.codec}；rate=${config.sampleRate}；channels=${config.channels}；目标为iPhone上行（不记录地址或密钥）")
        microphones[id] = LegacyMicrophone(config, log, diagnostics)
    }
    @Synchronized override fun onMicrophoneStopped(id: AudioStreamId) { microphones.remove(id)?.close() }
    @Synchronized override fun close() {
        if (!closed.compareAndSet(false, true)) return
        video.clear(); worker.interrupt()
        audio.values.forEach { it.close() }; audio.clear()
        microphones.values.forEach { it.close() }; microphones.clear()
        log(diagnostics.summary())
    }
    private fun videoLoop() {
        var decoder: MediaCodec? = null
        var oldSurface: Surface? = null
        var oldGeneration = -1L
        var recoveryFailures=0
        var oldConfig: ByteArray? = null
        var oldCodec = VideoCodec.H264
        var inputs: Array<ByteBuffer>? = null
        var needsIdr = true
        var frameCount = 0L
        var pending: ByteArray? = null
        val info = MediaCodec.BufferInfo()
        fun release() {
            decoder?.let { try { it.stop() } catch (_: Exception) {}; try { it.release() } catch (_: Exception) {} }
            decoder = null; inputs = null
        }
        fun drain() {
            val codec = decoder ?: return
            while (!closed.get()) {
                val index = codec.dequeueOutputBuffer(info, 0)
                if (index >= 0) { codec.releaseOutputBuffer(index, true); diagnostics.videoRendered.incrementAndGet(); rendered() }
                else if (index == MediaCodec.INFO_TRY_AGAIN_LATER) return
            }
        }
        try {
            while (!closed.get()) {
              try {
                val snapshot=surfaceState.snapshot
                val target = snapshot.target
                val data = configuration.snapshot
                if (snapshot.generation!=oldGeneration || target !== oldSurface || data !== oldConfig || videoCodec != oldCodec) {
                    release(); oldSurface = target; oldGeneration=snapshot.generation; oldConfig = data; oldCodec = videoCodec; needsIdr = true; pending=null; video.clear()
                    if (target?.isValid == true && data != null) {
                        val mime = if(oldCodec == VideoCodec.H265) "video/hevc" else "video/avc"
                        val format = MediaFormat.createVideoFormat(mime, videoWidth, videoHeight)
                        if (oldCodec == VideoCodec.H265) {
                            val csd = MediaCodecSupport.hevcCodecSpecificData(data)
                            require(csd.isNotEmpty())
                            format.setByteBuffer("csd-0", ByteBuffer.wrap(csd))
                        } else {
                            val (sps, pps) = MediaCodecSupport.avcParameterSets(data)
                            require(sps.isNotEmpty() && pps.isNotEmpty()) { "Invalid AVC configuration" }
                            format.setByteBuffer("csd-0", ByteBuffer.wrap(byteArrayOf(0, 0, 0, 1) + sps))
                            format.setByteBuffer("csd-1", ByteBuffer.wrap(byteArrayOf(0, 0, 0, 1) + pps))
                        }
                        for (name in CodecCapabilities.names(mime)) {
                            var candidate: MediaCodec? = null
                            try {
                                candidate = MediaCodec.createByCodecName(name)
                                candidate.configure(format, target, null, 0); candidate.start()
                                decoder = candidate; inputs = candidate.inputBuffers
                                diagnostics.decoderStarts.incrementAndGet()
                                log("视频解码器已启动：$name"); break
                            } catch (e: Exception) {
                                try { candidate?.release() } catch (_: Exception) {}
                                log("视频解码器启动失败：$name；${e.javaClass.simpleName}；${e.message?.take(160)}")
                            }
                        }
                        check(decoder != null) { "No usable H.264 decoder" }
                    }
                }
                if (decoder != null && resync.getAndSet(false)) { needsIdr = true; recovery?.let { diagnostics.recoveryRequests.incrementAndGet(); it() } }
                drain()
                val frame = pending?.also { pending = null } ?: video.poll(20, TimeUnit.MILLISECONDS) ?: continue
                val codec = decoder
                if (codec == null) {
                    if (surfaceState.snapshot.target?.isValid == true && configuration.snapshot != null) pending = frame
                    continue
                }
                val annexB = MediaCodecSupport.toAnnexB(frame)
                if (annexB.isEmpty()) { resync.set(true); continue }
                if (needsIdr && !MediaCodecSupport.isRandomAccess(annexB, oldCodec)) continue
                var index = -1
                val deadline = System.nanoTime() + 500_000_000L
                while (index < 0 && !closed.get() && System.nanoTime() < deadline) {
                    drain(); index = codec.dequeueInputBuffer(10000)
                }
                if (index < 0) { resync.set(true); continue }
                val input = inputs!![index]; input.clear()
                if (annexB.size > input.remaining()) {
                    codec.queueInputBuffer(index, 0, 0, 0, 0); resync.set(true); continue
                }
                input.put(annexB)
                codec.queueInputBuffer(index, 0, annexB.size, frameCount++ * (1_000_000L / settings.fps), 0)
                needsIdr = false; drain(); recoveryFailures=0
              } catch(error:InterruptedException) {
                if(closed.get()) break
              } catch(error:Exception) {
                if(closed.get()) break
                recoveryFailures++
                log("视频恢复：解码异常=${error.javaClass.simpleName}；原因=${error.message?.take(160)}；次数=$recoveryFailures；将重建解码器并请求关键帧；线程继续运行")
                release(); oldSurface=null; oldGeneration=-1; oldConfig=null; pending=null
                video.clear(); resync.set(true)
                Thread.sleep(minOf(2000L,250L*recoveryFailures.coerceAtMost(8)))
              }
            }
        } catch (_: InterruptedException) {} catch (e: Exception) { log("视频失败：${e.javaClass.simpleName}") }
        finally { release() }
    }
}

private class LegacyAudio(private val format: com.shilapi.xcertplay.airplay.AudioFormat, private val firstSample: Int,
                          private val log: (String) -> Unit, private val diagnostics: MediaDiagnostics,
                          private val settings: ReceiverSettings, private val coordinator: LegacyAudioCoordinator) : Closeable {
    private val stopped = AtomicBoolean(false)
    private val received = java.util.concurrent.atomic.AtomicLong()
    private val written = java.util.concurrent.atomic.AtomicLong()
    private val dropped = java.util.concurrent.atomic.AtomicLong()
    private val selection = AudioChannelMapper.map(format.audioType, format.payloadType, AudioChannelMappingMode.MOBILE_COMPATIBLE, android.media.AudioManager.STREAM_MUSIC)
    private val stream = AudioOverridePolicy.resolve(selection.channel.name,selection.streamType,settings.mediaChannel,settings.navigationChannel)
    private val queue = ArrayBlockingQueue<Pair<ByteArray, Int>>(64)
    @Volatile private var track: AudioTrack? = null
    @Volatile private var actualStream = stream
    init { Thread({ run() }, "legacy-audio").apply { isDaemon = true; start() } }
    fun summary(): String = "音频通道统计：用途=${AudioDiagnostics.purpose(format.audioType)}；type=${format.payloadType}；Android=${AudioDiagnostics.androidStreamName(actualStream)}($actualStream)；请求通道=$stream；RTP=${received.get()}；写入字节=${written.get()}；入队拒绝=${dropped.get()}；AudioTrack状态=${track?.state ?: -1}；播放状态=${track?.playState ?: -1}"
    fun offer(bytes: ByteArray, sample: Int) {
        if (stopped.get()) return
        diagnostics.audioPackets.incrementAndGet()
        received.incrementAndGet()
        if (!queue.offer(bytes.copyOf() to sample)) { diagnostics.audioDropped.incrementAndGet(); dropped.incrementAndGet(); log("音频缓冲溢出") }
    }
    override fun close() { stopped.set(true); queue.clear(); try { track?.pause(); track?.flush() } catch (_: Exception) {} }
    private fun run() {
        var codec: MediaCodec? = null
        var inputs: Array<ByteBuffer>? = null
        var outputs: Array<ByteBuffer>? = null
        val info = MediaCodec.BufferInfo()
        try {
            val channels = if (format.channels == 1) android.media.AudioFormat.CHANNEL_OUT_MONO else android.media.AudioFormat.CHANNEL_OUT_STEREO
            val minimum = AudioTrack.getMinBufferSize(format.sampleRate, channels, android.media.AudioFormat.ENCODING_PCM_16BIT)
            require(minimum > 0) { "Audio output unavailable" }
            val plan = MediaAudioBuffer.plan(selection.channel == AudioChannel.MEDIA, format.sampleRate, format.channels, minimum, settings.bufferMillis)
            var startBytes = MediaAudioBuffer.startBytesFor(plan.startBytes, plan.trackBufferBytes, 4096)
            var bufferedBytes = 0
            var lastWrite = 0L
            val progress = AudioBufferProgress(format.channels * 2)
            var playing = false
            fun createTrack(channel:Int):AudioTrack {
                val result=AudioTrack(channel, format.sampleRate, channels, android.media.AudioFormat.ENCODING_PCM_16BIT,plan.trackBufferBytes,AudioTrack.MODE_STREAM)
                if(result.state!=AudioTrack.STATE_INITIALIZED) { result.release(); error("Audio stream unavailable") }
                return result
            }
            val output=try { createTrack(stream) } catch(error:Exception) {
                if(stream==selection.streamType) throw error
                log("自选音频通道不可用：$stream；回退自动通道=${selection.streamType}")
                actualStream=selection.streamType
                createTrack(selection.streamType)
            }
            track = output
            val capacity = runCatching { AudioTrack::class.java.getDeclaredMethod("getNativeFrameCount").apply { isAccessible=true }.invoke(output) as Int }.getOrNull()?.times(format.channels*2)
            if(capacity != null && capacity > 0) startBytes = MediaAudioBuffer.startBytesFor(plan.startBytes, capacity, minOf(4096, capacity/2).coerceAtLeast(2))
            log("Audio buffer requested=${plan.trackBufferBytes} actual=${capacity ?: -1} start=$startBytes mediaMs=${settings.bufferMillis}")
            coordinator.acquire(output, selection.channel)
            log("音频选路：用途=${AudioDiagnostics.purpose(format.audioType)}；Android=${AudioDiagnostics.androidStreamName(actualStream)}($actualStream)；请求通道=$stream；输出声道数=${format.channels}；rate=${format.sampleRate}；state=${output.state}；playState=${output.playState}")
            if (format.codec != AudioCodecKind.LPCM) {
                val mime = if (format.codec == AudioCodecKind.OPUS) "audio/opus" else "audio/mp4a-latm"
                val mediaFormat = MediaFormat.createAudioFormat(mime, format.sampleRate, format.channels)
                if (format.codec == AudioCodecKind.AAC_LC) mediaFormat.setInteger("is-adts", 1)
                val frequency = MediaCodecSupport.aacFrequencyIndex(format.sampleRate)
                mediaFormat.setByteBuffer("csd-0", ByteBuffer.wrap(byteArrayOf(((2 shl 3) or (frequency shr 1)).toByte(),
                    (((frequency and 1) shl 7) or (format.channels shl 3)).toByte())))
                if(format.codec == AudioCodecKind.OPUS) {
                    mediaFormat.setByteBuffer("csd-0", ByteBuffer.wrap(opusHead(format.channels, format.sampleRate)))
                    mediaFormat.setByteBuffer("csd-1", ByteBuffer.wrap(java.nio.ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN).putLong(6500000L).array()))
                    mediaFormat.setByteBuffer("csd-2", ByteBuffer.wrap(java.nio.ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN).putLong(80000000L).array()))
                }
                codec = MediaCodec.createDecoderByType(mime)
                codec.configure(mediaFormat, null, null, 0); codec.start()
                inputs = codec.inputBuffers; outputs = codec.outputBuffers
            }
            fun write(bytes: ByteArray) {
                var offset = 0
                while (!stopped.get() && offset < bytes.size) {
                    if (!playing && bufferedBytes >= startBytes) { output.play(); playing = true }
                    val count = output.write(bytes, offset, minOf(4096, bytes.size - offset))
                    check(count > 0) { "Audio write failed" }; diagnostics.audioWrittenBytes.addAndGet(count.toLong()); written.addAndGet(count.toLong()); offset += count; bufferedBytes += count; progress.written(count); lastWrite = System.nanoTime(); if(!playing && bufferedBytes >= startBytes) { output.play(); playing=true }
                }
            }
            fun drain() {
                val decoder = codec ?: return
                while (!stopped.get()) {
                    val index = decoder.dequeueOutputBuffer(info, 0)
                    when {
                        index >= 0 -> {
                            try {
                                if (info.size > 0) {
                                    val bytes = ByteArray(info.size)
                                    val buffer = outputs!![index]
                                    buffer.position(info.offset); buffer.limit(info.offset + info.size); buffer.get(bytes)
                                    write(bytes)
                                }
                            } finally { decoder.releaseOutputBuffer(index, false) }
                        }
                        index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> outputs = decoder.outputBuffers
                        index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val next = decoder.outputFormat
                            check(next.getInteger(MediaFormat.KEY_SAMPLE_RATE) == format.sampleRate &&
                                next.getInteger(MediaFormat.KEY_CHANNEL_COUNT) == format.channels) { "Unexpected AAC output format" }
                        }
                        else -> return
                    }
                }
            }
            log("音频已启动：${format.codec}, ${format.sampleRate} Hz")
            while (!stopped.get()) {
                drain()
                val packet = queue.poll(20, TimeUnit.MILLISECONDS)
                if (packet == null) {
                    if(selection.channel == AudioChannel.MEDIA && playing && progress.queuedBytes(output.playbackHeadPosition) == 0L && System.nanoTime()-lastWrite > 100_000_000L) {
                        output.pause(); playing=false; bufferedBytes=0
                    }
                    if(!playing && bufferedBytes > 0 && System.nanoTime()-lastWrite >= 500_000_000L) { output.play(); playing=true }
                    continue
                }
                if (packet.first.size <= 12) continue
                var payload = packet.first.copyOfRange(12, packet.first.size)
                if (codec == null) { for (i in 0 until payload.size - 1 step 2) { val b = payload[i]; payload[i] = payload[i + 1]; payload[i + 1] = b }; write(payload) }
                else {
                    if (format.codec == AudioCodecKind.AAC_LC) payload = MediaCodecSupport.adtsFrame(payload, format.sampleRate, format.channels)
                    var index = -1
                    val deadline = System.nanoTime() + 250_000_000L
                    while (index < 0 && !stopped.get() && System.nanoTime() < deadline) { drain(); index = codec.dequeueInputBuffer(10000) }
                    if (index < 0) { log("AAC 输入缓冲超时"); continue }
                    val input = inputs!![index]; input.clear()
                    if (payload.size > input.remaining()) { codec.queueInputBuffer(index, 0, 0, 0, 0); continue }
                    input.put(payload)
                    val pts = ((packet.second.toLong() - firstSample.toLong()) and 0xffffffffL) * 1_000_000L / format.sampleRate
                    codec.queueInputBuffer(index, 0, payload.size, pts, 0); drain()
                }
            }
        } catch (e: Exception) { log("音频失败：${e.javaClass.simpleName}") }
        finally {
            log(summary())
            try { codec?.stop() } catch (_: Exception) {}; try { codec?.release() } catch (_: Exception) {}
            track?.let { coordinator.release(it) }
            try { track?.stop() } catch (_: Exception) {}; try { track?.release() } catch (_: Exception) {}; track = null
        }
    }
}

/** Match upstream MicrophoneUplink source selection using SDK18 constructors. */
private fun microphoneAudioSource(audioType: String): Int = when (audioType.toLowerCase(java.util.Locale.US)) {
    "telephony" -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
    "speechrecognition" -> MediaRecorder.AudioSource.VOICE_RECOGNITION
    else -> MediaRecorder.AudioSource.MIC
}
private class LegacyMicrophone(private val config: MicrophoneConfig, private val log: (String) -> Unit, private val diagnostics: MediaDiagnostics) : Closeable {
    private val stopped = AtomicBoolean(false)
    @Volatile private var record: AudioRecord? = null
    @Volatile private var socket: DatagramSocket? = null
    init { Thread({ run() }, "legacy-microphone").apply { isDaemon = true; start() } }
    override fun close() { stopped.set(true); try { record?.stop() } catch (_: Exception) {}; socket?.close() }
    private fun run() {
        var encoder: LegacyOpusEncoder? = null
        try {
            if (config.codec == AudioCodecKind.OPUS) encoder = LegacyOpusEncoder(config.bitrate)
            val channel = if (config.channels == 1) android.media.AudioFormat.CHANNEL_IN_MONO else android.media.AudioFormat.CHANNEL_IN_STEREO
            val minBuffer = AudioRecord.getMinBufferSize(config.sampleRate, channel, android.media.AudioFormat.ENCODING_PCM_16BIT)
            require(minBuffer > 0)
            val recorder = AudioRecord(microphoneAudioSource(config.audioType), config.sampleRate, channel,
                android.media.AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer * 2, config.frameBytes * 4))
            record = recorder; check(recorder.state == AudioRecord.STATE_INITIALIZED)
            val network = DatagramSocket(null); network.bind(java.net.InetSocketAddress(LegacyBindAddress.any(config.host), 0)); socket = network
            val counters = MicrophoneCounters(); val pcm = ByteArray(config.frameBytes)
            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING)
            log("PCM 麦克风上行已启动；AudioRecord状态=RECORDING；Android录音权限可实际使用")
            while (!stopped.get()) {
                var offset = 0
                while (!stopped.get() && offset < pcm.size) {
                    val n = recorder.read(pcm, offset, pcm.size - offset)
                    if (n < 0) {
                        if (!stopped.get()) log("麦克风读取失败：AudioRecord代码=$n")
                        throw java.io.IOException("AudioRecord read failed")
                    }
                    if (n == 0) { diagnostics.microphoneZeroReads.incrementAndGet(); Thread.yield(); continue }
                    diagnostics.microphoneReadBytes.addAndGet(n.toLong()); offset += n
                }
                if (stopped.get()) break
                if (pcm.any { it != 0.toByte() }) diagnostics.microphoneNonZeroFrames.incrementAndGet()
                val units = encoder?.encode(pcm) ?: listOf(MicrophonePacketizer.toWirePcm(pcm))
                for (unit in units) {
                val packet = MicrophonePacketizer.sealPacket(config.key, config.payloadType, counters, unit, config.samplesPerPacket)
                network.send(DatagramPacket(packet, packet.size, config.host, config.port))
                val sent = diagnostics.microphoneSentPackets.incrementAndGet()
                if (sent == 1L) log("麦克风首个PCM上行包已发送；非零帧=${diagnostics.microphoneNonZeroFrames.get()}（非零不等于语音已识别；无录音保存）")
                }
            }
        } catch (e: Exception) { if (!stopped.get()) log("麦克风失败：${e.javaClass.simpleName}") }
        finally { encoder?.close(); log("麦克风上行结束：读取字节=${diagnostics.microphoneReadBytes.get()}；非零帧=${diagnostics.microphoneNonZeroFrames.get()}；发送包=${diagnostics.microphoneSentPackets.get()}"); try { record?.stop() } catch (_: Exception) {}; try { record?.release() } catch (_: Exception) {}; record = null; socket?.close(); socket = null }
    }
}
