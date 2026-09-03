package com.mycamerascontroller.client.holo

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

/**
 * A camera in the list, drawn as a plate of light rather than assembled from
 * a background drawable and a row of child views.
 *
 * Everything here is procedural — the bevelled outline, the circulating edge
 * light, the status halo, the twelve-bar signal walk, the glyph ghosting — so
 * the card can respond continuously to touch pressure and to how much the
 * user is engaging with it, which a nine-patch cannot.
 *
 * Touch is the whole interaction model on this client: there is no hover to
 * telegraph what a press will do, so the card answers immediately in depth,
 * light and haptics the instant a finger lands.
 */
@SuppressLint("ClickableViewAccessibility")
class HoloNodeView(context: Context) : View(context) {

    var title: String = ""
        set(value) { field = value; scramble.set(value) { drawnTitle = it; invalidate() } }
    var online: Boolean = false
        set(value) { if (field != value) { field = value; invalidate() } }
    var channelOpen: Boolean = false
        set(value) { if (field != value) { field = value; invalidate() } }
    var focused: Boolean = false
        set(value) { if (field != value) { field = value; invalidate() } }
    var tint: Int = Holo.CYAN

    var onActivate: (() -> Unit)? = null
    var onLongPressAt: ((Float, Float) -> Unit)? = null
    var onSettings: (() -> Unit)? = null
    /** Reported so the stage behind can be stirred at the exact touch point. */
    var onTouchPoint: ((Float, Float) -> Unit)? = null

    private val scramble = Scramble(1.4f)
    private var drawnTitle = ""

    private val press = Spring(0f, 420f, 26f)
    private val lift = Spring(0f, 210f, 20f)
    private val enter = Spring(0f, 120f, 17f)
    private val meterSeed = (Math.random() * 1000).toFloat()

    private var detach: (() -> Unit)? = null
    private var downX = 0f
    private var downY = 0f
    /** Screen-absolute counterparts of downX/downY, for the radial menu —
        which is a full-window overlay and needs the finger's real position,
        not this card's local coordinate space. */
    private var downRawX = 0f
    private var downRawY = 0f
    private var longPressFired = false
    private val longPressRunnable = Runnable {
        longPressFired = true
        Haptics.longPress(this)
        onLongPressAt?.invoke(downRawX, downRawY)
    }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        letterSpacing = 0.18f
    }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val outline = Path()
    private val bounds = RectF()

    private val cut = context.dp(13f)
    private val padH = context.dp(16f)
    private val gearSize = context.dp(44f)

    // Every card redraws at 60fps by design (see onAttachedToWindow), so any
    // shader rebuilt unconditionally in onDraw becomes a per-frame, per-visible-row
    // allocation — with several cards on screen that's enough garbage to stall
    // the GC and stutter the whole list. These are built once and repositioned
    // with a reused Matrix, or left in place and dimmed via Paint.alpha (which
    // multiplies a shader's own alpha), instead of being recreated every frame.
    private var bodyShader: LinearGradient? = null
    private var bodyShaderW = -1
    private var bodyShaderH = -1
    private var washShader: RadialGradient? = null
    private var washShaderColor = 0
    private var washShaderW = -1
    private var washShaderH = -1
    private var scanShader: LinearGradient? = null
    private var scanShaderTint = 0
    private val scanMatrix = Matrix()
    private var edgeShader: SweepGradient? = null
    private var edgeShaderTint = 0
    private var edgeShaderL = -1f
    private var edgeShaderAlpha = -1f
    private val edgeMatrix = Matrix()
    private var meterShader: LinearGradient? = null
    private var meterShaderColor = 0
    private val meterMatrix = Matrix()

    init {
        isClickable = true
        textPaint.textSize = context.sp(16f)
        textPaint.letterSpacing = 0.02f
        subPaint.textSize = context.sp(10f)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        enter.set(0f)
        enter.to(1f)
        detach = HoloTicker.add { dt, _ ->
            press.update(dt); lift.update(dt); enter.update(dt)
            // Always redraw: the meter and the edge light are live even at
            // rest, and a card that freezes when idle stops reading as a feed.
            invalidate()
        }
    }

    override fun onDetachedFromWindow() {
        detach?.invoke(); detach = null
        scramble.dispose()
        removeCallbacks(longPressRunnable)
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec),
            context.dp(78f).toInt()
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y
                downRawX = event.rawX; downRawY = event.rawY
                longPressFired = false
                press.to(1f)
                lift.to(1f)
                Haptics.tick(this)
                onTouchPoint?.invoke(event.rawX, event.rawY)
                postDelayed(longPressRunnable, 420)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(event.x - downX) > cut || abs(event.y - downY) > cut) {
                    removeCallbacks(longPressRunnable)
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPressRunnable)
                press.to(0f)
                lift.to(0f)
                lift.kick(7f)
                if (!longPressFired && event.x >= width - gearSize - padH) onSettings?.invoke()
                else if (!longPressFired) onActivate?.invoke()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
                press.to(0f); lift.to(0f)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /** Shakes the card off — used when a node is tapped while offline. */
    fun reject() {
        Haptics.reject(this)
        lift.kick(-26f)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val e = enter.value.coerceIn(0f, 1f)
        val p = press.value
        val l = lift.value
        val t = HoloTicker.time

        // Entrance and press both act in depth, not in opacity alone.
        val scale = (0.94f + 0.06f * e) * (1f - p * 0.022f)
        canvas.save()
        canvas.translate(w * (1f - scale) / 2f, h * (1f - scale) / 2f + (1f - e) * context.dp(14f))
        canvas.scale(scale, scale)
        val alpha = e

        val inset = context.dp(1.5f)
        bounds.set(inset, inset, w - inset, h - inset)
        outline.reset()
        // Bevelled corners on the diagonal — the shape reads as machined,
        // and it gives the circulating edge light somewhere to catch.
        outline.moveTo(bounds.left, bounds.top + cut)
        outline.lineTo(bounds.left + cut, bounds.top)
        outline.lineTo(bounds.right, bounds.top)
        outline.lineTo(bounds.right, bounds.bottom - cut)
        outline.lineTo(bounds.right - cut, bounds.bottom)
        outline.lineTo(bounds.left, bounds.bottom)
        outline.close()

        canvas.save()
        canvas.clipPath(outline)

        // Body: a sheet of glass with the room glowing through it.
        if (bodyShader == null || bodyShaderW != w.toInt() || bodyShaderH != h.toInt()) {
            bodyShader = LinearGradient(
                0f, 0f, w, h,
                Holo.alpha(0xFF0C1E2C.toInt(), 0.62f),
                Holo.alpha(0xFF060E16.toInt(), 0.42f),
                Shader.TileMode.CLAMP
            )
            bodyShaderW = w.toInt(); bodyShaderH = h.toInt()
        }
        fill.shader = bodyShader
        fill.alpha = (alpha.coerceIn(0f, 1f) * 255).toInt()
        canvas.drawRect(0f, 0f, w, h, fill)
        fill.shader = null
        fill.alpha = 255

        // Pressure pools light where the finger is.
        if (p > 0.01f) {
            glow.shader = RadialGradient(
                downX, downY, max(w, h) * 0.55f,
                Holo.alpha(tint, 0.22f * p * alpha), Holo.alpha(tint, 0f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w, h, glow)
        }

        // Status wash from the left edge.
        val statusColor = when {
            channelOpen -> Holo.MAGENTA
            online -> Holo.MINT
            else -> Holo.INK_DIM
        }
        if (washShader == null || washShaderColor != statusColor || washShaderW != w.toInt() || washShaderH != h.toInt()) {
            washShader = RadialGradient(
                0f, h / 2f, w * 0.7f,
                Holo.alpha(statusColor, 1f), Holo.alpha(statusColor, 0f),
                Shader.TileMode.CLAMP
            )
            washShaderColor = statusColor; washShaderW = w.toInt(); washShaderH = h.toInt()
        }
        glow.shader = washShader
        glow.alpha = (((0.16f + 0.14f * l) * alpha).coerceIn(0f, 1f) * 255).toInt()
        canvas.drawRect(0f, 0f, w, h, glow)
        glow.shader = null
        glow.alpha = 255

        // A scan line crossing the card, phase-offset per card by its seed.
        val scanY = ((t * 0.32f + meterSeed * 0.017f) % 1f) * h
        val scanHalf = context.dp(10f)
        if (scanShader == null || scanShaderTint != tint) {
            scanShader = LinearGradient(
                0f, -scanHalf, 0f, scanHalf,
                intArrayOf(Holo.alpha(tint, 0f), Holo.alpha(tint, 1f), Holo.alpha(tint, 0f)),
                null, Shader.TileMode.CLAMP
            )
            scanShaderTint = tint
        }
        scanMatrix.reset()
        scanMatrix.postTranslate(0f, scanY)
        scanShader!!.setLocalMatrix(scanMatrix)
        fill.shader = scanShader
        fill.alpha = ((0.20f * alpha).coerceIn(0f, 1f) * 255).toInt()
        canvas.drawRect(0f, scanY - scanHalf, w, scanY + scanHalf, fill)
        fill.shader = null
        fill.alpha = 255
        canvas.restore()

        // Circulating edge light. Rebuilt only when its colours actually moved
        // (tint change, or the press/lift springs still settling) — at rest the
        // shader is untouched and only its rotation matrix is updated.
        stroke.strokeWidth = context.dp(1.2f)
        if (edgeShader == null || edgeShaderTint != tint ||
            abs(edgeShaderL - l) > 0.004f || abs(edgeShaderAlpha - alpha) > 0.004f
        ) {
            edgeShader = SweepGradient(
                w / 2f, h / 2f,
                intArrayOf(
                    Holo.alpha(tint, 0f),
                    Holo.alpha(tint, (0.15f + 0.75f * l) * alpha),
                    Holo.alpha(Holo.VIOLET, (0.10f + 0.4f * l) * alpha),
                    Holo.alpha(tint, 0f),
                    Holo.alpha(tint, 0f),
                ),
                floatArrayOf(0f, 0.10f, 0.22f, 0.42f, 1f)
            )
            edgeShaderTint = tint; edgeShaderL = l; edgeShaderAlpha = alpha
        }
        edgeMatrix.reset()
        edgeMatrix.setRotate((t * 62f) % 360f, w / 2f, h / 2f)
        edgeShader!!.setLocalMatrix(edgeMatrix)
        stroke.shader = edgeShader
        canvas.drawPath(outline, stroke)
        stroke.shader = null

        // Resting border.
        stroke.color = Holo.alpha(if (channelOpen) Holo.MAGENTA else if (online) Holo.MINT else Holo.INK_DIM, 0.28f * alpha)
        stroke.strokeWidth = context.dp(1f)
        canvas.drawPath(outline, stroke)

        // ---- status dot with a breathing halo -------------------------------
        val dotX = padH + context.dp(6f)
        val dotY = h / 2f
        if (online) {
            val ping = ((t * 0.42f + meterSeed * 0.01f) % 1f)
            glow.shader = null
            glow.color = Holo.alpha(statusColor, (1f - ping) * 0.35f * alpha)
            canvas.drawCircle(dotX, dotY, context.dp(5f) + ping * context.dp(16f), glow)
        }
        glow.color = Holo.alpha(statusColor, alpha)
        canvas.drawCircle(dotX, dotY, context.dp(5f), glow)

        // ---- title, with a hologram's chromatic ghosting ---------------------
        val textX = dotX + context.dp(18f)
        val baseline = h / 2f - context.dp(2f)
        textPaint.color = Holo.alpha(Holo.MAGENTA, 0.35f * alpha)
        canvas.drawText(drawnTitle, textX + context.dp(1.2f), baseline, textPaint)
        textPaint.color = Holo.alpha(Holo.CYAN, 0.35f * alpha)
        canvas.drawText(drawnTitle, textX - context.dp(1.2f), baseline, textPaint)
        textPaint.color = Holo.alpha(Holo.INK, alpha)
        canvas.drawText(drawnTitle, textX, baseline, textPaint)

        subPaint.color = Holo.alpha(statusColor, 0.75f * alpha)
        val status = when {
            channelOpen -> "КАНАЛ ОТКРЫТ"
            online -> "В СЕТИ · ГОТОВ"
            else -> "НЕ В СЕТИ"
        }
        canvas.drawText(status, textX, baseline + context.dp(17f), subPaint)

        // ---- signal meter ----------------------------------------------------
        val meterRight = w - padH - gearSize - context.dp(10f)
        val barW = context.dp(2.5f)
        val barGap = context.dp(2.5f)
        val meterH = context.dp(24f)
        val amplitude = if (online) 1f else 0.07f
        if (meterShader == null || meterShaderColor != statusColor) {
            // Baked at the shape's own 0.15/0.95 ramp; each bar just rescales
            // this one shader onto its own rect via meterMatrix instead of
            // allocating a fresh gradient of its own every frame.
            meterShader = LinearGradient(
                0f, 1f, 0f, 0f,
                Holo.alpha(statusColor, 0.15f), Holo.alpha(statusColor, 0.95f),
                Shader.TileMode.CLAMP
            )
            meterShaderColor = statusColor
        }
        bar.shader = meterShader
        bar.alpha = (alpha.coerceIn(0f, 1f) * 255).toInt()
        for (i in 0 until 12) {
            val phase = t * 2.4f + i * 0.55f + meterSeed
            val level = ((sin(phase) * 0.5f + sin(phase * 1.87f + 1.3f) * 0.3f + sin(phase * 3.31f) * 0.2f) * 0.5f + 0.5f)
            val scaled = 0.12f + level * 0.88f * amplitude * (0.55f + 0.45f * l)
            val x = meterRight - (11 - i) * (barW + barGap)
            val barH = meterH * scaled
            meterMatrix.reset()
            meterMatrix.setScale(1f, barH)
            meterMatrix.postTranslate(x, dotY + meterH / 2f - barH)
            meterShader!!.setLocalMatrix(meterMatrix)
            canvas.drawRect(x, dotY + meterH / 2f - barH, x + barW, dotY + meterH / 2f, bar)
        }
        bar.shader = null
        bar.alpha = 255

        // ---- settings affordance --------------------------------------------
        val gearCx = w - padH - gearSize / 2f
        stroke.color = Holo.alpha(tint, 0.35f * alpha)
        stroke.strokeWidth = context.dp(1f)
        canvas.drawCircle(gearCx, dotY, gearSize / 2f - context.dp(4f), stroke)
        textPaint.color = Holo.alpha(tint, alpha)
        val glyph = "⚙"
        val gw = textPaint.measureText(glyph)
        canvas.save()
        canvas.rotate(t * 22f, gearCx, dotY)
        canvas.drawText(glyph, gearCx - gw / 2f, dotY + context.dp(6f), textPaint)
        canvas.restore()

        canvas.restore()
    }
}
