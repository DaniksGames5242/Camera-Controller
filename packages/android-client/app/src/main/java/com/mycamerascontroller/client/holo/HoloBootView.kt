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
import kotlin.math.max
import kotlin.math.min

/**
 * Entry into the application.
 *
 * The projector ignites, the room resolves around a single point of light,
 * the readout reports what is actually being negotiated underneath, and the
 * whole thing lifts. It never gates anything: signalling connects in parallel
 * behind it and the sequence dismisses on its own clock whether or not that
 * finished.
 *
 * Timing runs on the wall clock rather than accumulated frame deltas, so a
 * cheap phone gets the same sequence as an expensive one — just with fewer
 * frames in it.
 */
class HoloBootView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private data class Line(val at: Float, val text: String, val tone: Int)

    private val lines = listOf(
        Line(0.55f, "ПРОЕКТОР · ЗАПУСК ЭМИТТЕРА", Holo.CYAN),
        Line(0.95f, "ОБЪЁМНАЯ СЕТКА · КАЛИБРОВКА", Holo.INK_DIM),
        Line(1.35f, "КАНАЛ СИГНАЛИНГА · FIREBASE RTDB", Holo.MINT),
        Line(1.75f, "ТРАНСПОРТ · WEBRTC / STUN + TURN", Holo.MINT),
        Line(2.15f, "СКАНИРОВАНИЕ УЗЛОВ СЕТИ…", Holo.CYAN),
    )

    /** Receives the 0..1 power-on ramp; wired to the stage renderer. */
    var onProgress: ((Float) -> Unit)? = null
    var onFinished: (() -> Unit)? = null

    private var startedAt = 0L
    private var finished = false
    private var detach: (() -> Unit)? = null
    private var fade = 1f

    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        letterSpacing = 0.22f
    }
    private val marker = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE }
    private val wash = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        text.textSize = context.sp(10f)
        marker.textSize = context.sp(10f)
        isClickable = false
    }

    fun begin() {
        startedAt = System.nanoTime()
        finished = false
        fade = 1f
        visibility = VISIBLE
        detach = HoloTicker.add { dt, _ -> tick(dt) }
    }

    /** Cuts the sequence short — any touch anywhere does this. */
    fun skip() {
        if (finished) return
        finished = true
        onProgress?.invoke(1f)
    }

    private fun tick(dt: Float) {
        val t = (System.nanoTime() - startedAt) / 1e9f
        if (!finished) {
            val progress = min(1f, smoothstep(0.05f, 1.2f, t) * 0.6f + smoothstep(1.6f, 2.6f, t) * 0.4f)
            onProgress?.invoke(progress)
            if (t > 2.9f) finished = true
        }
        if (finished) {
            fade = max(0f, fade - dt * 2.4f)
            if (fade <= 0.001f) {
                visibility = GONE
                detach?.invoke(); detach = null
                onFinished?.invoke()
                return
            }
        }
        invalidate()
    }

    private fun smoothstep(a: Float, b: Float, x: Float): Float {
        val k = ((x - a) / (b - a)).coerceIn(0f, 1f)
        return k * k * (3 - 2 * k)
    }

    override fun onDraw(canvas: Canvas) {
        if (visibility != VISIBLE) return
        val t = (System.nanoTime() - startedAt) / 1e9f
        val w = width.toFloat()
        val h = height.toFloat()

        // Everything outside the opening iris stays dark, so the room appears
        // to be lit into existence from the emitter rather than faded up.
        val iris = smoothstep(0.0f, 1.6f, t)
        wash.shader = RadialGradient(
            w / 2f, h * 0.52f, max(w, h) * (0.12f + iris * 1.1f),
            Holo.alpha(0xFF01050A.toInt(), 0f), Holo.alpha(0xFF01050A.toInt(), fade),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, wash)
        wash.shader = null

        // Ignition flash at the emitter.
        val flash = (1f - smoothstep(0f, 0.8f, t)) * fade
        if (flash > 0.001f) {
            wash.shader = RadialGradient(
                w / 2f, h * 0.52f, w * 0.5f,
                Holo.alpha(Holo.CYAN, 0.85f * flash), Holo.alpha(Holo.CYAN, 0f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w, h, wash)
            wash.shader = null
        }

        var y = h - context.dp(96f)
        for (line in lines.reversed()) {
            if (t < line.at) continue
            val age = ((t - line.at) / 0.4f).coerceIn(0f, 1f)
            val alpha = age * fade
            val slide = (1f - age) * context.dp(16f)
            marker.color = Holo.alpha(Holo.MAGENTA, alpha)
            canvas.drawText("▍", context.dp(18f) - slide, y, marker)
            text.color = Holo.alpha(line.tone, alpha * 0.95f)
            canvas.drawText(line.text, context.dp(32f) - slide, y, text)
            y -= context.dp(18f)
        }

        if (t > 0.35f) {
            text.color = Holo.alpha(Holo.INK_DIM, 0.45f * fade)
            canvas.drawText("КОСНИТЕСЬ, ЧТОБЫ ПРОПУСТИТЬ", context.dp(18f), h - context.dp(52f), text)
        }

        // A charge bar along the bottom edge tracking the ramp.
        val charge = min(1f, t / 2.9f)
        wash.shader = LinearGradient(
            0f, 0f, w * charge, 0f,
            Holo.alpha(Holo.CYAN, 0.9f * fade), Holo.alpha(Holo.VIOLET, 0.9f * fade),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, h - context.dp(2.5f), w * charge, h, wash)
        wash.shader = null
    }

    override fun onDetachedFromWindow() { detach?.invoke(); detach = null; super.onDetachedFromWindow() }
}
