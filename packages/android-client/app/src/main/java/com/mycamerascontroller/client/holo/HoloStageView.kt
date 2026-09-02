package com.mycamerascontroller.client.holo

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.view.MotionEvent
import kotlin.math.exp
import kotlin.math.min

/**
 * The hologram, as a view you can put behind anything.
 *
 * Beyond hosting the renderer it owns the two inputs that make the Android
 * client feel unlike the desktop one:
 *
 *  - the device's own orientation, low-pass filtered and fed to the shaders
 *    as parallax, so the volume sits behind the glass instead of on it;
 *  - raw touch, converted to world space and turned into ripples and dust
 *    impulses at the exact point the finger landed.
 *
 * It never consumes touch events: it observes them and passes them on, so the
 * interface on top stays completely normal to interact with.
 */
class HoloStageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : GLSurfaceView(context, attrs), SensorEventListener {

    val renderer = HoloRenderer()

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val rotationMatrix = FloatArray(9)
    private val orientation = FloatArray(3)

    private var targetTiltX = 0f
    private var targetTiltY = 0f
    private var restPitch = Float.NaN
    private var restRoll = Float.NaN
    private var lastSensorNanos = 0L

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 0, 0, 0)
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
        // The stage is scenery: it must never intercept a tap meant for the UI.
        isClickable = false
        isFocusable = false
    }

    override fun onResume() {
        super.onResume()
        rotationSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
            ?: accelerometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    override fun onPause() {
        sensorManager.unregisterListener(this)
        super.onPause()
    }

    override fun onSensorChanged(event: SensorEvent) {
        val now = System.nanoTime()
        val dt = if (lastSensorNanos == 0L) 1f / 60f else ((now - lastSensorNanos) / 1e9f).coerceIn(0.001f, 0.1f)
        lastSensorNanos = now

        var pitch: Float
        var roll: Float
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                SensorManager.getOrientation(rotationMatrix, orientation)
                pitch = orientation[1]
                roll = orientation[2]
            }
            Sensor.TYPE_ACCELEROMETER -> {
                // Crude but adequate fallback: gravity's components along the
                // screen axes are the tilt, up to the noise the filter removes.
                roll = -event.values[0] / SensorManager.GRAVITY_EARTH
                pitch = event.values[1] / SensorManager.GRAVITY_EARTH
            }
            else -> return
        }

        // Calibrate to however the user happens to be holding the phone, so
        // the effect is "tilt from here", not "hold your phone level".
        if (restPitch.isNaN()) { restPitch = pitch; restRoll = roll }
        // Let the rest pose drift slowly toward the current one: put the phone
        // down at a new angle and the hologram re-centres instead of staying
        // pinned to the edge of its range.
        restPitch += (pitch - restPitch) * (1f - exp(-0.08f * dt))
        restRoll += (roll - restRoll) * (1f - exp(-0.08f * dt))

        targetTiltX = ((roll - restRoll) * 1.5f).coerceIn(-1f, 1f)
        targetTiltY = ((pitch - restPitch) * 1.5f).coerceIn(-1f, 1f)

        val k = 1f - exp(-6f * dt)
        renderer.tiltX += (targetTiltX - renderer.tiltX) * k
        renderer.tiltY += (targetTiltY - renderer.tiltY) * k
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /**
     * Converts a screen touch into the room and stirs it there. Called by the
     * hosting activity from dispatchTouchEvent, so it sees every gesture
     * without stealing any.
     */
    fun observeTouch(event: MotionEvent) {
        if (event.actionMasked != MotionEvent.ACTION_DOWN && event.actionMasked != MotionEvent.ACTION_POINTER_DOWN) return
        stirScreenPoint(event.x, event.y)
    }

    /**
     * Same effect, driven from a screen point in another view's coordinate
     * space (a button press, a card press) rather than from a raw touch event
     * on this view — so pressing a control on top of the stage still visibly
     * disturbs the room under it.
     */
    fun stirAt(rawX: Float, rawY: Float) {
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        stirScreenPoint(rawX - loc[0], rawY - loc[1])
    }

    private fun stirScreenPoint(x: Float, y: Float) {
        if (width == 0 || height == 0) return
        val ndcX = (x / width) * 2f - 1f
        val ndcY = -((y / height) * 2f - 1f)
        // Unproject onto the plane the dust lives in; the constants mirror the
        // fixed camera the shaders use.
        val aspect = width.toFloat() / height.toFloat()
        val worldX = ndcX * 0.45f * aspect * 15f + renderer.tiltX * 3.4f
        val worldY = ndcY * 0.45f * 15f + 0.6f + renderer.tiltY * 2.6f
        renderer.touchImpulse(worldX, worldY, 1f)
        renderer.ripple(worldX, 0f, min(1f, 0.8f))
    }

    /** Re-zeroes the tilt reference to however the phone is being held now. */
    fun recalibrateTilt() {
        restPitch = Float.NaN
        restRoll = Float.NaN
    }
}
