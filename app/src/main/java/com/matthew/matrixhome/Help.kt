package com.matthew.matrixhome

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The help screen: a man page for the remote, the line and its two menus. Shown once on first
 * launch and from system/ > help.
 * It covers everything in near-black so nothing behind competes with it; HomeActivity closes it on
 * any button. Keys are drawn in inverse video, exactly as in the key bar, so they look the same here
 * as they will at the bottom of the home screen.
 */
class HelpView(context: Context, private val hue: Hue) : FrameLayout(context) {
    private val dp = resources.displayMetrics.density
    private val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    init {
        setBackgroundColor(Color.argb(242, 0, 0, 0))
        line("$ man matrix", 13f, hue.color)
        line("How to use your TV", 30f, Color.WHITE, top = 6).setShadowLayer(8 * dp, 0f, 0f, withAlpha(hue.color, 200))

        gap(22)
        row(key("▲ ▼ ◀ ▶"), "move the cursor (the solid block)")
        row(key("OK"), "open what the cursor is on")
        row(key("HOLD OK"), "add an app to favorites, or remove it")
        row(key("BACK"), "close a menu, or jump back to the first favorite")

        gap(22)
        row(path("favorites"), "your main apps, first on the line (up to five)")
        row(path("apps/"), "a pop-up menu of every app on the TV")
        row(path("system/"), "help, settings, your name, weather, wallpaper, colour, screensaver")

        gap(22)
        line("The bottom line always shows what the buttons do.", 13f, DIM_WHITE)
        line("Open this again any time: system/ > help", 13f, DIM_WHITE, top = 2)
        gap(26)
        line(SpannableStringBuilder(key("OK")).append("  start"), 14f, Color.WHITE)

        addView(column, LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER_VERTICAL or Gravity.START)
            .apply { marginStart = (96 * dp).toInt() })
    }

    private fun line(text: CharSequence, sizeSp: Float, color: Int, top: Int = 0) = TextView(context).apply {
        this.text = text
        typeface = mono
        textSize = sizeSp
        setTextColor(color)
        column.addView(this, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = (top * dp).toInt() })
    }

    private fun gap(heightDp: Int) = column.addView(View(context), LinearLayout.LayoutParams(1, (heightDp * dp).toInt()))

    /** A two-column line: the key or path in a fixed-width column, then what it does. */
    private fun row(left: CharSequence, right: String) {
        column.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply { text = left; typeface = mono; textSize = 15f; setTextColor(Color.WHITE) },
                LinearLayout.LayoutParams((150 * dp).toInt(), WRAP_CONTENT))
            addView(TextView(context).apply { text = right; typeface = mono; textSize = 15f; setTextColor(Color.WHITE) })
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = (8 * dp).toInt() })
    }

    /** ` KEY ` in inverse video: a block of the hue with black bold text. */
    private fun key(name: String) = SpannableStringBuilder(" $name ").apply {
        setSpan(BackgroundColorSpan(hue.color), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        setSpan(ForegroundColorSpan(Color.BLACK), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun path(name: String) = SpannableStringBuilder(name).apply {
        setSpan(ForegroundColorSpan(hue.color), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}
