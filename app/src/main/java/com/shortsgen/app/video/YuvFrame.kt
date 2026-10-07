package com.shortsgen.app.video

import android.graphics.Bitmap
import kotlin.math.abs

/**
 * A single, static 1080x1920 frame in YUV 4:2:0 (separate Y / U / V planes).
 *
 * The renderer only ever produces white text on a black background, so almost every
 * pixel is a shade of grey. Grey pixels get a constant chroma (128) which keeps the
 * conversion fast and the colours perfectly neutral.
 *
 * Conversion uses BT.709 coefficients with the limited ("TV") range, which is what
 * H.264 players expect for 1080p content.
 */
class YuvFrame private constructor(
    val width: Int,
    val height: Int,
    val y: ByteArray,
    val u: ByteArray,
    val v: ByteArray
) {

    val sizeBytes: Int get() = width * height * 3 / 2

    companion object {

        /** grey level (0..255) -> limited range luma (16..235) */
        private val GREY_TO_Y = IntArray(256) { g ->
            (((47 * g + 157 * g + 16 * g + 128) shr 8) + 16).coerceIn(16, 235)
        }

        fun from(bitmap: Bitmap): YuvFrame {
            val w = bitmap.width
            val h = bitmap.height
            require(w % 2 == 0 && h % 2 == 0) { "Frame size must be even: ${w}x$h" }

            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

            val yPlane = ByteArray(w * h)
            val uPlane = ByteArray(w * h / 4)
            val vPlane = ByteArray(w * h / 4)

            var yIndex = 0
            var uvIndex = 0
            for (row in 0 until h) {
                val sampleChroma = (row and 1) == 0
                val rowBase = row * w
                for (col in 0 until w) {
                    val pixel = pixels[rowBase + col]
                    val r = (pixel shr 16) and 0xFF
                    val g = (pixel shr 8) and 0xFF
                    val b = pixel and 0xFF

                    val grey = (r == g) && (g == b)
                    yPlane[yIndex++] = if (grey) {
                        GREY_TO_Y[r].toByte()
                    } else {
                        (((47 * r + 157 * g + 16 * b + 128) shr 8) + 16)
                            .coerceIn(16, 235).toByte()
                    }

                    if (sampleChroma && (col and 1) == 0) {
                        if (grey) {
                            uPlane[uvIndex] = 128.toByte()
                            vPlane[uvIndex] = 128.toByte()
                        } else {
                            val cb = ((-26 * r - 87 * g + 112 * b + 128) shr 8) + 128
                            val cr = ((112 * r - 102 * g - 10 * b + 128) shr 8) + 128
                            uPlane[uvIndex] = cb.coerceIn(16, 240).toByte()
                            vPlane[uvIndex] = cr.coerceIn(16, 240).toByte()
                        }
                        uvIndex++
                    }
                }
            }
            return YuvFrame(w, h, yPlane, uPlane, vPlane)
        }

        /** Absolute difference of two unsigned bytes stored in signed [ByteArray]s. */
        fun diff(a: ByteArray, b: ByteArray): Int {
            var sum = 0
            val n = minOf(a.size, b.size)
            for (i in 0 until n) sum += abs(a[i].toInt() - b[i].toInt())
            return sum
        }
    }
}
