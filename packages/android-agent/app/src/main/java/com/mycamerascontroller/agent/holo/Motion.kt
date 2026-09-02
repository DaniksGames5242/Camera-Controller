package com.mycamerascontroller.agent.holo

import android.view.Choreographer
import kotlin.math.ceil
import kotlin.math.exp

/** A critically-tunable spring — see the client app for the full rationale;
    this is the same primitive, kept local since this module doesn't share
    source with the client's Gradle module. */
class Spring(value: Float = 0f, var stiffness: Float = 170f, var damping: Float = 22f) {
    var value = value
    var velocity = 0f
    var target = value

    fun set(v: Float) { value = v; target = v; velocity = 0f }
    fun to(v: Float) { target = v }
    fun kick(v: Float) { velocity += v }

    fun update(dt: Float): Float {
        val steps = (ceil(dt / (1f / 120f)).toInt()).coerceIn(1, 6)
        val h = dt / steps
        repeat(steps) {
            val accel = (target - value) * stiffness - velocity * damping
            velocity += accel * h
            value += velocity * h
        }
        return value
    }
}

/** One Choreographer callback for the whole screen; stops itself once the
    last listener detaches rather than ticking forever in the background. */
object HoloTicker : Choreographer.FrameCallback {
    private val callbacks = LinkedHashSet<(Float, Float) -> Unit>()
    private val pending = ArrayList<(Float, Float) -> Unit>()
    private val removing = ArrayList<(Float, Float) -> Unit>()
    private var lastNanos = 0L
    private var running = false
    var time = 0f; private set

    fun add(cb: (Float, Float) -> Unit): () -> Unit {
        pending.add(cb)
        if (!running) { running = true; lastNanos = 0L; Choreographer.getInstance().postFrameCallback(this) }
        return { removing.add(cb) }
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        if (pending.isNotEmpty()) { callbacks.addAll(pending); pending.clear() }
        if (removing.isNotEmpty()) { callbacks.removeAll(removing.toSet()); removing.clear() }
        if (callbacks.isEmpty()) { running = false; lastNanos = 0L; return }
        Choreographer.getInstance().postFrameCallback(this)

        val dt = if (lastNanos == 0L) 1f / 60f else ((frameTimeNanos - lastNanos) / 1e9f).coerceIn(1f / 240f, 1f / 20f)
        lastNanos = frameTimeNanos
        time += dt
        for (cb in callbacks.toList()) cb(dt, time)
    }
}

/** Glyph-scramble decode, identical in spirit to the client's. */
class Scramble(private val speed: Float = 1f) {
    private val glyphs = "АБВГДЕЖЗИКЛМНОПРСТУФХЦЧШЩЭЮЯ0123456789#%&@*<>/\\|=+-"
    private var frame = 0f
    private var stop: (() -> Unit)? = null
    private var current = ""
    private var queue: List<Item> = emptyList()

    private class Item(val from: Char?, val to: Char?, val start: Int, val end: Int) { var glyph: Char? = null }

    fun setInstant(text: String, apply: (String) -> Unit) { stop?.invoke(); stop = null; current = text; apply(text) }

    fun set(text: String, apply: (String) -> Unit) {
        if (text == current) return
        val from = current
        current = text
        val length = maxOf(from.length, text.length)
        queue = (0 until length).map { i ->
            val start = (Math.random() * 10 / speed).toInt()
            Item(from.getOrNull(i), text.getOrNull(i), start, start + (Math.random() * 12 / speed).toInt() + 4)
        }
        frame = 0f
        stop?.invoke()
        stop = HoloTicker.add { dt, _ -> tick(dt, apply) }
    }

    private fun tick(dt: Float, apply: (String) -> Unit) {
        val sb = StringBuilder()
        var complete = 0
        for (item in queue) {
            when {
                frame >= item.end -> { complete++; item.to?.let { sb.append(it) } }
                frame >= item.start -> {
                    if (item.glyph == null || Math.random() < 0.3) item.glyph = glyphs.random()
                    sb.append(item.glyph)
                }
                else -> item.from?.let { sb.append(it) }
            }
        }
        apply(sb.toString())
        frame += dt * 60f
        if (complete == queue.size) { stop?.invoke(); stop = null; apply(current) }
    }

    fun dispose() { stop?.invoke(); stop = null }
}
