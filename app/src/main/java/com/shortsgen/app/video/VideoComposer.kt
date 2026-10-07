package com.shortsgen.app.video

import android.graphics.Typeface
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Renders one vertical short: 1080x1920, black background, white wrapped text
 * (680x1320 text box, `font.ttf`), H.264/AVC video + AAC audio in an MP4 container.
 *
 * The clip is exactly as long as the speech that was synthesised for it
 * (+ [VideoSpec.TAIL_US] so the last audio frame is never cut off).
 *
 * Everything runs on-device with the platform codecs:
 *   * video: software AVC encoder (`c2.android.avc.encoder`) fed with YUV 4:2:0 frames
 *   * audio: AAC-LC encoder
 *   * container: [MediaMuxer]
 */
class VideoComposer(private val typeface: Typeface) {

    companion object {
        private const val TAG = "ShortsGen/Video"
        private const val DEQUEUE_TIMEOUT_US = 20_000L
        private const val ENCODE_TIMEOUT_MS = 10 * 60_000L
    }

    /** Thrown while picking/starting an encoder, so the caller can retry another path. */
    private class EncoderSetupException(message: String, cause: Throwable?) : IOException(message, cause)

    private data class StartedEncoder(
        val codec: MediaCodec,
        val name: String,
        val colorFormat: Int,
        val useImageApi: Boolean
    )

    /**
     * @return duration of the produced clip in microseconds.
     */
    fun compose(
        text: String,
        samples: FloatArray,
        sampleRate: Int,
        outFile: File,
        onProgress: (Float) -> Unit = {},
        checkCancelled: () -> Unit = {}
    ): Long {
        if (samples.isEmpty()) {
            throw IOException("Refusing to build a video without audio samples")
        }
        val audioDurationUs = samples.size.toLong() * 1_000_000L / sampleRate

        val bitmap = TextFrameRenderer.render(text, typeface)
        val yuv = YuvFrame.from(bitmap)
        bitmap.recycle()

        val pcm = Pcm.floatTo16Bit(samples)
        val audio = AudioEncoder.encode(pcm, sampleRate)

        return try {
            encodeVideo(yuv, audio, audioDurationUs, outFile, onProgress, checkCancelled, true)
        } catch (setup: EncoderSetupException) {
            Log.w(TAG, "Flexible-YUV encoder path failed, retrying with a packed layout", setup)
            checkCancelled()
            encodeVideo(yuv, audio, audioDurationUs, outFile, onProgress, checkCancelled, false)
        }
    }

    // ---------------------------------------------------------------------------------
    // video + muxing
    // ---------------------------------------------------------------------------------

    private fun encodeVideo(
        yuv: YuvFrame,
        audio: AudioEncoder.Result,
        audioDurationUs: Long,
        outFile: File,
        onProgress: (Float) -> Unit,
        checkCancelled: () -> Unit,
        preferFlexible: Boolean
    ): Long {
        outFile.parentFile?.mkdirs()
        if (outFile.exists() && !outFile.delete()) {
            throw IOException("Cannot overwrite ${outFile.absolutePath}")
        }

        val frameDurationUs = 1_000_000L / VideoSpec.FPS
        val totalDurationUs = audioDurationUs + VideoSpec.TAIL_US
        val frameCount = ((totalDurationUs + frameDurationUs - 1) / frameDurationUs)
            .toInt().coerceAtLeast(2)

        val encoder = createVideoEncoder(preferFlexible)
        val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        val videoInfo = MediaCodec.BufferInfo()
        val audioInfo = MediaCodec.BufferInfo()
        var muxerStarted = false
        var videoTrack = -1
        var audioTrack = -1
        var completed = false

        try {
            var frameIndex = 0
            var audioIndex = 0
            var inputDone = false
            var outputDone = false
            val deadline = SystemClock.elapsedRealtime() + ENCODE_TIMEOUT_MS

            while (!outputDone) {
                checkCancelled()
                if (SystemClock.elapsedRealtime() > deadline) {
                    throw IOException("Video encoding timed out")
                }

                if (!inputDone) {
                    val index = encoder.codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (index >= 0) {
                        if (frameIndex >= frameCount) {
                            encoder.codec.queueInputBuffer(
                                index, 0, 0,
                                frameCount * frameDurationUs,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            val size = writeFrame(encoder, index, yuv)
                            encoder.codec.queueInputBuffer(
                                index, 0, size,
                                frameIndex * frameDurationUs, 0
                            )
                            frameIndex++
                        }
                    }
                }

                when (val index = encoder.codec.dequeueOutputBuffer(videoInfo, DEQUEUE_TIMEOUT_US)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit

                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (muxerStarted) {
                            throw IllegalStateException("The video encoder changed its format twice")
                        }
                        val videoFormat = encoder.codec.outputFormat
                        videoTrack = muxer.addTrack(videoFormat)
                        audioTrack = muxer.addTrack(audio.format)
                        muxer.start()
                        muxerStarted = true
                        Log.i(
                            TAG,
                            "Muxer started (video=$videoTrack audio=$audioTrack) " +
                                "${videoFormat.getInteger(MediaFormat.KEY_WIDTH)}x" +
                                "${videoFormat.getInteger(MediaFormat.KEY_HEIGHT)} frames=$frameCount"
                        )
                    }

                    else -> {
                        if (index >= 0) {
                            val isCodecConfig =
                                (videoInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                            if (!isCodecConfig && videoInfo.size > 0) {
                                if (!muxerStarted) {
                                    throw IllegalStateException("Encoder produced data before its format")
                                }
                                val buffer = encoder.codec.getOutputBuffer(index)
                                    ?: throw IOException("Video output buffer is null")
                                buffer.position(videoInfo.offset)
                                buffer.limit(videoInfo.offset + videoInfo.size)
                                muxer.writeSampleData(videoTrack, buffer, videoInfo)

                                // Keep audio and video interleaved: write every audio frame
                                // that happens before the video frame we just muxed.
                                audioIndex = writeAudioUntil(
                                    muxer, audioTrack, audio.chunks, audioIndex,
                                    videoInfo.presentationTimeUs, audioInfo
                                )
                                onProgress(0.95f * frameIndex / frameCount)
                            }
                            encoder.codec.releaseOutputBuffer(index, false)
                            if ((videoInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                outputDone = true
                            }
                        }
                    }
                }
            }

            writeAudioUntil(muxer, audioTrack, audio.chunks, audioIndex, Long.MAX_VALUE, audioInfo)
            completed = true
            onProgress(1f)
        } finally {
            if (muxerStarted) {
                runCatching { muxer.stop() }.onFailure { Log.w(TAG, "muxer.stop failed", it) }
            }
            runCatching { muxer.release() }
            runCatching { encoder.codec.stop() }
            runCatching { encoder.codec.release() }
            if (!completed) {
                runCatching { outFile.delete() }
            }
        }

        val durationUs = frameCount * frameDurationUs
        Log.i(TAG, "Wrote ${outFile.name}: ${outFile.length()} bytes, ${durationUs / 1000} ms")
        return durationUs
    }

    private fun writeAudioUntil(
        muxer: MediaMuxer,
        track: Int,
        chunks: List<AudioEncoder.Chunk>,
        startIndex: Int,
        untilUs: Long,
        info: MediaCodec.BufferInfo
    ): Int {
        if (track < 0) return startIndex
        var index = startIndex
        while (index < chunks.size && chunks[index].ptsUs <= untilUs) {
            val chunk = chunks[index]
            info.set(0, chunk.data.size, chunk.ptsUs, 0)
            muxer.writeSampleData(track, ByteBuffer.wrap(chunk.data), info)
            index++
        }
        return index
    }

    // ---------------------------------------------------------------------------------
    // frame upload
    // ---------------------------------------------------------------------------------

    private fun writeFrame(encoder: StartedEncoder, index: Int, yuv: YuvFrame): Int {
        val codec = encoder.codec
        if (encoder.useImageApi) {
            // Read the capacity *before* getInputImage(): that call invalidates the
            // ByteBuffer object returned by getInputBuffer() for the same index.
            val raw = codec.getInputBuffer(index)
                ?: throw IOException("Video input buffer $index is null")
            val capacity = raw.capacity()

            val image = runCatching { codec.getInputImage(index) }.getOrNull()
                ?: throw EncoderSetupException(
                    "getInputImage() is unavailable on ${encoder.name}", null
                )

            copyPlane(image.planes[0], yuv.y, yuv.width, yuv.height)
            copyPlane(image.planes[1], yuv.u, yuv.width / 2, yuv.height / 2)
            copyPlane(image.planes[2], yuv.v, yuv.width / 2, yuv.height / 2)
            return if (capacity > 0) capacity else yuv.sizeBytes
        }

        val buffer = codec.getInputBuffer(index)
            ?: throw IOException("Video input buffer $index is null")
        buffer.clear()
        buffer.put(yuv.y)
        if (isSemiPlanar(encoder.colorFormat)) {
            val u = yuv.u
            val v = yuv.v
            for (i in u.indices) {
                buffer.put(u[i])
                buffer.put(v[i])
            }
        } else {
            buffer.put(yuv.u)
            buffer.put(yuv.v)
        }
        return buffer.position()
    }

    private fun copyPlane(plane: Image.Plane, data: ByteArray, width: Int, height: Int) {
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride

        if (pixelStride == 1) {
            if (rowStride == width) {
                val count = minOf(data.size, width * height)
                buffer.position(0)
                buffer.put(data, 0, count)
            } else {
                for (row in 0 until height) {
                    buffer.position(row * rowStride)
                    buffer.put(data, row * width, width)
                }
            }
        } else {
            for (row in 0 until height) {
                val rowBase = row * rowStride
                val dataBase = row * width
                for (col in 0 until width) {
                    buffer.put(rowBase + col * pixelStride, data[dataBase + col])
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------
    // encoder selection
    // ---------------------------------------------------------------------------------

    private fun createVideoEncoder(preferFlexible: Boolean): StartedEncoder {
        val candidates = encoderCandidates(preferFlexible)
        if (candidates.isEmpty()) {
            throw EncoderSetupException(
                "No H.264 encoder with a usable YUV420 input format was found on this device", null
            )
        }

        var lastError: Throwable? = null
        for (candidate in candidates) {
            val info = candidate.first
            val colorFormat = candidate.second
            for (withExtras in listOf(true, false)) {
                val codec = try {
                    MediaCodec.createByCodecName(info.name)
                } catch (t: Throwable) {
                    lastError = t
                    continue
                }
                try {
                    codec.configure(
                        buildVideoFormat(info, colorFormat, withExtras),
                        null, null, MediaCodec.CONFIGURE_FLAG_ENCODE
                    )
                    codec.start()
                    Log.i(
                        TAG,
                        "Video encoder: ${info.name} colorFormat=0x" +
                            Integer.toHexString(colorFormat) + " extras=$withExtras"
                    )
                    return StartedEncoder(
                        codec = codec,
                        name = info.name,
                        colorFormat = colorFormat,
                        useImageApi = colorFormat ==
                            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
                    )
                } catch (t: Throwable) {
                    runCatching { codec.release() }
                    lastError = t
                }
            }
        }
        throw EncoderSetupException("Could not start any H.264 encoder", lastError)
    }

    private fun buildVideoFormat(
        info: MediaCodecInfo,
        colorFormat: Int,
        extras: Boolean
    ): MediaFormat {
        val format = MediaFormat.createVideoFormat(
            VideoSpec.MIME_AVC, VideoSpec.WIDTH, VideoSpec.HEIGHT
        )
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
        format.setInteger(MediaFormat.KEY_BIT_RATE, VideoSpec.VIDEO_BITRATE)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, VideoSpec.FPS)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, VideoSpec.I_FRAME_INTERVAL_SECONDS)

        if (extras) {
            val encoderCaps = runCatching {
                info.getCapabilitiesForType(VideoSpec.MIME_AVC).encoderCapabilities
            }.getOrNull()
            when {
                encoderCaps?.isBitrateModeSupported(
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
                ) == true -> format.setInteger(
                    MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
                )

                encoderCaps?.isBitrateModeSupported(
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                ) == true -> format.setInteger(
                    MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                )
            }
            format.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
            format.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
        }
        return format
    }

    private fun encoderCandidates(preferFlexible: Boolean): List<Pair<MediaCodecInfo, Int>> {
        val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(VideoSpec.MIME_AVC, true) }
        }.sortedBy { rankEncoder(it.name) }

        val result = ArrayList<Pair<MediaCodecInfo, Int>>()
        val seen = HashSet<String>()

        // Pass 1: encoders that claim to support 1080x1920@30
        for (info in infos) {
            val caps = runCatching { info.getCapabilitiesForType(VideoSpec.MIME_AVC) }.getOrNull()
                ?: continue
            val colorFormat = pickColorFormat(caps.colorFormats, preferFlexible) ?: continue
            val video = caps.videoCapabilities
            val supported = video == null || runCatching {
                video.areSizeAndRateSupported(VideoSpec.WIDTH, VideoSpec.HEIGHT, VideoSpec.FPS.toDouble())
            }.getOrDefault(false)
            if (supported && seen.add(info.name)) result.add(info to colorFormat)
        }
        // Pass 2: everything else as a last resort
        for (info in infos) {
            val caps = runCatching { info.getCapabilitiesForType(VideoSpec.MIME_AVC) }.getOrNull()
                ?: continue
            val colorFormat = pickColorFormat(caps.colorFormats, preferFlexible) ?: continue
            if (seen.add(info.name)) result.add(info to colorFormat)
        }
        return result
    }

    /** Software AVC encoders first: they accept plain YUV byte buffers on every device. */
    private fun rankEncoder(name: String): Int = when {
        name == "c2.android.avc.encoder" -> 0
        name == "OMX.google.h264.encoder" -> 1
        name.startsWith("c2.android") -> 2
        name.startsWith("OMX.google") -> 3
        else -> 4
    }

    private fun pickColorFormat(formats: IntArray, preferFlexible: Boolean): Int? {
        val flexible = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
        val planar = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
        val semiPlanar = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
        val packedPlanar = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedPlanar
        val packedSemiPlanar = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar

        val order = if (preferFlexible) {
            intArrayOf(flexible, planar, semiPlanar, packedPlanar, packedSemiPlanar)
        } else {
            intArrayOf(planar, semiPlanar, packedPlanar, packedSemiPlanar, flexible)
        }
        for (candidate in order) {
            if (formats.contains(candidate)) return candidate
        }
        return null
    }

    private fun isSemiPlanar(colorFormat: Int): Boolean =
        colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar ||
            colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar
}
