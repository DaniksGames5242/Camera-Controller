package com.mycamerascontroller.client.holo

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * A holographic plate you can put content on.
 *
 * Used for every surface that would otherwise have been a card or a dialog.
 * It materialises rather than fading: a noise-thresholded dissolve sweeps
 * across it with a burning edge, exactly as the slabs do on the desktop
 * client, so a sheet arriving on a phone and a channel opening on a monitor
 * read as the same physical event.
 */
class HoloPlateLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    var accent: Int = Holo.CYAN
    /** 0 = scattered, 1 = solid. Driven by the reveal spring below. */
    private val reveal = Spring(0f, 110f, 17f)
    private var detach: (() -> Unit)? = null

    private val body = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val burn = Paint(Paint.ANTI_ALIAS_FLAG)
    private val outline = Path()
    private val cut = context.dp(20f)

    init {
        setWillNotDraw(false)
        // Content is laid out normally; only the surface under it is custom.
        clipToPadding = false
    }

    fun materialise() { reveal.set(0f); reveal.to(1f) }

    fun dematerialise(onDone: () -> Unit) {
        reveal.to(0f)
        postDelayed(onDone, 260)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        detach = HoloTicker.add { dt, _ ->
            reveal.update(dt)
            invalidate()
        }
    }

    override fun onDetachedFromWindow() { detach?.invoke(); detach = null; super.onDetachedFromWindow() }

    override fun dispatchDraw(canvas: Canvas) {
        val r = reveal.value.coerceIn(0f, 1f)
        canvas.save()
        // The plate rises into place and settles, and its content rides with it.
        canvas.translate(0f, (1f - r) * height * 0.16f)
        canvas.scale(0.97f + 0.03f * r, 0.97f + 0.03f * r, width / 2f, height.toFloat())
        super.dispatchDraw(canvas)
        canvas.restore()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = reveal.value.coerceIn(0f, 1f)
        val t = HoloTicker.time
        if (r <= 0.001f) return

        canvas.save()
        canvas.translate(0f, (1f - r) * h * 0.16f)

        outline.reset()
        outline.moveTo(0f, cut)
        outline.lineTo(cut, 0f)
        outline.lineTo(w - cut, 0f)
        outline.lineTo(w, cut)
        outline.lineTo(w, h)
        outline.lineTo(0f, h)
        outline.close()

        canvas.save()
        canvas.clipPath(outline)
        body.shader = LinearGradient(
            0f, 0f, 0f, h,
            Holo.alpha(0xFF0B1F30.toInt(), 0.97f * r),
            Holo.alpha(0xFF050B14.toInt(), 0.99f * r),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, body)
        body.shader = null

        // Dissolve front: content above the line has condensed, below it is
        // still forming. It burns as it passes.
        val front = h * (1.25f * r)
        body.shader = LinearGradient(
            0f, front - context.dp(70f), 0f, front,
            intArrayOf(Holo.alpha(accent, 0f), Holo.alpha(accent, 0.10f), Holo.alpha(accent, 0.30f)),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, front - context.dp(70f), w, front, body)
        body.shader = null
        burn.color = Holo.alpha(Holo.MINT, (1f - r) * 0.9f)
        canvas.drawRect(0f, front - context.dp(2f), w, front, burn)

        // Slow scan, the same one every other surface carries.
        val scanY = ((t * 0.28f) % 1f) * h
        body.shader = LinearGradient(
            0f, scanY - context.dp(16f), 0f, scanY + context.dp(16f),
            intArrayOf(Holo.alpha(accent, 0f), Holo.alpha(accent, 0.09f * r), Holo.alpha(accent, 0f)),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, scanY - context.dp(16f), w, scanY + context.dp(16f), body)
        body.shader = null
        canvas.restore()

        stroke.strokeWidth = context.dp(1.2f)
        stroke.color = Holo.alpha(accent, 0.5f * r)
        canvas.drawPath(outline, stroke)

        // Grab rail: the affordance that says this sheet answers to a drag.
        val railW = w * 0.14f
        stroke.strokeWidth = context.dp(3.5f)
        stroke.strokeCap = Paint.Cap.ROUND
        stroke.color = Holo.alpha(Holo.INK_DIM, 0.55f * r)
        canvas.drawLine(w / 2f - railW / 2f, context.dp(12f), w / 2f + railW / 2f, context.dp(12f), stroke)
        stroke.strokeCap = Paint.Cap.BUTT

        canvas.restore()
    }
}
