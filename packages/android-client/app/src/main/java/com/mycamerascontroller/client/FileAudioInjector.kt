package com.mycamerascontroller.client

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import org.webrtc.audio.JavaAudioDeviceModule
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Feeds a decoded audio file into WebRTC's outgoing audio track in place of
 * the microphone, via [JavaAudioDeviceModule.AudioBufferCallback] — the
 * SDK's own extension point for supplying recording data (confirmed by
 * decompiling org.webrtc.audio.WebRtcAudioRecord: when a caller disables
 * real capture via [JavaAudioDeviceModule.setAudioRecordEnabled] `false`,
 * the record thread skips `AudioRecord.read` entirely and this callback's
 * writes into the buffer are what actually gets sent — not a hack on top of
 * mic tapping). No native/JNI code needed.
 *
 * [ViewerActivity] is responsible for calling `setAudioRecordEnabled(false)`
 * before [play] and `setAudioRecordEnabled(true)` after playback completes —
 * this class only decodes and supplies samples.
 */
class FileAudioInjector : JavaAudioDeviceModule.AudioBufferCallback {

    @Volatile private var pcm: ShortArray? = null
    @Volatile private var position = 0
    @Volatile private var onDone: (() -> Unit)? = null

    /** Starts feeding [samples] (16-bit mono PCM at [TARGET_SAMPLE_RATE]) on the next audio callback. */
    fun play(samples: ShortArray, onDone: () -> Unit) {
        this.onDone = onDone
        position = 0
        pcm = samples
    }

    override fun onBuffer(
        buffer: ByteBuffer,
        audioFormat: Int,
        channelCount: Int,
        sampleRate: Int,
        bytesRead: Int,
        captureTimestampNs: Long,
    ): Long {
        buffer.clear()
        val samples = pcm
        val shortsNeeded = buffer.capacity() / 2
        if (samples == null) {
            // Not expected in practice (real mic capture is enabled again
            // right after a file finishes), but never leave stale bytes.
            repeat(shortsNeeded) { buffer.putShort(0) }
            return captureTimestampNs
        }
        var written = 0
        while (written < shortsNeeded) {
            val pos = position
            if (pos < samples.size) {
                buffer.putShort(samples[pos])
                position = pos + 1
            } else {
                buffer.putShort(0)
            }
            written++
        }
        if (position >= samples.size) {
            pcm = null
            val cb = onDone
            onDone = null
            cb?.invoke()
        }
        return captureTimestampNs
    }

    companion object {
        const val TARGET_SAMPLE_RATE = 48_000

        /**
         * Decodes any audio file the platform's codecs support (mp3/wav/m4a
         * aac/ogg/…) to 16-bit mono PCM at [TARGET_SAMPLE_RATE] — matching
         * the fixed format the AudioDeviceModule is built with in
         * ViewerActivity. Runs synchronously; call off the main thread.
         */
        fun decodeToPcm16Mono48k(context: Context, uri: Uri): ShortArray {
            val extractor = MediaExtractor()
            extractor.setDataSource(context, uri, null)

            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    trackIndex = i
                    format = f
                    break
                }
            }
            require(trackIndex >= 0 && format != null) { "No audio track in file" }
            extractor.selectTrack(trackIndex)

            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val srcSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val srcChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val pcmBytes = ByteArrayOutputStream()
            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val inBuf = codec.getInputBuffer(inIndex)!!
                        val sampleSize = extractor.readSampleData(inBuf, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                if (outIndex >= 0) {
                    val outBuf = codec.getOutputBuffer(outIndex)!!
                    val chunk = ByteArray(bufferInfo.size)
                    outBuf.get(chunk)
                    pcmBytes.write(chunk)
                    codec.releaseOutputBuffer(outIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
            codec.stop()
            codec.release()
            extractor.release()

            // Decoded output is always raw 16-bit signed PCM, little-endian,
            // interleaved by srcChannels — true for every built-in Android
            // audio decoder regardless of the source container/codec.
            val raw = pcmBytes.toByteArray()
            val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
            val interleaved = ShortArray(raw.size / 2) { bb.short }

            val mono = if (srcChannels <= 1) {
                interleaved
            } else {
                ShortArray(interleaved.size / srcChannels) { i ->
                    var sum = 0
                    for (c in 0 until srcChannels) sum += interleaved[i * srcChannels + c]
                    (sum / srcChannels).toShort()
                }
            }

            if (srcSampleRate == TARGET_SAMPLE_RATE) return mono

            // Simple linear-interpolation resample — sufficient fidelity for
            // talk-back voice/alert audio, no external DSP dependency needed.
            val ratio = TARGET_SAMPLE_RATE.toDouble() / srcSampleRate
            val outLen = (mono.size * ratio).toInt()
            return ShortArray(outLen) { i ->
                val srcPos = i / ratio
                val i0 = srcPos.toInt().coerceIn(0, mono.size - 1)
                val i1 = (i0 + 1).coerceIn(0, mono.size - 1)
                val frac = srcPos - i0
                (mono[i0] * (1 - frac) + mono[i1] * frac).toInt().toShort()
            }
        }
    }
}
