package com.mycamerascontroller.client.holo

import android.content.Context
import android.graphics.Color
import android.util.TypedValue

/** The one palette every surface in the Android client draws from. */
object Holo {
    const val VOID = 0xFF02040A.toInt()
    const val CYAN = 0xFF35E6FF.toInt()
    const val MINT = 0xFF48FFC0.toInt()
    const val VIOLET = 0xFF8B6BFF.toInt()
    const val MAGENTA = 0xFFFF3FA4.toInt()
    const val AMBER = 0xFFFFB23F.toInt()
    const val RED = 0xFFFF4D5E.toInt()
    const val INK = 0xFFDCF5FF.toInt()
    const val INK_DIM = 0xFF7FA5B8.toInt()

    fun alpha(color: Int, a: Float): Int =
        Color.argb((a.coerceIn(0f, 1f) * 255).toInt(), Color.red(color), Color.green(color), Color.blue(color))

    /** A stable hue per device, so a camera always reads as the same colour. */
    fun tintFor(id: String): Int {
        var hash = 0
        for (ch in id) hash = (hash * 31 + ch.code) and 0x7FFFFFFF
        val t = (hash % 1000) / 1000f
        return when {
            t < 0.5f -> blend(CYAN, VIOLET, t * 2f)
            else -> blend(VIOLET, MAGENTA, (t - 0.5f) * 2f)
        }
    }

    fun blend(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        return Color.argb(
            (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * k).toInt(),
            (Color.red(a) + (Color.red(b) - Color.red(a)) * k).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * k).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * k).toInt(),
        )
    }
}

fun Context.dp(value: Float): Float =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

fun Context.sp(value: Float): Float =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)
