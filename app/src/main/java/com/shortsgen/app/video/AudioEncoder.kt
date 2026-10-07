package com.shortsgen.app.video

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.SystemClock
import android.util.Log
import java.io.IOException

/**
 * Encodes 16 bit mono PCM into AAC-LC access units that can be written into an MP4.
 *
 * The whole track is encoded up-front and kept in memory (a few hundred KB even for a
 * long clip). That lets the muxer interleave audio and video perfectly without having
 * to run two encoders on two threads.
 */
object AudioEncoder {

    private const val TAG = "ShortsGen/Audio"
    private const val AAC_FRAME_SAMPLES = 1024
    private const val DEQUEUE_TIMEOUT_US = 20_000L
    private const val TIMEOUT_MS = 5 * 60_000L

    data class Chunk(val data: ByteArray, val ptsUs: Long)

    data class Result(
        val format: MediaFormat,
        val chunks: List<Chunk>,
        val sampleRate: Int,
        val durationUs: Long
    )

    fun encode(pcmIn: ByteArray, sampleRateIn: Int, bitrate: Int = VideoSpec.AUDIO_BITRATE): Result {
        var pcm = pcmIn
        var sampleRate = sampleRateIn

        var encoderName = findAacEncoder(sampleRate)
        if (encoderName == null) {
            // Extremely rare: the device has no AAC encoder for 22050 Hz -> resample to 44100 Hz.
            Log.w(TAG, "No AAC encoder supports $sampleRate Hz, resampling to 44100 Hz")
            sampleRate = 44100
            pcm = Pcm.resample(pcmIn, sampleRateIn, sampleRate)
            encoderName = findAacEncoder(sampleRate)
        }
        val name = encoderName ?: throw IOException("This device has no AAC (audio/mp4a-latm) encoder")

        val inputDurationUs = Pcm.durationUs(pcm, sampleRate)

        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
        }

        val codec = MediaCodec.createByCodecName(name)
        val info = MediaCodec.BufferInfo()
        val chunks = ArrayList<Chunk>()
        var outputFormat: MediaFormat? = null
        var inputOffset = 0
        var inputDone = false
        var outputDone = false
        val frameBytes = AAC_FRAME_SAMPLES * 2
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS

        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        Log.i(TAG, "AAC encoder=$name rate=$sampleRate bytes=${pcm.size}")

        try {
            while (!outputDone) {
                if (SystemClock.elapsedRealtime() > deadline) {
                    throw IOException("AAC encoding timed out")
                }

                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (index >= 0) {
                        val buffer = codec.getInputBuffer(index)
                            ?: throw IOException("AAC input buffer is null")
                        buffer.clear()
                        val remaining = pcm.size - inputOffset
                        val ptsUs = inputOffset.toLong() * 500_000L / sampleRate
                        if (remaining <= 0) {
                            codec.queueInputBuffer(
                                index, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            val size = minOf(remaining, frameBytes, buffer.capacity())
                            buffer.put(pcm, inputOffset, size)
                            val isLast = inputOffset + size >= pcm.size
                            codec.queueInputBuffer(
                                index, 0, size, ptsUs,
                                if (isLast) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                            )
                            inputOffset += size
                            if (isLast) inputDone = true
                        }
                    }
                }

                when (val index = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (outputFormat == null) outputFormat = codec.outputFormat
                    }
                    else -> {
                        if (index >= 0) {
                            val isCodecConfig =
                                (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                            if (!isCodecConfig && info.size > 0) {
                                val buffer = codec.getOutputBuffer(index)
                                    ?: throw IOException("AAC output buffer is null")
                                val data = ByteArray(info.size)
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                buffer.get(data)
                                chunks.add(Chunk(data, info.presentationTimeUs))
                            }
                            codec.releaseOutputBuffer(index, false)
                            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                outputDone = true
                            }
                        }
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }

        val finalFormat = outputFormat
            ?: throw IOException("The AAC encoder never reported an output format")
        if (chunks.isEmpty()) {
            throw IOException("The AAC encoder produced no audio data")
        }
        Log.i(TAG, "AAC done: ${chunks.size} frames, $inputDurationUs us")
        return Result(finalFormat, chunks, sampleRate, inputDurationUs)
    }

    /** Picks an AAC encoder that accepts [sampleRate]. */
    private fun findAacEncoder(sampleRate: Int): String? {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val candidates = list.codecInfos.filter { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(VideoSpec.MIME_AAC, true) }
        }
        // Prefer the AOSP software encoder: predictable on every device.
        val ordered = candidates.sortedBy { info ->
            when (info.name) {
                "c2.android.aac.encoder" -> 0
                "OMX.google.aac.encoder" -> 1
                else -> if (info.name.startsWith("c2.android") || info.name.startsWith("OMX.google")) 2 else 3
            }
        }
        for (info in ordered) {
            val supported = runCatching {
                val caps = info.getCapabilitiesForType(VideoSpec.MIME_AAC)
                val audio = caps.audioCapabilities
                audio == null || audio.isSampleRateSupported(sampleRate)
            }.getOrDefault(false)
            if (supported) return info.name
        }
        return ordered.firstOrNull()?.name
    }
}
