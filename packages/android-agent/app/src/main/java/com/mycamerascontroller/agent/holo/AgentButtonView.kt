package com.mycamerascontroller.agent.holo

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

/** The same bevelled, light-pooling control every surface in this product
    uses — see the client app's HoloButtonView for the full rationale. */
@SuppressLint("ClickableViewAccessibility")
class AgentButtonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var label: String = ""
        set(value) { field = value; invalidate() }
    var accent: Int = Holo.CYAN
    var done: Boolean = false
        set(value) { if (field != value) { field = value; invalidate() } }
    var onActivate: (() -> Unit)? = null

    private val press = Spring(0f, 460f, 26f)
    private var detach: (() -> Unit)? = null
    private var downX = 0f
    private var downY = 0f

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        letterSpacing = 0.14f
        textAlign = Paint.Align.CENTER
    }
    private val outline = Path()
    private val cut get() = context.dp(10f)

    init {
        isClickable = true
        text.textSize = context.sp(13f)
        minimumHeight = context.dp(56f).toInt()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        detach = HoloTicker.add { dt, _ -> press.update(dt); invalidate() }
    }

    override fun onDetachedFromWindow() { detach?.invoke(); detach = null; super.onDetachedFromWindow() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = if (View.MeasureSpec.getMode(heightMeasureSpec) == View.MeasureSpec.EXACTLY)
            View.MeasureSpec.getSize(heightMeasureSpec) else context.dp(56f).toInt()
        setMeasuredDimension(View.MeasureSpec.getSize(widthMeasureSpec), h)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = event.x; downY = event.y; press.to(1f); return true }
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
        val tone = if (done) Holo.MINT else accent

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
            Holo.alpha(tone, if (done) 0.26f else 0.10f + 0.10f * p),
            Holo.alpha(0xFF04121C.toInt(), 0.6f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, fill)
        fill.shader = null
        if (p > 0.01f) {
            fill.shader = RadialGradient(downX, downY, max(w, h) * 0.7f, Holo.alpha(tone, 0.32f * p), Holo.alpha(tone, 0f), Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, w, h, fill)
            fill.shader = null
        }
        canvas.restore()

        stroke.strokeWidth = context.dp(1.2f)
        stroke.color = Holo.alpha(tone, if (done) 0.9f else 0.5f + 0.35f * p)
        canvas.drawPath(outline, stroke)

        text.color = Holo.alpha(if (done) Holo.INK else Holo.INK, 0.95f)
        val body = if (done) "✓  $label" else label
        canvas.drawText(body, w / 2f, h / 2f + context.dp(4.5f), text)
        canvas.restore()
    }
}
