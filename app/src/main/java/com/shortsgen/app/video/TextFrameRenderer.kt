package com.shortsgen.app.video

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlin.math.max
import kotlin.math.min

/**
 * Renders one 1080x1920 frame: pure black background + the line of text in white,
 * using the bundled `font.ttf`, wrapped inside the 680x1320 text box.
 *
 * The text size is reduced until the paragraph fits into the box, and as a final
 * safety net the whole paragraph is scaled down so that nothing can ever leave the
 * 680x1320 area.
 */
object TextFrameRenderer {

    const val WIDTH = 1080
    const val HEIGHT = 1920
    const val BOX_WIDTH = 680
    const val BOX_HEIGHT = 1320

    private const val MAX_TEXT_SIZE_PX = 88f
    private const val MIN_TEXT_SIZE_PX = 22f
    private const val TEXT_SIZE_STEP_PX = 2f
    private const val LINE_SPACING_MULT = 1.12f

    fun render(text: String, typeface: Typeface): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.BLACK)

        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            color = Color.WHITE
            this.typeface = typeface
            textAlign = Paint.Align.LEFT
            isFakeBoldText = false
        }

        var textSize = MAX_TEXT_SIZE_PX
        var layout = buildLayout(text, paint, textSize)
        while (textSize > MIN_TEXT_SIZE_PX && layout.height > BOX_HEIGHT) {
            textSize -= TEXT_SIZE_STEP_PX
            layout = buildLayout(text, paint, textSize)
        }

        var widestLine = 1f
        for (i in 0 until layout.lineCount) {
            widestLine = max(widestLine, layout.getLineWidth(i))
        }
        val scale = min(
            1f,
            min(BOX_WIDTH / widestLine, BOX_HEIGHT / max(1, layout.height).toFloat())
        )

        val drawnWidth = layout.width * scale
        val drawnHeight = layout.height * scale
        val left = (WIDTH - drawnWidth) / 2f
        val top = (HEIGHT - drawnHeight) / 2f

        canvas.save()
        canvas.translate(left, top)
        canvas.scale(scale, scale)
        layout.draw(canvas)
        canvas.restore()

        return bitmap
    }

    private fun buildLayout(text: String, paint: TextPaint, textSize: Float): StaticLayout {
        paint.textSize = textSize
        return StaticLayout.Builder
            .obtain(text, 0, text.length, paint, BOX_WIDTH)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, LINE_SPACING_MULT)
            .setIncludePad(false)
            .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .build()
    }
}
