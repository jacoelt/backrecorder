package com.backrecorderapp.service.recorder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.sin

/**
 * An [AudioRecorder] that needs no microphone and still produces a real MPEG-4/AAC file.
 *
 * It encodes a synthetic sine tone through the platform's AAC encoder and muxes it with
 * [MediaMuxer], so the output carries a proper MOOV atom and `MediaExtractor` — which
 * `SaveRecordingUseCase` uses to concatenate segments — can read it. Stubbing the file out with
 * placeholder bytes instead would make every flow test fail inside `MediaExtractor` rather than at
 * the behaviour under test.
 *
 * Audio is written only when a test asks for it, via [feed] — nothing is driven by wall-clock time
 * — in [MediaRecorderAudioRecorder]'s format: 16 kHz mono AAC-LC at 32 kbps.
 */
class FakeAudioRecorder(
    private val onStarted: (FakeAudioRecorder) -> Unit = {},
    private val onFinalized: (File) -> Unit = {},
    private val onFailed: (Throwable) -> Unit = {}
) : AudioRecorder {

    private val lock = Any()

    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var trackIndex = -1
    private var muxerStarted = false
    private var framesEncoded = 0L
    private var stopped = false

    /**
     * Whether a segment is open, mirrored out of [codec]/[stopped] so [maxAmplitude] can answer
     * without [lock].
     *
     * The service polls the amplitude every 200 ms from a `Dispatchers.Default` coroutine, and
     * `synchronized` blocks the thread uninterruptibly — so reading the real state under [lock]
     * would park that coroutine for the length of a `feed()` encode, starving a pool that is two
     * threads wide on a CI emulator and delaying the rotation and flush coroutines behind it.
     */
    @Volatile
    private var capturing = false

    private val bufferInfo = MediaCodec.BufferInfo()

    /** The file this recorder was started on, available once [start] has run. */
    @Volatile
    var outputFile: File? = null
        private set

    override fun start(outputFile: File) {
        synchronized(lock) {
            check(codec == null) { "FakeAudioRecorder is single-use; start() was already called" }
            this.outputFile = outputFile

            val format = MediaFormat.createAudioFormat(MIME_TYPE, SAMPLE_RATE_HZ, CHANNEL_COUNT)
                .apply {
                    setInteger(
                        MediaFormat.KEY_AAC_PROFILE,
                        MediaCodecInfo.CodecProfileLevel.AACObjectLC
                    )
                    setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_SIZE_BYTES)
                }

            val encoder = MediaCodec.createEncoderByType(MIME_TYPE).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
            try {
                muxer = MediaMuxer(
                    outputFile.absolutePath,
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                )
            } catch (e: Throwable) {
                // Leave nothing started behind: an encoder stranded here would never be released,
                // and a non-null `codec` would make every later start() fail its single-use check.
                runCatching { encoder.stop() }
                runCatching { encoder.release() }
                throw e
            }
            codec = encoder
            capturing = true
        }
        // Announced only once the recorder can accept audio, so a test that waits for a segment to
        // open cannot win the race against the service and call feed() on an unstarted recorder.
        onStarted(this)
    }

    /**
     * Encodes [durationMs] of synthetic audio into the current file. Returns once the samples have
     * been handed to the muxer, so a test can treat recorded time as something it advances rather
     * than something it waits for.
     */
    fun feed(durationMs: Long) = synchronized<Unit>(lock) {
        val encoder = codec ?: error("feed() before start()")
        check(!stopped) { "feed() after stop()" }
        encodeFrames(encoder, (SAMPLE_RATE_HZ * durationMs / 1000L).toInt())
    }

    override fun stop() {
        var failure: Throwable? = null
        val finalized: File? = synchronized(lock) {
            val encoder = codec
            if (encoder == null || stopped) {
                null
            } else {
                stopped = true
                capturing = false
                try {
                    // A segment the service opened and closed without the test advancing it would
                    // otherwise hold no samples at all, which the muxer cannot finalize. Give it
                    // the smallest real segment instead, so every file this fake leaves behind is
                    // a readable MP4.
                    if (framesEncoded == 0L) {
                        encodeFrames(encoder, (SAMPLE_RATE_HZ * MIN_SEGMENT_MS / 1000L).toInt())
                    }

                    queueInput(encoder, ByteArray(0), endOfStream = true)
                    drain(endOfStream = true)
                } catch (e: Throwable) {
                    failure = e
                } finally {
                    // Release whatever happened above: a throw mid-drain would otherwise strand a
                    // native encoder and muxer for the rest of the test run. Every call is
                    // guarded so a failing release cannot bury the original failure.
                    runCatching { encoder.stop() }
                    runCatching { encoder.release() }
                    codec = null

                    muxer?.let {
                        if (muxerStarted) runCatching { it.stop() }
                        runCatching { it.release() }
                    }
                    muxer = null
                }

                if (failure == null) outputFile else null
            }
        }
        // RecordingService catches and only logs whatever stop() throws, so without this the test
        // would see a failure here as an unexplained await timeout.
        failure?.let { onFailed(it) }
        finalized?.let(onFinalized)
        failure?.let { throw it }
    }

    /**
     * Constant and non-zero while recording, so waveform capture records something deterministic —
     * and 0 before [start] and from the moment [stop] begins, which is what the real recorder does
     * (#48). A fake that reported audio after teardown would hide the zero samples production
     * writes around a rotation boundary.
     */
    override fun maxAmplitude(): Int = if (capturing) FAKE_AMPLITUDE else 0

    private fun encodeFrames(encoder: MediaCodec, frameCount: Int) {
        var remaining = frameCount
        while (remaining > 0) {
            val frames = minOf(FRAMES_PER_CHUNK, remaining)
            queueInput(encoder, sineChunk(framesEncoded, frames), endOfStream = false)
            drain(endOfStream = false)
            framesEncoded += frames
            remaining -= frames
        }
    }

    private fun queueInput(encoder: MediaCodec, pcm: ByteArray, endOfStream: Boolean) {
        var offset = 0
        var queuedAny = false
        var idleSpins = 0
        // `offset < pcm.size` alone would never run for the empty end-of-stream buffer, so track
        // whether anything was actually queued. Bounded, so a stalled codec fails with a message
        // instead of wedging the service's rotation coroutine.
        while (!queuedAny || offset < pcm.size) {
            val index = encoder.dequeueInputBuffer(TIMEOUT_US)
            if (index < 0) {
                check(++idleSpins < MAX_INPUT_SPINS) { "Encoder stopped accepting input" }
                drain(endOfStream = false)
                continue
            }
            val buffer: ByteBuffer = encoder.getInputBuffer(index) ?: run {
                // Hand the index back rather than abandoning it, or the codec runs out of input
                // buffers and the loop starves. Counted, so this path is bounded too.
                encoder.queueInputBuffer(index, 0, 0, 0L, 0)
                check(++idleSpins < MAX_INPUT_SPINS) { "Encoder returned no input buffer" }
                continue
            }
            buffer.clear()
            val size = maxOf(minOf(buffer.capacity(), pcm.size - offset), 0)
            if (size > 0) buffer.put(pcm, offset, size)
            val presentationTimeUs =
                (framesEncoded + offset / BYTES_PER_FRAME) * 1_000_000L / SAMPLE_RATE_HZ
            encoder.queueInputBuffer(
                index,
                0,
                size,
                presentationTimeUs,
                if (endOfStream) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
            )
            offset += size
            queuedAny = true
        }
    }

    private fun drain(endOfStream: Boolean) {
        val encoder = codec ?: return
        // Bounded so a codec that never reports end-of-stream fails the test instead of hanging it.
        var idleSpins = 0
        while (true) {
            val index = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return
                    check(++idleSpins < MAX_DRAIN_SPINS) { "Encoder never signalled end-of-stream" }
                }

                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    check(!muxerStarted) { "Encoder reported a format change twice" }
                    val activeMuxer = muxer ?: error("Encoder produced a format with no muxer")
                    trackIndex = activeMuxer.addTrack(encoder.outputFormat)
                    activeMuxer.start()
                    muxerStarted = true
                }

                index < 0 -> Unit // deprecated INFO_OUTPUT_BUFFERS_CHANGED and any future codes

                else -> {
                    val output = encoder.getOutputBuffer(index)
                    // The codec-config buffer belongs to the track format, not to the samples.
                    val isConfig = bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (output != null && bufferInfo.size > 0 && !isConfig && muxerStarted) {
                        output.position(bufferInfo.offset)
                        output.limit(bufferInfo.offset + bufferInfo.size)
                        muxer?.writeSampleData(trackIndex, output, bufferInfo)
                    }
                    encoder.releaseOutputBuffer(index, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    /** A 440 Hz tone, continuous across chunks so the encoder sees an ordinary signal. */
    private fun sineChunk(startFrame: Long, frames: Int): ByteArray {
        val bytes = ByteArray(frames * BYTES_PER_FRAME)
        for (i in 0 until frames) {
            val angle = 2.0 * PI * TONE_HZ * (startFrame + i) / SAMPLE_RATE_HZ
            val sample = (sin(angle) * Short.MAX_VALUE * 0.5).toInt()
            bytes[i * 2] = (sample and 0xFF).toByte()
            bytes[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
        }
        return bytes
    }

    companion object {
        const val SAMPLE_RATE_HZ = 16_000
        const val CHANNEL_COUNT = 1
        const val BIT_RATE = 32_000
        const val FAKE_AMPLITUDE = 16_384

        private const val MIME_TYPE = MediaFormat.MIMETYPE_AUDIO_AAC
        private const val BYTES_PER_FRAME = 2 // 16-bit mono
        private const val FRAMES_PER_CHUNK = 1024
        private const val MAX_INPUT_SIZE_BYTES = FRAMES_PER_CHUNK * BYTES_PER_FRAME * 4
        private const val TIMEOUT_US = 10_000L
        private const val MIN_SEGMENT_MS = 100L
        private const val TONE_HZ = 440.0
        private const val MAX_DRAIN_SPINS = 500 // 500 x 10 ms
        private const val MAX_INPUT_SPINS = 500 // 500 x 10 ms

    }
}
