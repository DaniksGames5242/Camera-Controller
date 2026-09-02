package com.mycamerascontroller.client.holo

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * A value you scrub rather than type.
 *
 * Typing a resolution on a phone means summoning a keyboard over the very
 * thing being configured. Dragging a ruler does not: the thumb is already on
 * the screen, the ticks pass under it with a haptic click each, and the value
 * lands on a step by itself. Free-flinging with friction and settling onto the
 * nearest step is the whole interaction — there is no commit button on it.
 */
@SuppressLint("ClickableViewAccessibility")
class HoloDialView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var label: String = ""
    var unit: String = ""
    var accent: Int = Holo.CYAN
    var min: Int = 0
    var max: Int = 4096
    var step: Int = 16
    /** Shown instead of the number when the value is zero: "agent default". */
    var zeroLabel: String = "АВТО"

    var value: Int = 0
        set(v) {
            val clamped = v.coerceIn(min, max)
            field = clamped
            scrub = clamped.toFloat()
            invalidate()
        }

    var onValueChanged: ((Int) -> Unit)? = null

    /** Continuous position; `value` is this snapped to a step. */
    private var scrub = 0f
    private var velocity = 0f
    private var dragging = false
    private var lastX = 0f
    private var lastTickValue = 0
    private var tracker: VelocityTracker? = null
    private var detach: (() -> Unit)? = null

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        letterSpacing = 0.24f
        color = Holo.alpha(Holo.INK_DIM, 0.85f)
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        letterSpacing = 0.2f
        color = Holo.alpha(Holo.INK_DIM, 0.7f)
    }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wash = Paint(Paint.ANTI_ALIAS_FLAG)

    /** Screen pixels per unit of value. */
    private val pxPerUnit get() = context.dp(0.55f)

    init {
        labelPaint.textSize = context.sp(10f)
        valuePaint.textSize = context.sp(30f)
        unitPaint.textSize = context.sp(12f)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        detach = HoloTicker.add { dt, _ -> tickPhysics(dt) }
    }

    override fun onDetachedFromWindow() { detach?.invoke(); detach = null; super.onDetachedFromWindow() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), context.dp(104f).toInt())
    }

    private fun tickPhysics(dt: Float) {
        if (!dragging) {
            if (abs(velocity) > 1f) {
                scrub += velocity * dt
                velocity *= exp(-4.5f * dt)
                if (scrub < min) { scrub = min.toFloat(); velocity = 0f }
                if (scrub > max) { scrub = max.toFloat(); velocity = 0f }
            } else if (abs(velocity) > 0f) {
                velocity = 0f
            } else {
                // Settle onto the nearest step.
                val snapped = (scrub / step).roundToInt() * step.toFloat()
                if (abs(snapped - scrub) > 0.01f) {
                    scrub += (snapped - scrub) * (1f - exp(-16f * dt))
                }
            }
        }
        val snappedValue = ((scrub / step).roundToInt() * step).coerceIn(min, max)
        if (snappedValue != lastTickValue) {
            lastTickValue = snappedValue
            // One click per step crossed — the ruler has detents.
            Haptics.dragTick(this, 0.35f)
        }
        if (snappedValue != value) {
            value = snappedValue
            onValueChanged?.invoke(snappedValue)
        }
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                dragging = true
                velocity = 0f
                lastX = event.x
                tracker = VelocityTracker.obtain().also { it.addMovement(event) }
                Haptics.tick(this)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(event)
                val dx = event.x - lastX
                lastX = event.x
                // Drag left to increase, like a physical wheel rolling away.
                scrub = (scrub - dx / pxPerUnit).coerceIn(min.toFloat(), max.toFloat())
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                dragging = false
                tracker?.let {
                    it.addMovement(event)
                    it.computeCurrentVelocity(1000)
                    velocity = -it.xVelocity / pxPerUnit
                    it.recycle()
                }
                tracker = null
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val centerX = w / 2f
        val rulerY = h - context.dp(20f)

        canvas.drawText(label, 0f, context.dp(13f), labelPaint)

        // Ruler: ticks laid out around the current scrub position, taller
        // every fifth step, faded toward the edges.
        val visibleUnits = (w / pxPerUnit)
        val first = ((scrub - visibleUnits / 2f) / step).toInt() * step
        var v = first
        while (v <= scrub + visibleUnits / 2f) {
            if (v >= min && v <= max) {
                val x = centerX + (v - scrub) * pxPerUnit
                val major = (v / step) % 5 == 0
                val distance = abs(x - centerX) / (w / 2f)
                val a = (1f - distance).coerceIn(0f, 1f)
                tick.color = Holo.alpha(if (major) accent else Holo.INK_DIM, (if (major) 0.85f else 0.35f) * a)
                val len = if (major) context.dp(15f) else context.dp(8f)
                canvas.drawRect(x - context.dp(0.7f), rulerY - len, x + context.dp(0.7f), rulerY, tick)
            }
            v += step
        }

        // Centre indicator.
        tick.color = Holo.alpha(accent, 0.95f)
        canvas.drawRect(centerX - context.dp(1.2f), rulerY - context.dp(24f), centerX + context.dp(1.2f), rulerY + context.dp(4f), tick)
        wash.shader = LinearGradient(
            centerX, rulerY - context.dp(30f), centerX, rulerY,
            Holo.alpha(accent, 0f), Holo.alpha(accent, 0.28f), Shader.TileMode.CLAMP
        )
        canvas.drawRect(centerX - context.dp(28f), rulerY - context.dp(30f), centerX + context.dp(28f), rulerY, wash)
        wash.shader = null

        // Read-out.
        val shown = if (value <= 0) zeroLabel else value.toString()
        valuePaint.color = Holo.alpha(Holo.MAGENTA, 0.32f)
        canvas.drawText(shown, context.dp(1.2f), context.dp(48f), valuePaint)
        valuePaint.color = Holo.alpha(Holo.CYAN, 0.32f)
        canvas.drawText(shown, -context.dp(1.2f), context.dp(48f), valuePaint)
        valuePaint.color = Holo.INK
        canvas.drawText(shown, 0f, context.dp(48f), valuePaint)

        if (value > 0 && unit.isNotEmpty()) {
            canvas.drawText(unit, valuePaint.measureText(shown) + context.dp(8f), context.dp(48f), unitPaint)
        }
    }
}
