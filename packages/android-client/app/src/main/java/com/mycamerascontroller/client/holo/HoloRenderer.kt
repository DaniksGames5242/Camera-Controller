package com.mycamerascontroller.client.holo

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Draws the room the whole Android interface sits inside.
 *
 * Render graph: raymarched stage → additive dust → bright pass → separable
 * blur → optics composite. It is deliberately the same shape as the desktop
 * pipeline so the two clients look like one product, with the step counts and
 * buffer formats chosen for a phone.
 *
 * All mutable state written from the UI thread is plain volatile scalars read
 * once per frame; there is no lock on the render path.
 */
class HoloRenderer : GLSurfaceView.Renderer {

    @Volatile var tiltX = 0f
    @Volatile var tiltY = 0f
    @Volatile var energy = 0f
    @Volatile var glitch = 0f
    @Volatile var boot = 0f
    @Volatile var quality = 1f

    private var width = 1
    private var height = 1
    private var startNanos = 0L
    private var lastNanos = 0L
    private var time = 0f
    private var scanPhase = -40f
    private var frameCost = 16f

    private val ripples = FloatArray(6 * 4)
    private var rippleCursor = 0
    private val touch = FloatArray(4)

    private var quad: FullscreenTriangle? = null
    private var stageProgram: GlProgram? = null
    private var particleProgram: GlProgram? = null
    private var brightProgram: GlProgram? = null
    private var blurProgram: GlProgram? = null
    private var compositeProgram: GlProgram? = null
    private var scene: GlTarget? = null
    private var bloomA: GlTarget? = null
    private var bloomB: GlTarget? = null
    private val emptyVao = IntArray(1)

    /** Particle count, scaled down automatically when frames get expensive. */
    private var particleCount = 2600

    /** Queues a floor ripple in world x/z. Safe to call from the UI thread. */
    fun ripple(x: Float, z: Float, strength: Float) {
        val i = (rippleCursor % 6) * 4
        rippleCursor++
        synchronized(ripples) {
            ripples[i] = x
            ripples[i + 1] = z
            ripples[i + 2] = time
            ripples[i + 3] = strength
        }
        energy = min(1f, energy + 0.35f * strength)
    }

    /** A finger landing at a world position; deforms the dust field. */
    fun touchImpulse(x: Float, y: Float, strength: Float) {
        synchronized(touch) {
            touch[0] = x
            touch[1] = y
            touch[2] = time
            touch[3] = strength
        }
        energy = min(1f, energy + 0.3f * strength)
    }

    fun kickGlitch(amount: Float) { glitch = min(1.4f, glitch + amount) }

    fun triggerScan() { scanPhase = -42f }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        quad = FullscreenTriangle()
        stageProgram = GlProgram(FULLSCREEN_VERT, STAGE_FRAG, "stage")
        particleProgram = GlProgram(PARTICLE_VERT, PARTICLE_FRAG, "particles")
        brightProgram = GlProgram(FULLSCREEN_VERT, BRIGHT_FRAG, "bright")
        blurProgram = GlProgram(FULLSCREEN_VERT, BLUR_FRAG, "blur")
        compositeProgram = GlProgram(FULLSCREEN_VERT, COMPOSITE_FRAG, "composite")
        GLES30.glGenVertexArrays(1, emptyVao, 0)
        startNanos = System.nanoTime()
        lastNanos = startNanos
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = max(1, w)
        height = max(1, h)
        // The raymarch cost is per pixel of the *scene* target, not the
        // screen — this is the single biggest lever a phone GPU has, well
        // before the step-count/particle governor below even engages.
        // Composited back through the bloom chain's linear filtering, the
        // softness reads as part of the hologram's look rather than as
        // upscaling artifacts.
        val sceneW = max(1, (width * RENDER_SCALE).toInt())
        val sceneH = max(1, (height * RENDER_SCALE).toInt())
        scene?.release(); bloomA?.release(); bloomB?.release()
        scene = GlTarget(sceneW, sceneH)
        bloomA = GlTarget(sceneW / 2, sceneH / 2)
        bloomB = GlTarget(sceneW / 2, sceneH / 2)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        val dt = ((now - lastNanos) / 1e9f).coerceIn(1f / 240f, 1f / 20f)
        lastNanos = now
        time = (now - startNanos) / 1e9f

        governQuality(dt * 1000f, dt)

        // Excitement and glitch decay on their own; every interaction pushes
        // them back up, so the room is visibly calmer when nothing is going on.
        energy = max(0f, energy * exp(-1.3f * dt))
        glitch = max(0f, glitch * exp(-6f * dt) - dt * 0.15f)
        scanPhase += dt * 26f
        if (scanPhase > 50f) scanPhase = -42f

        val sceneTarget = scene ?: return
        val bloom1 = bloomA ?: return
        val bloom2 = bloomB ?: return
        val tri = quad ?: return

        // ---- stage -----------------------------------------------------------
        sceneTarget.bind()
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_BLEND)

        stageProgram?.use()?.apply {
            v2("uResolution", sceneTarget.width.toFloat(), sceneTarget.height.toFloat())
            f("uTime", time)
            v2("uTilt", tiltX, tiltY)
            f("uEnergy", energy)
            f("uBoot", boot)
            f("uScanPhase", scanPhase)
            f("uSteps", 6f + quality * 12f)
            synchronized(ripples) { v4Array("uRipples", ripples, 6) }
        }
        tri.draw()

        // ---- dust ------------------------------------------------------------
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE)
        particleProgram?.use()?.apply {
            f("uTime", time)
            v2("uResolution", sceneTarget.width.toFloat(), sceneTarget.height.toFloat())
            v2("uTilt", tiltX, tiltY)
            f("uEnergy", energy)
            f("uBoot", max(0.08f, boot))
            f("uCount", particleCount.toFloat())
            synchronized(touch) {
                GLES30.glUniform4f(loc("uTouch"), touch[0], touch[1], touch[2], touch[3])
            }
        }
        GLES30.glBindVertexArray(emptyVao[0])
        GLES30.glDrawArrays(GLES30.GL_POINTS, 0, particleCount)
        GLES30.glBindVertexArray(0)
        GLES30.glDisable(GLES30.GL_BLEND)

        // ---- bloom -----------------------------------------------------------
        bloom1.bind()
        brightProgram?.use()?.apply {
            tex("uScene", 0, sceneTarget.texture)
            v2("uTexel", 1f / sceneTarget.width, 1f / sceneTarget.height)
        }
        tri.draw()

        blurProgram?.use()
        bloom2.bind()
        blurProgram?.tex("uSource", 0, bloom1.texture)?.v2("uDirection", 1.6f / bloom2.width, 0f)
        tri.draw()
        bloom1.bind()
        blurProgram?.tex("uSource", 0, bloom2.texture)?.v2("uDirection", 0f, 1.6f / bloom1.height)
        tri.draw()

        // ---- optics ----------------------------------------------------------
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, width, height)
        compositeProgram?.use()?.apply {
            tex("uScene", 0, sceneTarget.texture)
            tex("uBloom", 1, bloom1.texture)
            // This pass draws at the native viewport (below), unlike the
            // scaled-down scene/particle passes above — screen-space effects
            // here (scanlines, vignette) need the real pixel resolution.
            v2("uResolution", width.toFloat(), height.toFloat())
            f("uTime", time)
            f("uGlitch", min(1f, glitch))
            f("uEnergy", energy)
            f("uBoot", boot)
            v2("uTilt", tiltX, tiltY)
        }
        tri.draw()
    }

    /**
     * Trades march steps first and particle count second. A phone that cannot
     * hold the frame rate gets a simpler room rather than a stuttering one.
     */
    private fun governQuality(frameMs: Float, dt: Float) {
        frameCost += (frameMs.coerceIn(1f, 500f) - frameCost) * (1f - exp(-2.5f * dt))
        if (frameCost > 24f) {
            quality = max(0f, quality - dt * 1.2f)
            if (quality <= 0.02f) particleCount = max(700, particleCount - 40)
        } else if (frameCost < 13f) {
            if (particleCount < 2600) particleCount = min(2600, particleCount + 12)
            else quality = min(1f, quality + dt * 0.3f)
        }
    }

    private companion object {
        /** Fraction of native resolution the raymarched scene renders at. */
        const val RENDER_SCALE = 0.65f
    }
}
