package com.mycamerascontroller.agent.holo

import android.content.Context
import android.graphics.Color
import android.util.TypedValue

/** The palette shared with the client apps — this device is one node in the
    same holographic system, even though its own screen is seen only during
    setup. */
object Holo {
    const val VOID = 0xFF02040A.toInt()
    const val CYAN = 0xFF35E6FF.toInt()
    const val MINT = 0xFF48FFC0.toInt()
    const val VIOLET = 0xFF8B6BFF.toInt()
    const val MAGENTA = 0xFFFF3FA4.toInt()
    const val AMBER = 0xFFFFB23F.toInt()
    const val INK = 0xFFDCF5FF.toInt()
    const val INK_DIM = 0xFF7FA5B8.toInt()

    fun alpha(color: Int, a: Float): Int =
        Color.argb((a.coerceIn(0f, 1f) * 255).toInt(), Color.red(color), Color.green(color), Color.blue(color))
}

fun Context.dp(value: Float): Float =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

fun Context.sp(value: Float): Float =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)
