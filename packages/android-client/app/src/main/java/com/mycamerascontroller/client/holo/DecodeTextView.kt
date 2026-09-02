package com.mycamerascontroller.client.holo

import android.content.Context
import android.graphics.Canvas
import android.text.TextPaint
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView

/**
 * A label that decodes into place out of a scrambling cipher, with the
 * chromatic ghosting every other surface in this client has.
 *
 * The decode is not decoration: a status that resolves glyph by glyph is
 * impossible to miss out of the corner of an eye, which on a phone held at
 * arm's length matters more than it does on a monitor.
 */
class DecodeTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : AppCompatTextView(context, attrs) {

    private val scramble = Scramble(1.5f)
    private val ghostPaint = TextPaint()

    /** Chromatic fringing on the glyphs. Off for long or wrapped copy. */
    var ghost: Boolean = true

    fun setDecoded(value: String) = scramble.set(value) { text = it }

    fun setImmediate(value: String) = scramble.setInstant(value) { text = it }

    override fun onDraw(canvas: Canvas) {
        // Ghost passes are drawn with our own paint rather than by recolouring
        // and re-running super: TextView resets its paint colour inside
        // onDraw, and setTextColor from within a draw would invalidate every
        // frame forever.
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

    override fun onDetachedFromWindow() {
        scramble.dispose()
        super.onDetachedFromWindow()
    }
}
