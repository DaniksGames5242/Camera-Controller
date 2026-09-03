package com.mycamerascontroller.client.holo

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.roundToInt

/**
 * The three numbers that say what the system is doing: nodes seen, nodes
 * online, channels open.
 *
 * Values are not assigned, they are *approached* — a spring per figure, so a
 * count that changes rolls to its new value and the eye catches the movement
 * without any need for a flash or a badge.
 */
class HoloStatsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private data class Stat(val key: String, val color: Int, val spring: Spring)

    private val stats = listOf(
        Stat("УЗЛЫ", Holo.INK, Spring(0f, 120f, 18f)),
        Stat("В СЕТИ", Holo.MINT, Spring(0f, 120f, 18f)),
        Stat("КАНАЛЫ", Holo.MAGENTA, Spring(0f, 120f, 18f)),
    )

    private var detach: (() -> Unit)? = null

    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        letterSpacing = 0.24f
        color = Holo.alpha(Holo.INK_DIM, 0.8f)
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // Rebuilt only when the cell size changes instead of 6 times every frame
    // (this view redraws continuously — see onAttachedToWindow).
    private var washShader: LinearGradient? = null
    private var washShaderCellW = -1
    private var washShaderH = -1
    private val barShaders = arrayOfNulls<LinearGradient>(3)
    private var barShaderCellW = -1
    private val barMatrix = Matrix()

    init {
        keyPaint.textSize = context.sp(9f)
        valuePaint.textSize = context.sp(22f)
    }

    fun set(nodes: Int, online: Int, channels: Int) {
        stats[0].spring.to(nodes.toFloat())
        stats[1].spring.to(online.toFloat())
        stats[2].spring.to(channels.toFloat())
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        detach = HoloTicker.add { dt, _ ->
            var moving = false
            for (s in stats) { s.spring.update(dt); if (!s.spring.settled) moving = true }
            // The top light bar is always drifting, so this repaints regardless;
            // the spring check just avoids doing arithmetic nobody sees.
            if (moving || true) invalidate()
        }
    }

    override fun onDetachedFromWindow() { detach?.invoke(); detach = null; super.onDetachedFromWindow() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), context.dp(66f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val gap = context.dp(8f)
        val cellW = (w - gap * 2) / 3f
        val t = HoloTicker.time

        for ((i, stat) in stats.withIndex()) {
            val left = i * (cellW + gap)
            canvas.save()
            canvas.translate(left, 0f)

            if (washShader == null || washShaderCellW != cellW.toInt() || washShaderH != h.toInt()) {
                washShader = LinearGradient(
                    0f, 0f, 0f, h,
                    Holo.alpha(0xFF0A1E2E.toInt(), 0.55f), Holo.alpha(0xFF050D16.toInt(), 0.35f),
                    Shader.TileMode.CLAMP
                )
                washShaderCellW = cellW.toInt(); washShaderH = h.toInt()
            }
            glowPaint.shader = washShader
            canvas.drawRect(0f, 0f, cellW, h, glowPaint)
            glowPaint.shader = null

            framePaint.strokeWidth = context.dp(1f)
            framePaint.color = Holo.alpha(Holo.CYAN, 0.16f)
            canvas.drawRect(0.5f, 0.5f, cellW - 0.5f, h - 0.5f, framePaint)

            // A light travelling along the top edge, offset per cell.
            val phase = ((t * 0.35f + i * 0.24f) % 1f)
            val barW = cellW * 0.45f
            if (barShaderCellW != cellW.toInt()) {
                for (bi in barShaders.indices) barShaders[bi] = null
                barShaderCellW = cellW.toInt()
            }
            var barShader = barShaders[i]
            if (barShader == null) {
                barShader = LinearGradient(
                    -barW, 0f, 0f, 0f,
                    Holo.alpha(stat.color, 0f), Holo.alpha(stat.color, 0.85f), Shader.TileMode.CLAMP
                )
                barShaders[i] = barShader
            }
            barMatrix.reset()
            barMatrix.postTranslate(cellW * phase, 0f)
            barShader.setLocalMatrix(barMatrix)
            glowPaint.shader = barShader
            canvas.drawRect(cellW * phase - barW, 0f, cellW * phase, context.dp(1.5f), glowPaint)
            glowPaint.shader = null

            canvas.drawText(stat.key, context.dp(9f), context.dp(18f), keyPaint)
            valuePaint.color = stat.color
            canvas.drawText(
                stat.spring.value.roundToInt().toString(),
                context.dp(9f), h - context.dp(14f), valuePaint
            )
            canvas.restore()
        }
    }
}
