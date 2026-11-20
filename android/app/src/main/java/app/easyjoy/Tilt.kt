package app.easyjoy

import android.app.Activity
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.view.Display
import android.view.Surface
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.sign

/**
 * Steering from phone tilt, for a phone held in landscape.
 *
 * Uses the gravity component along the phone's long axis, so it works both when the phone is held
 * upright and turned like a wheel, and when it is held flatter and one side is lifted.
 * [angle] is in degrees, positive = turning right.
 */
object Tilt : SensorEventListener {
    @Volatile var angle = 0f
        private set
    /** False until the first reading after start(): never steer on a stale angle. */
    @Volatile private var fresh = false

    private var sensorManager: SensorManager? = null
    private var display: Display? = null
    private var onChange: (() -> Unit)? = null
    private var lowPass = false
    private var filteredY = 0f

    fun available(activity: Activity): Boolean {
        val sm = activity.getSystemService(SensorManager::class.java) ?: return false
        return sm.getDefaultSensor(Sensor.TYPE_GRAVITY) != null || sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null
    }

    fun start(activity: Activity, listener: () -> Unit) {
        stop()
        angle = 0f
        fresh = false
        val sm = activity.getSystemService(SensorManager::class.java) ?: return
        display = if (Build.VERSION.SDK_INT >= 30) activity.display else @Suppress("DEPRECATION") activity.windowManager.defaultDisplay
        val gravity = sm.getDefaultSensor(Sensor.TYPE_GRAVITY)
        val sensor = gravity ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        lowPass = gravity == null
        filteredY = 0f
        onChange = listener
        sensorManager = sm
        sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
        sensorManager = null
        onChange = null
        fresh = false
    }

    override fun onSensorChanged(e: SensorEvent) {
        var gy = e.values[1]
        if (lowPass) {
            filteredY += (gy - filteredY) * 0.2f
            gy = filteredY
        }
        val deg = Math.toDegrees(asin((gy / SensorManager.GRAVITY_EARTH).coerceIn(-1f, 1f).toDouble())).toFloat()
        // ROTATION_90: phone top on the left, so raising the left side (turning right) gives +y.
        val dir = if (display?.rotation == Surface.ROTATION_270) -1f else 1f
        angle = deg * dir
        fresh = true
        onChange?.invoke()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /** Steering in -1..1, using the user's centre and the angle that gives full lock. */
    fun steering(): Float {
        if (!fresh) return 0f
        val dead = 2f
        val a = angle - Settings.tiltCenter
        if (abs(a) <= dead) return 0f
        return ((a - sign(a) * dead) / (Settings.tiltMax - dead)).coerceIn(-1f, 1f)
    }
}
