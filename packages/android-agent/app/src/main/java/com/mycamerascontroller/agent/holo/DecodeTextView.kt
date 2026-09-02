package com.mycamerascontroller.agent.holo

import android.content.Context
import android.graphics.Canvas
import android.text.TextPaint
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView

/** A label that decodes out of a scrambling cipher with chromatic fringing —
    see the client app for the full rationale. */
class DecodeTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : AppCompatTextView(context, attrs) {

    private val scramble = Scramble(1.5f)
    private val ghostPaint = TextPaint()
    var ghost: Boolean = true

    fun setDecoded(value: String) = scramble.set(value) { text = it }
    fun setImmediate(value: String) = scramble.setInstant(value) { text = it }

    override fun onDraw(canvas: Canvas) {
        val l = layout
        if (ghost && l != null && lineCount == 1) {
            ghostPaint.set(paint)
            val body = text?.toString().orEmpty()
            val x = totalPaddingLeft + l.getLineLeft(0)
            val y = (totalPaddingTop + l.getLineBaseline(0)).toFloat()
            val offset = context.dp(1.2f)
            ghostPaint.color = Holo.alpha(Holo.MAGENTA, 0.32f)
            canvas.drawText(body, x + offset, y, ghostPaint)
            ghostPaint.color = Holo.alpha(Holo.CYAN, 0.32f)
            canvas.drawText(body, x - offset, y, ghostPaint)
        }
        super.onDraw(canvas)
    }

    override fun onDetachedFromWindow() { scramble.dispose(); super.onDetachedFromWindow() }
}
