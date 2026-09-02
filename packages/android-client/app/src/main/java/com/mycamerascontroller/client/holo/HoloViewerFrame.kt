package com.mycamerascontroller.client.holo

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.sin

/**
 * The holographic frame around a live channel — the full-screen counterpart
 * of a desktop slab's chrome, minus the parts a phone doesn't need (there is
 * no pointer to react to, and the video itself already fills the screen).
 *
 * Draws over the WebRTC surface: corner brackets that widen while connecting
 * and settle once live, a slow scan sweep, chromatic-fringed status text, and
 * a recording tally. All state is fed in rather than owned, so the activity
 * stays the source of truth for what is actually happening.
 */
class HoloViewerFrame @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var accent: Int = Holo.CYAN
    var deviceName: String = ""
    var connected: Boolean = false
        set(value) { if (field != value) { field = value; settle.to(if (value) 1f else 0f) } }
    var recording: Boolean = false
    var resolutionLabel: String = ""
    var elapsedLabel: String = "—"

    private val settle = Spring(0f, 90f, 14f)
    private var detach: (() -> Unit)? = null

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        letterSpacing = 0.05f
    }
    private val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        letterSpacing = 0.16f
    }
    private val wash = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        text.textSize = context.sp(19f)
        sub.textSize = context.sp(10f)
        isClickable = false
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        detach = HoloTicker.add { dt, _ -> settle.update(dt); invalidate() }
    }

    override fun onDetachedFromWindow() { detach?.invoke(); detach = null; super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val t = HoloTicker.time
        val s = settle.value.coerceIn(0f, 1f)
        val inset = context.dp(18f)
        val arm = context.dp(14f) + (1f - s) * context.dp(30f) + sin(t * 3f) * (1f - s) * context.dp(6f)

        // Corner brackets — wide and searching while connecting, tight once live.
        stroke.strokeWidth = context.dp(2f)
        stroke.color = Holo.alpha(accent, 0.75f)
        drawCorner(canvas, inset, inset, arm, 1, 1)
        drawCorner(canvas, w - inset, inset, arm, -1, 1)
        drawCorner(canvas, inset, h - inset, arm, 1, -1)
        drawCorner(canvas, w - inset, h - inset, arm, -1, -1)

        // Vignette so the top and bottom HUD text always sits on something dark.
        wash.shader = LinearGradient(
            0f, 0f, 0f, h * 0.22f,
            Holo.alpha(0xFF000000.toInt(), 0.55f), Holo.alpha(0xFF000000.toInt(), 0f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h * 0.22f, wash)
        wash.shader = LinearGradient(
            0f, h * 0.8f, 0f, h,
            Holo.alpha(0xFF000000.toInt(), 0f), Holo.alpha(0xFF000000.toInt(), 0.6f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, h * 0.8f, w, h, wash)
        wash.shader = null

        // Title, with the same chromatic ghosting every surface carries.
        val tx = inset
        val ty = inset + context.dp(30f)
        text.color = Holo.alpha(Holo.MAGENTA, 0.32f)
        canvas.drawText(deviceName, tx + context.dp(1.3f), ty, text)
        text.color = Holo.alpha(Holo.CYAN, 0.32f)
        canvas.drawText(deviceName, tx - context.dp(1.3f), ty, text)
        text.color = Holo.INK
        canvas.drawText(deviceName, tx, ty, text)

        sub.color = Holo.alpha(if (connected) Holo.MINT else Holo.AMBER, 0.9f)
        val status = if (connected) "ПРЯМАЯ ТРАНСЛЯЦИЯ" else "СОГЛАСОВАНИЕ КАНАЛА…"
        canvas.drawText(status, tx, ty + context.dp(18f), sub)

        // Bottom-left metadata.
        sub.color = Holo.alpha(Holo.INK_DIM, 0.85f)
        canvas.drawText("$resolutionLabel   ·   $elapsedLabel", inset, h - inset - context.dp(8f), sub)

        // Recording tally.
        if (recording) {
            val pulse = 0.5f + 0.5f * sin(t * 5f)
            wash.color = Holo.alpha(Holo.RED, 0.6f + 0.4f * pulse)
            canvas.drawCircle(w - inset - context.dp(6f), inset + context.dp(6f), context.dp(5f) + pulse * context.dp(2f), wash)
        }

        // Slow horizontal scan.
        val scanY = ((t * 0.16f) % 1f) * h
        wash.shader = LinearGradient(
            0f, scanY - context.dp(30f), 0f, scanY + context.dp(30f),
            intArrayOf(Holo.alpha(accent, 0f), Holo.alpha(accent, 0.05f), Holo.alpha(accent, 0f)),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, scanY - context.dp(30f), w, scanY + context.dp(30f), wash)
        wash.shader = null
    }

    private fun drawCorner(canvas: Canvas, x: Float, y: Float, arm: Float, dx: Int, dy: Int) {
        canvas.drawLine(x, y, x + arm * dx, y, stroke)
        canvas.drawLine(x, y, x, y + arm * dy, stroke)
    }
}
