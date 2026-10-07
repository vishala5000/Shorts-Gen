package com.shortsgen.app.video

/** PCM helpers: float32 -> interleaved 16 bit little endian, plus a fallback resampler. */
object Pcm {

    fun floatTo16Bit(samples: FloatArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        var i = 0
        for (sample in samples) {
            val clamped = if (sample > 1f) 1f else if (sample < -1f) -1f else sample
            val value = (clamped * 32767f).toInt()
            out[i] = (value and 0xFF).toByte()
            out[i + 1] = ((value shr 8) and 0xFF).toByte()
            i += 2
        }
        return out
    }

    fun sampleCount(pcm: ByteArray): Int = pcm.size / 2

    fun durationUs(pcm: ByteArray, sampleRate: Int): Long =
        sampleCount(pcm).toLong() * 1_000_000L / sampleRate.coerceAtLeast(1)

    /** Mono 16 bit linear-interpolation resampler (only used when a device has no AAC
     *  encoder that accepts the model's 22050 Hz). */
    fun resample(src: ByteArray, srcRate: Int, dstRate: Int): ByteArray {
        if (srcRate == dstRate) return src
        val srcCount = src.size / 2
        if (srcCount == 0) return ByteArray(0)
        val dstCount = ((srcCount.toLong() * dstRate) / srcRate).toInt().coerceAtLeast(1)
        val ratio = srcRate.toDouble() / dstRate
        val out = ByteArray(dstCount * 2)

        for (i in 0 until dstCount) {
            val pos = i * ratio
            val i0 = pos.toInt().coerceIn(0, srcCount - 1)
            val i1 = (i0 + 1).coerceAtMost(srcCount - 1)
            val frac = (pos - i0).toFloat()
            val s0 = readSample(src, i0)
            val s1 = readSample(src, i1)
            val value = (s0 + (s1 - s0) * frac).toInt().coerceIn(-32768, 32767)
            out[i * 2] = (value and 0xFF).toByte()
            out[i * 2 + 1] = ((value shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun readSample(pcm: ByteArray, index: Int): Int {
        val low = pcm[index * 2].toInt() and 0xFF
        val high = pcm[index * 2 + 1].toInt()
        return (high shl 8) or low
    }
}
