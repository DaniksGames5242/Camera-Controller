package com.mycamerascontroller.client

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.mycamerascontroller.client.holo.DecodeTextView
import com.mycamerascontroller.client.holo.HoloButtonView
import com.mycamerascontroller.client.holo.HoloDialView
import com.mycamerascontroller.client.holo.HoloPlateLayout
import com.mycamerascontroller.client.holo.HoloTicker
import com.mycamerascontroller.client.holo.Haptics
import com.mycamerascontroller.client.holo.Holo
import com.mycamerascontroller.client.holo.Spring
import com.mycamerascontroller.client.holo.dp
import com.mycamerascontroller.client.holo.sp
import kotlin.math.abs
import kotlin.math.max

/**
 * Capture settings, as a plate that rises out of the bottom of the room.
 *
 * Every value on it is scrubbed with a thumb rather than typed: no keyboard
 * ever covers the thing being configured, and the presets are one tap away
 * for the cases that cover almost everyone. Dragging the sheet down throws it
 * away, with the throw's own velocity deciding whether it commits — the
 * gesture vocabulary a phone already has, rather than a Cancel button
 * pretending to be one.
 */
@SuppressLint("ClickableViewAccessibility")
class HoloSettingsSheet(
    context: Context,
    private val deviceName: String,
    private val accent: Int,
    private val initial: DeviceSettings,
    private val onApply: (DeviceSettings) -> Unit,
) : Dialog(context, android.R.style.Theme_Translucent_NoTitleBar) {

    private lateinit var plate: HoloPlateLayout
    private val dismissDrag = Spring(0f, 260f, 24f)
    private var detach: (() -> Unit)? = null
    private var dragging = false
    private var dragStartY = 0f
    private var dragOffset = 0f

    private lateinit var widthDial: HoloDialView
    private lateinit var heightDial: HoloDialView
    private lateinit var fpsDial: HoloDialView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ctx = context

        plate = HoloPlateLayout(ctx).apply {
            accent = this@HoloSettingsSheet.accent
            setPadding(ctx.dp(22f).toInt(), ctx.dp(26f).toInt(), ctx.dp(22f).toInt(), ctx.dp(20f).toInt())
        }

        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }

        column.addView(eyebrow(ctx, "ПАРАМЕТРЫ ЗАХВАТА"))
        column.addView(DecodeTextView(ctx).apply {
            setTextColor(Holo.INK)
            textSize = 22f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            letterSpacing = 0.04f
            setPadding(0, ctx.dp(4f).toInt(), 0, ctx.dp(14f).toInt())
            setImmediate("")
            setDecoded(deviceName)
        })

        widthDial = dial(ctx, "ШИРИНА", "px", 0, 3840, 16, initial.width ?: 0)
        heightDial = dial(ctx, "ВЫСОТА", "px", 0, 2160, 16, initial.height ?: 0)
        fpsDial = dial(ctx, "ЧАСТОТА КАДРОВ", "fps", 0, 120, 1, initial.frameRate ?: 0)
        column.addView(widthDial)
        column.addView(heightDial)
        column.addView(fpsDial)

        column.addView(eyebrow(ctx, "ПРЕСЕТЫ").apply {
            setPadding(0, ctx.dp(10f).toInt(), 0, ctx.dp(8f).toInt())
        })
        column.addView(presetRow(ctx))

        column.addView(TextView(ctx).apply {
            text = context.getString(R.string.settings_hint)
            setTextColor(Holo.alpha(Holo.INK_DIM, 0.8f))
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setLineSpacing(ctx.dp(3f), 1f)
            setPadding(0, ctx.dp(14f).toInt(), 0, ctx.dp(16f).toInt())
        })

        val actions = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(HoloButtonView(ctx).apply {
            label = context.getString(R.string.cancel)
            this.accent = Holo.INK_DIM
            onActivate = { dismissWithMotion() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(View(ctx), LinearLayout.LayoutParams(ctx.dp(10f).toInt(), 1))
        actions.addView(HoloButtonView(ctx).apply {
            label = context.getString(R.string.save)
            this.accent = Holo.MINT
            engaged = true
            onActivate = {
                Haptics.materialise(this)
                onApply(
                    DeviceSettings(
                        width = widthDial.value.takeIf { it > 0 },
                        height = heightDial.value.takeIf { it > 0 },
                        frameRate = fpsDial.value.takeIf { it > 0 },
                    )
                )
                dismissWithMotion()
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f))
        column.addView(actions)

        plate.addView(column, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { dismissWithMotion() }
        }
        root.addView(plate, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setContentView(root)

        window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0x99010509.toInt()))
            setDimAmount(0f)
            addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        }

        // Drag-to-dismiss on the plate itself, with the release velocity
        // deciding the outcome.
        plate.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { dragging = true; dragStartY = event.rawY; true }
                MotionEvent.ACTION_MOVE -> {
                    dragOffset = max(0f, event.rawY - dragStartY)
                    dismissDrag.set(dragOffset)
                    v.translationY = dragOffset
                    // Resistance builds as the throw approaches its commit point.
                    if (dragOffset > v.height * 0.3f) Haptics.dragTick(v, 0.2f)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    if (dragOffset > v.height * 0.32f) dismissWithMotion()
                    else { dismissDrag.to(0f); dragOffset = 0f }
                    true
                }
                else -> false
            }
        }

        plate.materialise()
        detach = HoloTicker.add { dt, _ ->
            if (!dragging) plate.translationY = dismissDrag.update(dt)
        }
    }

    private fun eyebrow(ctx: Context, text: String) = TextView(ctx).apply {
        this.text = text
        setTextColor(Holo.alpha(accent, 0.9f))
        textSize = 10f
        typeface = Typeface.MONOSPACE
        letterSpacing = 0.3f
    }

    private fun dial(
        ctx: Context, label: String, unit: String, min: Int, max: Int, step: Int, value: Int,
    ) = HoloDialView(ctx).apply {
        this.label = label
        this.unit = unit
        this.min = min
        this.max = max
        this.step = step
        this.accent = this@HoloSettingsSheet.accent
        this.value = value
    }

    private fun presetRow(ctx: Context): View {
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val presets = listOf(
            Triple("ЭКОНОМ", 640 to 360, 15),
            Triple("БАЗА", 1280 to 720, 30),
            Triple("ЧЁТКО", 1600 to 900, 30),
            Triple("МАКС", 1920 to 1080, 30),
        )
        for ((i, preset) in presets.withIndex()) {
            if (i > 0) row.addView(View(ctx), LinearLayout.LayoutParams(ctx.dp(8f).toInt(), 1))
            row.addView(HoloButtonView(ctx).apply {
                label = preset.first
                accent = this@HoloSettingsSheet.accent
                onActivate = {
                    widthDial.value = preset.second.first
                    heightDial.value = preset.second.second
                    fpsDial.value = preset.third
                    Haptics.materialise(this)
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        return row
    }

    private fun dismissWithMotion() {
        plate.dematerialise { dismiss() }
    }

    override fun dismiss() {
        detach?.invoke(); detach = null
        super.dismiss()
    }
}
