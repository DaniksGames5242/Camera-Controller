package com.mycamerascontroller.client

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.mycamerascontroller.client.holo.DecodeTextView
import com.mycamerascontroller.client.holo.HoloButtonView
import com.mycamerascontroller.client.holo.HoloPlateLayout
import com.mycamerascontroller.client.holo.Holo
import com.mycamerascontroller.client.holo.dp

/**
 * A destructive action, put behind a second deliberate press on a plate that
 * states plainly what it does — the phone equivalent of the desktop
 * confirm(), but materialised rather than a system dialog, so it never
 * breaks the illusion the rest of the app maintains.
 */
class HoloConfirmSheet(
    context: Context,
    private val title: String,
    private val body: String,
    private val confirmLabel: String,
    private val onConfirm: () -> Unit,
) : Dialog(context, android.R.style.Theme_Translucent_NoTitleBar) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ctx = context

        val plate = HoloPlateLayout(ctx).apply {
            accent = Holo.RED
            setPadding(ctx.dp(24f).toInt(), ctx.dp(24f).toInt(), ctx.dp(24f).toInt(), ctx.dp(20f).toInt())
        }

        val column = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        column.addView(TextView(ctx).apply {
            text = ctx.getString(R.string.confirm_eyebrow)
            setTextColor(Holo.alpha(Holo.RED, 0.9f))
            textSize = 10f
            typeface = Typeface.MONOSPACE
            letterSpacing = 0.3f
        })
        column.addView(DecodeTextView(ctx).apply {
            setTextColor(Holo.INK)
            textSize = 20f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setPadding(0, ctx.dp(6f).toInt(), 0, ctx.dp(12f).toInt())
            setImmediate("")
            setDecoded(title)
        })
        column.addView(TextView(ctx).apply {
            text = body
            setTextColor(Holo.alpha(Holo.INK_DIM, 0.9f))
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setLineSpacing(ctx.dp(4f), 1f)
            setPadding(0, 0, 0, ctx.dp(20f).toInt())
        })

        val actions = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(HoloButtonView(ctx).apply {
            label = ctx.getString(R.string.cancel)
            accent = Holo.INK_DIM
            onActivate = { dismiss() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(android.view.View(ctx), LinearLayout.LayoutParams(ctx.dp(10f).toInt(), 1))
        actions.addView(HoloButtonView(ctx).apply {
            label = confirmLabel
            accent = Holo.RED
            danger = true
            onActivate = { onConfirm(); dismiss() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        column.addView(actions)

        plate.addView(column, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(ctx.dp(20f).toInt(), 0, ctx.dp(20f).toInt(), 0)
            setOnClickListener { dismiss() }
        }
        root.addView(plate, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setContentView(root)

        window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0x9A01050A.toInt()))
            addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        }
        plate.materialise()
    }
}
