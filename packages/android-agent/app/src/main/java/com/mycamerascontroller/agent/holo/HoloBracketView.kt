package com.mycamerascontroller.agent.holo

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.sin

/**
 * The corner-bracket frame every "waiting for something" state in this
 * product uses instead of a spinner. Wide and searching while idle, tight
 * and lit once the essentials are granted — the same visual grammar as the
 * client apps' connecting state, so a person moving between the agent setup
 * screen and the viewer never sees two different vocabularies for "not
 * ready yet".
 */
class HoloBracketView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var armed: Boolean = false
    private val settle = Spring(0f, 90f, 14f)
    private var detach: (() -> Unit)? = null
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        detach = HoloTicker.add { dt, _ -> settle.to(if (armed) 1f else 0f); settle.update(dt); invalidate() }
    }

    override fun onDetachedFromWindow() { detach?.invoke(); detach = null; super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val t = HoloTicker.time
        val s = settle.value.coerceIn(0f, 1f)
        val accent = if (s > 0.5f) Holo.MINT else Holo.AMBER
        val arm = context.dp(16f) + (1f - s) * context.dp(20f) + sin(t * 2.6f) * (1f - s) * context.dp(5f)

        stroke.strokeWidth = context.dp(2f)
        stroke.color = Holo.alpha(accent, 0.85f)
        corner(canvas, 0f, 0f, arm, 1, 1)
        corner(canvas, w, 0f, arm, -1, 1)
        corner(canvas, 0f, h, arm, 1, -1)
        corner(canvas, w, h, arm, -1, -1)

        // A ring that closes as the essentials arrive.
        val sweepArc = 40f + s * 320f
        val rect = android.graphics.RectF(w / 2f - h * 0.3f, h / 2f - h * 0.3f, w / 2f + h * 0.3f, h / 2f + h * 0.3f)
        stroke.strokeWidth = context.dp(1.4f)
        stroke.color = Holo.alpha(accent, 0.35f)
        canvas.drawArc(rect, t * 46f, sweepArc, false, stroke)
    }

    private fun corner(canvas: Canvas, x: Float, y: Float, arm: Float, dx: Int, dy: Int) {
        canvas.drawLine(x, y, x + arm * dx, y, stroke)
        canvas.drawLine(x, y, x, y + arm * dy, stroke)
    }
}
