package com.mycamerascontroller.agent.holo

import android.content.Context
import android.graphics.Camera
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The agent's own small piece of the same room the client apps stand in.
 *
 * This screen exists for one purpose — grant two permissions — and is seen
 * once, briefly. A full GLES pipeline would be wrong for it: the value here
 * is in never looking like a bare permission dialog, not in matching the
 * client's shader complexity. A tilted procedural floor, drifting motes and
 * a status-driven glow do that with a single Canvas view.
 */
class AgentStageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** 0 = idle amber pulse, 1 = fully armed mint glow (essentials granted). */
    var armed: Boolean = false

    private var detach: (() -> Unit)? = null
    private val glow = Spring(0f)
    private val camera = Camera()
    private val matrix = Matrix()

    private val floorPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val wash = Paint(Paint.ANTI_ALIAS_FLAG)

    private data class Mote(var a: Float, var r: Float, var speed: Float, var y: Float)
    private val motes = List(46) {
        Mote(
            a = (Math.random() * PI * 2).toFloat(),
            r = 0.25f + Math.random().toFloat() * 0.7f,
            speed = 0.05f + Math.random().toFloat() * 0.10f,
            y = Math.random().toFloat(),
        )
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        detach = HoloTicker.add { dt, _ -> glow.to(if (armed) 1f else 0f); glow.update(dt); invalidate() }
    }

    override fun onDetachedFromWindow() { detach?.invoke(); detach = null; super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val t = HoloTicker.time
        val g = glow.value.coerceIn(0f, 1f)
        val accent = if (g > 0.5f) Holo.MINT else Holo.AMBER

        // Deep gradient ground.
        floorPaint.shader = LinearGradient(
            0f, 0f, 0f, h,
            Holo.VOID, Holo.alpha(0xFF040C16.toInt(), 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, floorPaint)
        floorPaint.shader = null

        // Perspective floor grid, receding toward the lower third.
        canvas.save()
        val floorTop = h * 0.62f
        canvas.clipRect(0f, floorTop, w, h)
        camera.save()
        camera.rotateX(68f)
        camera.getMatrix(matrix)
        camera.restore()
        matrix.preTranslate(-w / 2f, -h)
        matrix.postTranslate(w / 2f, h)
        canvas.concat(matrix)

        linePaint.strokeWidth = context.dp(1f)
        val spacing = context.dp(46f)
        val scroll = (t * 30f) % spacing
        var gx = -w
        while (gx < w * 2) {
            linePaint.color = Holo.alpha(accent, 0.18f)
            canvas.drawLine(gx, h - context.dp(600f), gx, h + context.dp(200f), linePaint)
            gx += spacing
        }
        var gy = h + context.dp(200f) - scroll
        while (gy > h - context.dp(600f)) {
            linePaint.color = Holo.alpha(accent, 0.14f)
            canvas.drawLine(-w, gy, w * 2, gy, linePaint)
            gy -= spacing
        }
        canvas.restore()

        // Emitter glow at the horizon, breathing with the armed state.
        wash.shader = RadialGradient(
            w / 2f, floorTop, w * 0.6f,
            Holo.alpha(accent, 0.16f + 0.10f * g), Holo.alpha(accent, 0f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, wash)
        wash.shader = null

        // Drifting motes, orbiting the emitter axis loosely.
        for (m in motes) {
            m.a += m.speed * (1f / 60f) * (0.6f + g * 0.8f)
            val rad = w * 0.28f * m.r
            val mx = w / 2f + cos(m.a) * rad
            val my = floorTop - h * 0.32f * m.y - sin(m.a * 1.7f) * h * 0.05f
            val size = context.dp(1.2f) + m.r * context.dp(1.6f)
            wash.color = Holo.alpha(if (m.r > 0.7f) Holo.CYAN else accent, 0.25f + 0.35f * (1f - m.y))
            canvas.drawCircle(mx, my, size, wash)
        }

        // Vignette.
        wash.shader = RadialGradient(
            w / 2f, h / 2f, w * 0.75f,
            Holo.alpha(0x00000000, 0f), Holo.alpha(0xFF000000.toInt(), 0.55f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, wash)
        wash.shader = null
    }
}
