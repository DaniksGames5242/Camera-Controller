package com.mycamerascontroller.client.holo

import android.view.Choreographer
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.min

/**
 * Procedural motion for Android views.
 *
 * The same rule as the desktop client: no interpolators, no fixed durations.
 * Every animated value is integrated from a physical model each frame, which
 * is what lets a press be interrupted by a fling, or a card be re-targeted
 * halfway through its own entrance, without a visible seam.
 */

class Spring(value: Float = 0f, var stiffness: Float = 170f, var damping: Float = 22f) {
    var value = value
    var velocity = 0f
    var target = value

    fun set(v: Float) { value = v; target = v; velocity = 0f }
    fun to(v: Float) { target = v }
    fun kick(v: Float) { velocity += v }

    fun update(dt: Float): Float {
        // Sub-stepped: a stiff spring and a dropped frame is otherwise all it
        // takes to launch a view off the screen.
        val steps = min(6, ceil(dt / (1f / 120f)).toInt().coerceAtLeast(1))
        val h = dt / steps
        repeat(steps) {
            val accel = (target - value) * stiffness - velocity * damping
            velocity += accel * h
            value += velocity * h
        }
        return value
    }

    val settled: Boolean get() = abs(value - target) < 0.0005f && abs(velocity) < 0.0005f
}

class Damper(value: Float = 0f, var rate: Float = 10f) {
    var value = value
    var target = value
    fun set(v: Float) { value = v; target = v }
    fun to(v: Float) { target = v }
    fun update(dt: Float): Float {
        value = target + (value - target) * exp(-rate * dt)
        return value
    }
}

/**
 * One Choreographer callback for the whole interface. A per-view animator
 * would mean dozens of independent callbacks fighting for the same frame;
 * this keeps ordering deterministic and makes adding an animation free.
 */
object HoloTicker : Choreographer.FrameCallback {
    private val callbacks = LinkedHashSet<(Float, Float) -> Unit>()
    private val pending = ArrayList<(Float, Float) -> Unit>()
    private val removing = ArrayList<(Float, Float) -> Unit>()
    private var lastNanos = 0L
    private var running = false
    var time = 0f; private set

    fun add(cb: (Float, Float) -> Unit): () -> Unit {
        pending.add(cb)
        start()
        return { removing.add(cb) }
    }

    private fun start() {
        if (running) return
        running = true
        lastNanos = 0L
        Choreographer.getInstance().postFrameCallback(this)
    }

    fun stop() {
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return

        if (pending.isNotEmpty()) { callbacks.addAll(pending); pending.clear() }
        if (removing.isNotEmpty()) { callbacks.removeAll(removing.toSet()); removing.clear() }

        // Nothing left watching the clock: stop rather than posting an empty
        // callback every 16ms for the rest of the process's life. The next
        // add() restarts the loop.
        if (callbacks.isEmpty()) { running = false; lastNanos = 0L; return }
        Choreographer.getInstance().postFrameCallback(this)

        val dt = if (lastNanos == 0L) 1f / 60f
        else ((frameTimeNanos - lastNanos) / 1e9f).coerceIn(1f / 240f, 1f / 20f)
        lastNanos = frameTimeNanos
        time += dt

        // Iterate a snapshot: a callback is allowed to add or remove others.
        for (cb in callbacks.toList()) cb(dt, time)
    }
}

/** Glyph scramble used for every label that changes at runtime. */
class Scramble(private val speed: Float = 1f) {
    private val glyphs = "АБВГДЕЖЗИКЛМНОПРСТУФХЦЧШЩЭЮЯ0123456789#%&@*<>/\\|=+-"
    private var frame = 0f
    private var stop: (() -> Unit)? = null
    private var current = ""
    private var queue: List<Item> = emptyList()

    private class Item(val from: Char?, val to: Char?, val start: Int, val end: Int) {
        var glyph: Char? = null
    }

    fun setInstant(text: String, apply: (String) -> Unit) {
        stop?.invoke(); stop = null
        current = text
        apply(text)
    }

    fun set(text: String, apply: (String) -> Unit) {
        if (text == current) return
        val from = current
        current = text
        val length = maxOf(from.length, text.length)
        queue = (0 until length).map { i ->
            val start = (Math.random() * 12 / speed).toInt()
            Item(from.getOrNull(i), text.getOrNull(i), start, start + (Math.random() * 14 / speed).toInt() + 4)
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
        // Progress in nominal 60 Hz frames derived from real time, so the
        // effect runs at one speed on every device.
        frame += dt * 60f
        if (complete == queue.size) {
            stop?.invoke(); stop = null
            apply(current)
        }
    }

    fun dispose() { stop?.invoke(); stop = null }
}
