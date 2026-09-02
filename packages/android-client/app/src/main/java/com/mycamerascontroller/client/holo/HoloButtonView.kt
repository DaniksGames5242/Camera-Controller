package com.mycamerascontroller.client.holo

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max

/**
 * A control that behaves like a switch on a light table.
 *
 * Pressing it does not swap a background: the plate sinks, light pools under
 * the finger, a charge sweeps the bevelled outline, and — because a finger
 * hides the control it is pressing — the confirmation also arrives as a
 * haptic tick at the exact moment the state flips.
 */
@SuppressLint("ClickableViewAccessibility")
class HoloButtonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var label: String = ""
        set(value) { field = value; invalidate() }
    var glyph: String? = null
        set(value) { field = value; invalidate() }
    var accent: Int = Holo.CYAN
        set(value) { field = value; invalidate() }
    /** Latched state — a recording button that is recording, a live mic. */
    var engaged: Boolean = false
        set(value) { if (field != value) { field = value; charge.set(0f); charge.to(1f); invalidate() } }
    var danger: Boolean = false

    var onActivate: (() -> Unit)? = null
    var onTouchPoint: ((Float, Float) -> Unit)? = null

    private val press = Spring(0f, 460f, 26f)
    private val charge = Spring(1f, 90f, 14f)
    private var detach: (() -> Unit)? = null
    private var downX = 0f
    private var downY = 0f

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        letterSpacing = 0.18f
        textAlign = Paint.Align.CENTER
    }
    private val outline = Path()
    private val cut = context.dp(10f)

    init {
        isClickable = true
        text.textSize = context.sp(12f)
        // Comfortably above the 48dp touch-target floor: this client is
        // driven by thumbs, often one-handed, often outdoors.
        minimumHeight = context.dp(54f).toInt()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        detach = HoloTicker.add { dt, _ ->
            press.update(dt); charge.update(dt)
            if (!press.settled || !charge.settled || engaged) invalidate()
        }
    }

    override fun onDetachedFromWindow() { detach?.invoke(); detach = null; super.onDetachedFromWindow() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = when (MeasureSpec.getMode(heightMeasureSpec)) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(heightMeasureSpec)
            else -> context.dp(54f).toInt()
        }
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), h)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y
                press.to(1f)
                Haptics.tick(this)
                onTouchPoint?.invoke(event.rawX, event.rawY)
                return true
            }
            MotionEvent.ACTION_UP -> {
                press.to(0f); press.kick(6f)
                if (event.x in 0f..width.toFloat() && event.y in 0f..height.toFloat()) onActivate?.invoke()
                return true
            }
            MotionEvent.ACTION_CANCEL -> { press.to(0f); return true }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val p = press.value
        val t = HoloTicker.time
        val tone = if (danger) Holo.RED else accent

        canvas.save()
        val scale = 1f - p * 0.035f
        canvas.translate(w * (1f - scale) / 2f, h * (1f - scale) / 2f)
        canvas.scale(scale, scale)

        val inset = context.dp(1f)
        outline.reset()
        outline.moveTo(inset, inset + cut)
        outline.lineTo(inset + cut, inset)
        outline.lineTo(w - inset, inset)
        outline.lineTo(w - inset, h - inset - cut)
        outline.lineTo(w - inset - cut, h - inset)
        outline.lineTo(inset, h - inset)
        outline.close()

        canvas.save()
        canvas.clipPath(outline)
        fill.shader = LinearGradient(
            0f, 0f, 0f, h,
            Holo.alpha(tone, if (engaged) 0.30f else 0.10f + 0.10f * p),
            Holo.alpha(0xFF04121C.toInt(), 0.55f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, fill)
        fill.shader = null

        if (p > 0.01f) {
            fill.shader = RadialGradient(
                downX, downY, max(w, h) * 0.7f,
                Holo.alpha(tone, 0.34f * p), Holo.alpha(tone, 0f), Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w, h, fill)
            fill.shader = null
        }

        // Charge sweep on every state change, so a latch is unmissable even
        // with a thumb covering half the control.
        val c = charge.value.coerceIn(0f, 1f)
        if (c < 0.999f) {
            val x = w * c
            fill.shader = LinearGradient(
                x - w * 0.35f, 0f, x, 0f,
                Holo.alpha(tone, 0f), Holo.alpha(tone, 0.55f), Shader.TileMode.CLAMP
            )
            canvas.drawRect(x - w * 0.35f, 0f, x, h, fill)
            fill.shader = null
        }
        canvas.restore()

        stroke.strokeWidth = context.dp(1.2f)
        stroke.color = Holo.alpha(tone, if (engaged) 0.95f else 0.45f + 0.35f * p)
        canvas.drawPath(outline, stroke)

        if (engaged) {
            // A live control keeps a pulse running along its top edge.
            val pulse = 0.5f + 0.5f * kotlin.math.sin(t * 5f)
            stroke.color = Holo.alpha(tone, 0.25f + 0.45f * pulse)
            stroke.strokeWidth = context.dp(2.6f)
            canvas.drawLine(inset + cut, inset, w - inset, inset, stroke)
        }

        text.color = Holo.alpha(if (engaged) Holo.INK else Holo.blend(Holo.INK, tone, 0.35f), 0.95f)
        val body = glyph?.let { "$it  $label" } ?: label
        canvas.drawText(body, w / 2f, h / 2f + context.dp(4.5f), text)

        canvas.restore()
    }
}
