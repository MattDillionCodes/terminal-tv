package com.matthew.matrixhome

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface

/**
 * The code's character set, shared by the rain and the code tiles: Simon Whiteley's recipe of
 * mirrored half-width katakana plus numerals and a few symbols.
 *
 * Glyphs are pre-rendered into ALPHA_8 atlases (one row of cells, white on transparent). An
 * alpha-only bitmap draws in the paint's colour, so any hue is just a different `paint.color`.
 */
object Glyphs {
    private val probe = Paint().apply { typeface = Typeface.MONOSPACE }

    /** Half-width katakana U+FF66..U+FF9D, if the system font has them (Noto CJK does on Google TV). */
    private val kana = (0xFF66..0xFF9D).map { it.toChar() }.filter { probe.hasGlyph(it.toString()) }.joinToString("")

    val chars = kana + "0123456789Z:.=*+-<>¦|\""
    val count = chars.length
    private val mirrored = kana.length // the katakana are drawn back to front, as in the films

    private val cache = HashMap<String, Bitmap>()

    /**
     * [count] glyphs in [cellW] x [cellH] cells. With [blur] > 0 each cell is padded by [blur] on
     * every side and the glyphs blurred by it: the soft halo drawn behind the rain's bright tracers.
     */
    fun atlas(cellW: Int, cellH: Int, blur: Int = 0): Bitmap = cache.getOrPut("$cellW/$cellH/$blur") {
        val w = cellW + 2 * blur
        val h = cellH + 2 * blur
        val bmp = Bitmap.createBitmap(w * count, h, Bitmap.Config.ALPHA_8)
        val c = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = cellH * 0.8f
            typeface = Typeface.MONOSPACE
            textAlign = Paint.Align.CENTER
            if (blur > 0) maskFilter = BlurMaskFilter(blur * 0.9f, BlurMaskFilter.Blur.NORMAL)
        }
        val baseline = blur + cellH / 2f - (paint.ascent() + paint.descent()) / 2
        chars.forEachIndexed { i, ch ->
            val cx = i * w + w / 2f
            c.save()
            if (i < mirrored) c.scale(-1f, 1f, cx, 0f)
            c.drawText(ch.toString(), cx, baseline, paint)
            c.restore()
        }
        bmp
    }
}
