package com.mycamerascontroller.client.holo

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Actions fanned out around the finger that summoned them.
 *
 * A long press on a phone leaves the thumb already at the point of interest,
 * and it is the one gesture where the finger's position is known precisely.
 * Putting the choices on an arc around it means every option is the same
 * short distance away and none of them are under the hand — neither of which
 * a dropped-down list manages. Selection follows the finger continuously, so
 * the whole interaction is one press-drag-release without a second tap.
 */
@SuppressLint("ClickableViewAccessibility")
class HoloRadialMenu @JvmOverloads constructor(
    context: Context,
    attrs: android.util.AttributeSet? = null,
) : View(context, attrs) {

    data class Item(val label: String, val glyph: String, val danger: Boolean = false, val run: () -> Unit)

    private var items: List<Item> = emptyList()
    private var originX = 0f
    private var originY = 0f
    private var selected = -1
    private val reveal = Spring(0f, 230f, 20f)
    private var detach: (() -> Unit)? = null
    private var accent = Holo.CYAN

    private val disc = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.CENTER
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        letterSpacing = 0.14f
        textAlign = Paint.Align.CENTER
    }
    private val hub = Path()

    private val radius get() = context.dp(104f)
    private val itemRadius get() = context.dp(30f)

    init {
        visibility = GONE
        glyphPaint.textSize = context.sp(19f)
        labelPaint.textSize = context.sp(9.5f)
    }

    fun show(x: Float, y: Float, accent: Int, items: List<Item>) {
        this.items = items
        this.accent = accent
        originX = x
        originY = y
        selected = -1
        visibility = VISIBLE
        reveal.set(0f)
        reveal.to(1f)
        if (detach == null) {
            detach = HoloTicker.add { dt, _ ->
                reveal.update(dt)
                if (reveal.value < 0.004f && reveal.target == 0f) visibility = GONE
                invalidate()
            }
        }
    }

    fun hide() { reveal.to(0f); selected = -1 }

    val isOpen: Boolean get() = visibility == VISIBLE && reveal.target > 0f

    /**
     * Fed the same gesture stream that opened the menu, so the finger never
     * has to lift and land again.
     */
    fun onGesture(event: MotionEvent): Boolean {
        if (!isOpen) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val next = hitTest(event.rawX, event.rawY)
                if (next != selected) {
                    selected = next
                    if (next >= 0) Haptics.tick(this)
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                val choice = items.getOrNull(hitTest(event.rawX, event.rawY))
                hide()
                if (choice != null) { Haptics.materialise(this); choice.run() }
                return true
            }
            MotionEvent.ACTION_CANCEL -> { hide(); return true }
        }
        return true
    }

    private fun hitTest(x: Float, y: Float): Int {
        if (items.isEmpty()) return -1
        val dx = x - originX
        val dy = y - originY
        val distance = hypot(dx, dy)
        // A dead zone at the centre so releasing where you pressed cancels.
        if (distance < radius * 0.42f) return -1
        val angle = atan2(dy.toDouble(), dx.toDouble())
        for (i in items.indices) {
            val itemAngle = angleFor(i)
            var delta = angle - itemAngle
            while (delta > PI) delta -= 2 * PI
            while (delta < -PI) delta += 2 * PI
            if (kotlin.math.abs(delta) < spread() / (2.0 * items.size) * 1.1) return i
        }
        return -1
    }

    /** Fan upward, and narrow the arc when there are few items. */
    private fun spread(): Double = minOf(PI * 1.4, 0.62 * items.size + 0.5)

    private fun angleFor(index: Int): Double {
        val s = spread()
        return -PI / 2 - s / 2 + s * (index + 0.5) / items.size
    }

    override fun onDraw(canvas: Canvas) {
        val r = reveal.value.coerceIn(0f, 1.15f)
        if (r <= 0.002f) return

        // Scrim: the room dims but stays visible through it.
        disc.shader = RadialGradient(
            originX, originY, radius * 3.2f,
            Holo.alpha(0xFF01060B.toInt(), 0.30f * r), Holo.alpha(0xFF01060B.toInt(), 0.86f * r),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), disc)
        disc.shader = null

        // Origin marker, where the finger landed.
        disc.color = Holo.alpha(accent, 0.9f * r)
        canvas.drawCircle(originX, originY, context.dp(5f) * r, disc)
        stroke.strokeWidth = context.dp(1f)
        stroke.color = Holo.alpha(accent, 0.35f * r)
        canvas.drawCircle(originX, originY, radius * 0.42f * r, stroke)

        for ((i, item) in items.withIndex()) {
            val angle = angleFor(i)
            // Items arrive in sequence rather than all at once.
            val local = ((r * 1.25f) - i * 0.06f).coerceIn(0f, 1f)
            val d = radius * local
            val cx = originX + (cos(angle) * d).toFloat()
            val cy = originY + (sin(angle) * d).toFloat()
            val on = i == selected
            val tone = if (item.danger) Holo.RED else accent

            // Connector, so the fan reads as one mechanism.
            stroke.strokeWidth = context.dp(1f)
            stroke.color = Holo.alpha(tone, (if (on) 0.7f else 0.22f) * local)
            canvas.drawLine(originX, originY, cx, cy, stroke)

            val rr = itemRadius * (if (on) 1.16f else 1f) * local
            disc.color = Holo.alpha(0xFF061420.toInt(), 0.95f * local)
            canvas.drawCircle(cx, cy, rr, disc)
            if (on) {
                disc.shader = RadialGradient(
                    cx, cy, rr * 1.9f, Holo.alpha(tone, 0.45f), Holo.alpha(tone, 0f), Shader.TileMode.CLAMP
                )
                canvas.drawCircle(cx, cy, rr * 1.9f, disc)
                disc.shader = null
            }
            stroke.strokeWidth = context.dp(if (on) 2f else 1.2f)
            stroke.color = Holo.alpha(tone, (if (on) 1f else 0.45f) * local)
            canvas.drawCircle(cx, cy, rr, stroke)

            glyphPaint.color = Holo.alpha(if (on) Holo.INK else tone, local)
            canvas.drawText(item.glyph, cx, cy + context.dp(7f), glyphPaint)

            labelPaint.color = Holo.alpha(if (on) Holo.INK else Holo.INK_DIM, local)
            canvas.drawText(item.label, cx, cy + rr + context.dp(16f), labelPaint)
        }
        hub.reset()
    }

    override fun onDetachedFromWindow() {
        detach?.invoke(); detach = null
        super.onDetachedFromWindow()
    }
}
