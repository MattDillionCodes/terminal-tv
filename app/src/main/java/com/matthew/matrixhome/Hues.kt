package com.matthew.matrixhome

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ColorSpace

/**
 * One phosphor colour: the wallpaper set in assets/wallpapers/[id]/ and the accent every piece of
 * UI is drawn in. [color] is the measured glyph-body colour of that set's wallpapers (the brightest
 * 0.25-1% of pixels, skipping the white-hot cursors), so the UI sits in the same light as the art.
 */
class Hue(val id: String, val label: String, val color: Int) {
    /** The leading "cursor" glyph of the rain and focus glints: the hue pushed most of the way to white. */
    val hot: Int = blend(color, Color.WHITE, 0.72f)
    /** Unfocused borders, dividers: the hue at low strength. */
    val dim: Int = Color.argb(90, Color.red(color), Color.green(color), Color.blue(color))
}

object Hues {
    val all = listOf(
        Hue("red", "Red pill", Color.rgb(255, 74, 68)),
        Hue("orange", "Orange", Color.rgb(255, 154, 60)),
        Hue("yellow", "Yellow", Color.rgb(242, 220, 58)),
        Hue("green", "Green code", Color.rgb(61, 245, 110)),
        Hue("blue", "Blue pill", Color.rgb(74, 168, 255)),
        Hue("indigo", "Indigo", Color.rgb(143, 123, 255)),
        Hue("violet", "Violet", Color.rgb(224, 107, 255)),
    )
    val default = all.first { it.id == "green" }

    fun byId(id: String?) = all.firstOrNull { it.id == id } ?: default
}

/** The six wallpaper scenes, the same in every hue (assets/wallpapers/<hue>/<scene>.webp). */
object Scenes {
    val all = listOf("cascade", "depth", "tunnel", "floor", "scan", "wet-glass")

    fun label(scene: String) = scene.replace('-', ' ').uppercase()

    /**
     * Decodes straight into a GPU-resident HARDWARE bitmap: the wallpapers are already exactly
     * 1920x1080 sRGB, so there's no scaling or colour conversion, and no texture upload per frame.
     */
    fun load(context: Context, hue: Hue, scene: String): Bitmap? = runCatching {
        context.assets.open("wallpapers/${hue.id}/$scene.webp").use { input ->
            BitmapFactory.decodeStream(input, null, BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.HARDWARE
                inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
            })
        }
    }.getOrNull()
}

fun blend(a: Int, b: Int, t: Float): Int = Color.rgb(
    (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
    (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
    (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt(),
)

fun withAlpha(color: Int, alpha: Int) = Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
