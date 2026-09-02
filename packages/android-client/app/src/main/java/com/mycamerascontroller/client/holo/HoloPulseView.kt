package com.mycamerascontroller.client.holo

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * The "still looking" indicator for an empty node list.
 *
 * Three rings at incommensurable rates with an expanding sonar pulse through
 * them: it reads as an instrument actively sweeping rather than as a spinner
 * waiting for a network call, which is the honest description of what the
 * app is doing while no agent has announced itself.
 */
class HoloPulseView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val oval = RectF()
    private var detach: (() -> Unit)? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        detach = HoloTicker.add { _, _ -> if (isShown) invalidate() }
    }

    override fun onDetachedFromWindow() { detach?.invoke(); detach = null; super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        val t = HoloTicker.time
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) / 2f - context.dp(3f)

        val rings = listOf(
            Triple(1.0f, 42f, Holo.CYAN),
            Triple(0.72f, -64f, Holo.VIOLET),
            Triple(0.44f, 30f, Holo.MAGENTA),
        )
        stroke.strokeWidth = context.dp(1.2f)
        for ((scale, speed, color) in rings) {
            oval.set(cx - r * scale, cy - r * scale, cx + r * scale, cy + r * scale)
            stroke.color = Holo.alpha(color, 0.55f)
            // Broken arcs rather than closed circles: rotation is only legible
            // on a shape that has ends.
            canvas.drawArc(oval, t * speed, 210f, false, stroke)
        }

        // Sonar pulse expanding through the rings and fading out.
        val pulse = (t * 0.55f) % 1f
        stroke.strokeWidth = context.dp(1.6f)
        stroke.color = Holo.alpha(Holo.MINT, (1f - pulse) * 0.7f)
        canvas.drawCircle(cx, cy, r * pulse, stroke)
    }
}
