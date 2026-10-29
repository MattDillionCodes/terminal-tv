package com.matthew.matrixhome

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow
import kotlin.random.Random

/**
 * The digital rain, done the way the films do it: the glyphs sit still in a fixed grid and what
 * "falls" is light. Each column carries a couple of raindrops (a bright tracer with a fading
 * tail) sweeping down through the stationary symbols, while random cells quietly change glyph.
 * Glyphs come from [Glyphs]: mirrored half-width katakana plus numerals and a few symbols.
 *
 * Cheap enough for the TV: the glyphs are pre-rendered once into an ALPHA_8 atlas (drawn with the
 * paint's colour, so re-tinting for a new hue costs nothing), only lit cells are drawn, and the
 * simulation steps at ~30 fps, which is also how the film's rain reads: cell by cell, not smooth.
 */
class RainView(context: Context) : View(context) {
    var hue: Hue = Hues.default
        set(value) { field = value; invalidate() }

    /** Overall strength, 0..1 (animated for the intro and ambient mode). At 0 it stops drawing entirely. */
    var intensity = 1f
        set(value) { field = value; invalidate() }

    var running = false
        set(value) {
            field = value
            last = SystemClock.uptimeMillis()
            if (value) invalidate()
        }

    private val density = resources.displayMetrics.density
    private val cellW = (12 * density).toInt()
    private val cellH = (14 * density).toInt()
    private val pad = (5 * density).toInt() // room around each glow so the blur isn't clipped

    private val atlas = Glyphs.atlas(cellW, cellH)
    private val glowAtlas = Glyphs.atlas(cellW, cellH, blur = pad)

    private var cols = 0
    private var rows = 0
    private var cells = ByteArray(0)
    private var head = FloatArray(0)  // per drop: row of the tracer (can be negative: not arrived yet)
    private var speed = FloatArray(0) // rows per second
    private var length = IntArray(0)  // tail length in rows
    private var last = 0L
    private val rnd = Random(SystemClock.uptimeMillis())

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val src = Rect()
    private val dst = Rect()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        cols = ceil(w / cellW.toFloat()).toInt()
        rows = ceil(h / cellH.toFloat()).toInt()
        cells = ByteArray(cols * rows) { rnd.nextInt(Glyphs.count).toByte() }
        val drops = cols * DROPS_PER_COLUMN
        head = FloatArray(drops)
        speed = FloatArray(drops)
        length = IntArray(drops)
        for (d in 0 until drops) respawn(d, scatter = true)
    }

    /** A new drop above the screen (or, at startup, anywhere in or above it so the rain is already falling). */
    private fun respawn(d: Int, scatter: Boolean = false) {
        speed[d] = 6f + rnd.nextFloat() * 16f
        length[d] = 6 + rnd.nextInt((rows * 0.7f).toInt().coerceAtLeast(7))
        head[d] = if (scatter) rnd.nextFloat() * (rows + length[d]) - rows * 0.5f
        else -rnd.nextFloat() * rows * 0.9f // the gap before it arrives gives the rain its rhythm
    }

    private fun step(dt: Float) {
        for (d in head.indices) {
            head[d] += speed[d] * dt
            if (head[d] - length[d] > rows) respawn(d)
        }
        repeat((cells.size * CYCLE_PER_SECOND * dt).toInt() + 1) {
            cells[rnd.nextInt(cells.size)] = rnd.nextInt(Glyphs.count).toByte()
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (cols == 0 || intensity <= 0.01f) return
        val now = SystemClock.uptimeMillis()
        if (running) step(((now - last) / 1000f).coerceAtMost(0.1f))
        last = now

        for (d in head.indices) {
            val col = d / DROPS_PER_COLUMN
            val x = col * cellW
            val tip = floor(head[d]).toInt()
            val len = length[d]
            for (i in 0 until len) {
                val row = tip - i
                if (row < 0) break
                if (row >= rows) continue
                val y = row * cellH
                val strength = (1f - i / len.toFloat()).pow(1.6f) * intensity
                val g = cells[row * cols + col].toInt()
                if (i == 0) {
                    // The tracer: a soft halo in the hue, then the glyph itself nearly white.
                    paint.color = withAlpha(hue.color, (200 * strength).toInt())
                    val gw = cellW + 2 * pad
                    src.set(g * gw, 0, (g + 1) * gw, cellH + 2 * pad)
                    dst.set(x - pad, y - pad, x + cellW + pad, y + cellH + pad)
                    canvas.drawBitmap(glowAtlas, src, dst, paint)
                    paint.color = withAlpha(hue.hot, (255 * strength).toInt())
                } else {
                    paint.color = withAlpha(hue.color, (235 * strength).toInt())
                }
                src.set(g * cellW, 0, (g + 1) * cellW, cellH)
                dst.set(x, y, x + cellW, y + cellH)
                canvas.drawBitmap(atlas, src, dst, paint)
            }
        }
        if (running) postInvalidateDelayed(FRAME_MS)
    }

    private companion object {
        const val DROPS_PER_COLUMN = 2
        const val CYCLE_PER_SECOND = 0.04f // share of all cells that swap glyph each second
        const val FRAME_MS = 33L
    }
}
