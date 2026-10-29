package com.matthew.matrixhome

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.TextView

val mono: Typeface = Typeface.MONOSPACE
val monoBold: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
val ease = PathInterpolator(0.25f, 0.1f, 0.25f, 1f)

/** Secondary text (paths, counts, package names): white at low strength, so the hue stays the accent. */
val DIM_WHITE = Color.argb(140, 255, 255, 255)

/**
 * One selectable entry, the way a terminal shows a menu: plain monospace text in the hue, and when
 * focused, inverse video (a solid block of the colour with black text). No boxes, art or motion;
 * focus changes instantly, like a cursor. [ink] overrides the hue (the colour entries are written
 * in their own colour). With [pointer] (the pop-up menus) the focused line gets a triangle left of
 * its text, like a menu cursor; every line keeps the space for it, so text never shifts as it moves.
 */
class TermItem(context: Context, hue: Hue, text: String, sizeSp: Float, private val ink: Int = hue.color,
               private val pointer: Boolean = false) : TextView(context) {
    private val dp = resources.displayMetrics.density
    // Named ink, not color: inside GradientDrawable().apply {} a bare `color` is the drawable's own
    // getColor() (null here), which silently left the focus block unfilled.
    private val block = GradientDrawable().apply { cornerRadius = 2 * dp; setColor(ink) }
    private var lit = false

    // Drawn as a shape, not a ▶ character: the fallback font's triangle isn't one monospace cell wide.
    private val triangle = Path()
    private val trianglePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        isFocusable = true
        defaultFocusHighlightEnabled = false
        this.text = text
        typeface = mono
        textSize = sizeSp
        gravity = Gravity.CENTER_VERTICAL
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setPadding((12 * dp + if (pointer) POINTER_SPACE_DP * dp else 0f).toInt(), 0, (12 * dp).toInt(), 0)
        setFocusedLook(false)
    }

    fun setFocusedLook(focused: Boolean) {
        lit = focused
        background = if (focused) block else null
        // Crisp text, no glow: at rest slightly dimmed so the one solid block is what the eye finds.
        setTextColor(if (focused) Color.BLACK else withAlpha(ink, 215))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val x = 12 * dp
        val half = 4 * dp
        triangle.rewind()
        triangle.moveTo(x, h / 2f - half)
        triangle.lineTo(x + 6 * dp, h / 2f)
        triangle.lineTo(x, h / 2f + half)
        triangle.close()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!pointer || !lit) return
        trianglePaint.color = Color.BLACK // on the highlight block
        canvas.drawPath(triangle, trianglePaint)
    }

    companion object {
        /** Extra left padding a [pointer] entry needs: the triangle (6dp) and a gap before the text. */
        const val POINTER_SPACE_DP = 14
    }
}

/** Helper so views can say "this view is focusable and here's what to do when focus changes". */
fun View.onFocus(block: (Boolean) -> Unit) = setOnFocusChangeListener { _, f -> block(f) }
