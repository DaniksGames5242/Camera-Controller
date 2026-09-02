package com.mycamerascontroller.client.holo

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout

/**
 * Transient notices, stacked from the top.
 *
 * Each one is a plate that skews in, decodes its text and leaves; they never
 * take touch, because on a phone anything that can be tapped by accident
 * while reaching for something else eventually will be.
 */
class HoloToastHost @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    enum class Tone { INFO, OK, WARN, ERROR }

    private val column = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.END
    }

    init {
        isClickable = false
        isFocusable = false
        addView(
            column,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
                setMargins(context.dp(16f).toInt(), context.dp(16f).toInt(), context.dp(16f).toInt(), 0)
            }
        )
    }

    override fun onInterceptTouchEvent(ev: android.view.MotionEvent) = false
    override fun onTouchEvent(event: android.view.MotionEvent) = false

    fun push(text: String, tone: Tone = Tone.INFO, ttlMs: Long = 3200) {
        val toast = ToastView(context, tone)
        column.addView(toast, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, 0, context.dp(8f).toInt()) })
        toast.show(text)
        postDelayed({ toast.dismiss { column.removeView(toast) } }, ttlMs)
    }

    private class ToastView(context: Context, private val tone: Tone) : View(context) {

        private val enter = Spring(0f, 200f, 21f)
        private val scramble = Scramble(1.9f)
        private var body = ""
        private var detach: (() -> Unit)? = null

        private val plate = Paint(Paint.ANTI_ALIAS_FLAG)
        private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.MONOSPACE
            letterSpacing = 0.08f
        }
        private val outline = Path()
        private val cut = context.dp(10f)

        private val accent = when (tone) {
            Tone.OK -> Holo.MINT
            Tone.WARN -> Holo.AMBER
            Tone.ERROR -> Holo.RED
            else -> Holo.CYAN
        }

        init { text.textSize = context.sp(11f) }

        fun show(value: String) {
            enter.set(0f); enter.to(1f)
            scramble.set(value) { body = it; requestLayout(); invalidate() }
        }

        fun dismiss(onDone: () -> Unit) {
            enter.to(0f)
            postDelayed(onDone, 320)
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            detach = HoloTicker.add { dt, _ -> enter.update(dt); invalidate() }
        }

        override fun onDetachedFromWindow() {
            detach?.invoke(); detach = null
            scramble.dispose()
            super.onDetachedFromWindow()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = (text.measureText(body) + context.dp(38f)).toInt()
                .coerceIn(context.dp(140f).toInt(), MeasureSpec.getSize(widthMeasureSpec))
            setMeasuredDimension(w, context.dp(42f).toInt())
        }

        override fun onDraw(canvas: Canvas) {
            val e = enter.value.coerceIn(0f, 1f)
            if (e <= 0.002f) return
            val w = width.toFloat()
            val h = height.toFloat()

            canvas.save()
            // Skewed entrance: the plate slides in off-axis and squares up.
            canvas.translate((1f - e) * context.dp(34f), 0f)
            canvas.skew(-(1f - e) * 0.12f, 0f)

            outline.reset()
            outline.moveTo(0f, 0f)
            outline.lineTo(w, 0f)
            outline.lineTo(w, h - cut)
            outline.lineTo(w - cut, h)
            outline.lineTo(0f, h)
            outline.close()

            plate.shader = LinearGradient(
                0f, 0f, w, 0f,
                Holo.alpha(0xFF06111A.toInt(), 0.94f * e), Holo.alpha(0xFF040B12.toInt(), 0.88f * e),
                Shader.TileMode.CLAMP
            )
            canvas.drawPath(outline, plate)
            plate.shader = null

            bar.color = Holo.alpha(accent, 0.95f * e)
            canvas.drawRect(0f, 0f, context.dp(3f), h, bar)

            text.color = Holo.alpha(Holo.INK, e)
            canvas.drawText(body, context.dp(14f), h / 2f + context.dp(4f), text)
            canvas.restore()
        }
    }
}
