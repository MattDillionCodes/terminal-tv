package com.matthew.matrixhome

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.min

/**
 * The pop-up menu: a small terminal window over the home screen with one entry per line, like
 * `ls -1`. A title bar (`~/apps` and a count), a rule, then the entries; past [maxRows] it scrolls
 * with the focus. [reveal] draws it up from its bottom edge and prints the lines one after another.
 * HomeActivity owns what the entries do and closes it on Back.
 */
class MenuView(context: Context, hue: Hue, title: String, count: String, val entries: List<View>, maxRows: Int) :
    LinearLayout(context) {
    private val dp = resources.displayMetrics.density

    init {
        orientation = VERTICAL
        background = GradientDrawable().apply {
            cornerRadius = 2 * dp
            setColor(Color.argb(244, 0, 0, 0))
            setStroke(dp.toInt().coerceAtLeast(1), hue.color)
        }
        val pad = dp.toInt().coerceAtLeast(1) // inside the 1dp border
        setPadding(pad, pad, pad, (6 * dp).toInt())

        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((12 * dp).toInt(), 0, (12 * dp).toInt(), 0)
            addView(TextView(context).apply { text = title; typeface = monoBold; textSize = 12f; setTextColor(hue.color) })
            addView(View(context), LayoutParams(0, 1, 1f))
            addView(TextView(context).apply { text = count; typeface = mono; textSize = 12f; setTextColor(DIM_WHITE) })
        }, LayoutParams(MATCH_PARENT, (28 * dp).toInt()))
        addView(View(context).apply { setBackgroundColor(hue.dim) }, LayoutParams(MATCH_PARENT, dp.toInt().coerceAtLeast(1))
            .apply { bottomMargin = (4 * dp).toInt() })

        val rowH = (ROW_DP * dp).toInt()
        val list = LinearLayout(context).apply {
            orientation = VERTICAL
            entries.forEach { addView(it, LayoutParams(MATCH_PARENT, rowH)) }
        }
        addView(ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = OVER_SCROLL_NEVER
            addView(list)
        }, LayoutParams(MATCH_PARENT, rowH * min(entries.size, maxRows).coerceAtLeast(1)))
    }

    /** Opens like a terminal window being drawn: the frame grows up from the line, then each entry prints. */
    fun reveal() {
        scaleY = 0f
        post { // measured by now: grow from the bottom edge, where the line it came from is
            pivotY = height.toFloat()
            animate().scaleY(1f).setDuration(140).setInterpolator(ease).start()
        }
        entries.forEachIndexed { i, v ->
            v.alpha = 0f
            v.animate().alpha(1f).setStartDelay(110L + min(i, 20) * 14L).setDuration(60).start()
        }
    }

    companion object {
        const val ROW_DP = 30
    }
}
