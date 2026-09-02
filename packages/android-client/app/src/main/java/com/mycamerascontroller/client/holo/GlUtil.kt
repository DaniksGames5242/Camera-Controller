package com.mycamerascontroller.client.holo

import android.opengl.GLES30
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Minimal GLES3 helpers for the hologram stage.
 *
 * Deliberately tiny and allocation-free at draw time: this runs on phones,
 * behind a live interface, and every millisecond spent here is one the video
 * decoder does not get.
 */

class GlProgram(vertexSource: String, fragmentSource: String, private val label: String = "program") {

    val handle: Int = GLES30.glCreateProgram().also { program ->
        val vs = compile(GLES30.GL_VERTEX_SHADER, vertexSource, "$label.vert")
        val fs = compile(GLES30.GL_FRAGMENT_SHADER, fragmentSource, "$label.frag")
        GLES30.glAttachShader(program, vs)
        GLES30.glAttachShader(program, fs)
        GLES30.glLinkProgram(program)
        val status = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(program)
            GLES30.glDeleteProgram(program)
            throw RuntimeException("$label: link failed\n$log")
        }
        // The linked program keeps its own references; dropping ours here
        // keeps the driver's object table small.
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
    }

    private val uniforms = HashMap<String, Int>()

    fun use(): GlProgram {
        GLES30.glUseProgram(handle)
        return this
    }

    fun loc(name: String): Int = uniforms.getOrPut(name) { GLES30.glGetUniformLocation(handle, name) }

    fun f(name: String, v: Float): GlProgram { GLES30.glUniform1f(loc(name), v); return this }
    fun i(name: String, v: Int): GlProgram { GLES30.glUniform1i(loc(name), v); return this }
    fun v2(name: String, x: Float, y: Float): GlProgram { GLES30.glUniform2f(loc(name), x, y); return this }
    fun v3(name: String, x: Float, y: Float, z: Float): GlProgram { GLES30.glUniform3f(loc(name), x, y, z); return this }
    fun v4Array(name: String, data: FloatArray, count: Int): GlProgram {
        GLES30.glUniform4fv(loc(name), count, data, 0)
        return this
    }

    fun tex(name: String, unit: Int, texture: Int): GlProgram {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        GLES30.glUniform1i(loc(name), unit)
        return this
    }

    fun release() = GLES30.glDeleteProgram(handle)

    private fun compile(type: Int, source: String, tag: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(shader)
            Log.e("Holo", "$tag compile failed:\n$log")
            source.lines().forEachIndexed { i, l -> Log.e("Holo", "${i + 1}| $l") }
            GLES30.glDeleteShader(shader)
            throw RuntimeException("$tag: compile failed\n$log")
        }
        return shader
    }
}

/**
 * Colour-only render target. RGBA8 rather than half-float on purpose:
 * renderable float attachments still need an extension on plenty of shipping
 * Android drivers, and the scene is tone mapped before it lands here anyway.
 */
class GlTarget(width: Int, height: Int, private val linear: Boolean = true) {
    var width = 0; private set
    var height = 0; private set

    val framebuffer: Int = IntArray(1).also { GLES30.glGenFramebuffers(1, it, 0) }[0]
    val texture: Int = IntArray(1).also { GLES30.glGenTextures(1, it, 0) }[0]

    init { resize(width, height) }

    fun resize(w: Int, h: Int) {
        val nw = w.coerceAtLeast(1)
        val nh = h.coerceAtLeast(1)
        if (nw == width && nh == height) return
        width = nw; height = nh
        val filter = if (linear) GLES30.GL_LINEAR else GLES30.GL_NEAREST
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, nw, nh, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null
        )
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, filter)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, filter)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, texture, 0
        )
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    fun bind() {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
        GLES30.glViewport(0, 0, width, height)
    }

    fun release() {
        GLES30.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
        GLES30.glDeleteTextures(1, intArrayOf(texture), 0)
    }
}

/** One oversized triangle: no seam, one fewer vertex, each pixel shaded once. */
class FullscreenTriangle {
    private val vbo = IntArray(1)
    private val vao = IntArray(1)

    init {
        val data = floatArrayOf(-1f, -1f, 3f, -1f, -1f, 3f)
        val buffer: FloatBuffer = ByteBuffer.allocateDirect(data.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().put(data).also { it.position(0) }
        GLES30.glGenVertexArrays(1, vao, 0)
        GLES30.glGenBuffers(1, vbo, 0)
        GLES30.glBindVertexArray(vao[0])
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[0])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, data.size * 4, buffer, GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glBindVertexArray(0)
    }

    fun draw() {
        GLES30.glBindVertexArray(vao[0])
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glBindVertexArray(0)
    }

    fun release() {
        GLES30.glDeleteBuffers(1, vbo, 0)
        GLES30.glDeleteVertexArrays(1, vao, 0)
    }
}

const val FULLSCREEN_VERT = """#version 300 es
layout(location = 0) in vec2 aPos;
out vec2 vUv;
void main() {
  vUv = aPos * 0.5 + 0.5;
  gl_Position = vec4(aPos, 0.0, 1.0);
}
"""
