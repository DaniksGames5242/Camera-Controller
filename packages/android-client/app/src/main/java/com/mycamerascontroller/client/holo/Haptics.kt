package com.mycamerascontroller.client.holo

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View

/**
 * Touch feedback, keyed to the animation rather than to the event.
 *
 * On a phone the finger covers the thing it is acting on, so the confirmation
 * that something happened has to arrive through the skin. Each cue is matched
 * to the motion it accompanies: a light tick when a card takes the press, a
 * heavier thud when a channel materialises, a rising double when a gesture
 * commits.
 */
object Haptics {

    private fun vibrator(context: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    /** A single crisp tick — press, selection, threshold crossed. */
    fun tick(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            view.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
        } else {
            @Suppress("DEPRECATION")
            view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }

    fun longPress(view: View) {
        view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
    }

    fun reject(view: View) {
        val v = vibrator(view.context) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 24, 40, 24), -1))
        } else {
            @Suppress("DEPRECATION") v.vibrate(longArrayOf(0, 24, 40, 24), -1)
        }
    }

    /** The "a channel just came up" thud, matched to the materialise ramp. */
    fun materialise(view: View) {
        val v = vibrator(view.context) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            v.vibrate(
                VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_RISE, 0.55f)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, 0.9f, 40)
                    .compose()
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createOneShot(38, 160))
        } else {
            @Suppress("DEPRECATION") v.vibrate(38)
        }
    }

    /** A continuous cue while a drag gesture approaches its commit point. */
    fun dragTick(view: View, intensity: Float) {
        val v = vibrator(view.context) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            v.vibrate(
                VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, intensity.coerceIn(0.1f, 1f))
                    .compose()
            )
        }
    }
}
