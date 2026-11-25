package app.easyjoy

import android.app.Activity
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.view.Display
import android.view.Surface

/**
 * Streams the phone's gyroscope + accelerometer to the PC, which serves them to emulators over DSU (Cemuhook).
 *
 * Axes are converted on the phone, because only the phone knows how it is being held:
 * Android sensor axes -> controller axes (X right, Y out of the screen, Z toward the player) -> DSU.
 * DSU (as Eden reads it) wants the accelerometer as the gravity direction in g (flat = Y -1)
 * and the gyro in deg/s with pitch = +X, yaw = -Y, roll = -Z.
 */
object Motion : SensorEventListener {
    private var sensorManager: SensorManager? = null
    @Volatile private var display: Display? = null
    private val accel = FloatArray(3)
    @Volatile private var hasAccel = false
    /** The PC says an emulator is listening (PONG flag). Sensors run only while this is true. */
    private var wanted = false
    private var registered = false

    /** Controller screen visible: sensors may run once the PC asks for motion. */
    @Synchronized
    fun start(activity: Activity) {
        sensorManager = activity.getSystemService(SensorManager::class.java)
        display = if (Build.VERSION.SDK_INT >= 30) activity.display else @Suppress("DEPRECATION") activity.windowManager.defaultDisplay
        sync()
    }

    @Synchronized
    fun stop() {
        sensorManager?.unregisterListener(this)
        registered = false
        sensorManager = null
        display = null
    }

    /** Called from the network thread on every PONG and when the connection drops. */
    @Synchronized
    fun setWanted(w: Boolean) {
        if (wanted == w) return
        wanted = w
        sync()
    }

    private fun sync() {
        val sm = sensorManager ?: return
        val on = wanted && Settings.motion
        if (on && !registered) {
            val gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE) ?: return
            val acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
            hasAccel = false
            val periodUs = 10_000 // 100 Hz
            sm.registerListener(this, acc, periodUs)
            sm.registerListener(this, gyro, periodUs)
            registered = true
        } else if (!on && registered) {
            sm.unregisterListener(this)
            registered = false
        }
    }

    /** Android axes -> controller axes (X right, Y out of the screen, Z toward the player). */
    private fun toController(v: FloatArray, out: FloatArray) {
        val x = v[0]; val y = v[1]; val z = v[2]
        when (display?.rotation) {
            Surface.ROTATION_90 -> { out[0] = -y; out[1] = z; out[2] = -x } // phone top on the left
            Surface.ROTATION_270 -> { out[0] = y; out[1] = z; out[2] = x }  // phone top on the right
            Surface.ROTATION_180 -> { out[0] = -x; out[1] = z; out[2] = y }
            else -> { out[0] = x; out[1] = z; out[2] = -y }
        }
    }

    private val a = FloatArray(3)
    private val g = FloatArray(3)

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                e.values.copyInto(accel, endIndex = 3)
                hasAccel = true
            }
            Sensor.TYPE_GYROSCOPE -> {
                if (!hasAccel) return
                toController(accel, a)
                toController(e.values, g)
                val toG = -1f / SensorManager.GRAVITY_EARTH
                val toDeg = (180.0 / Math.PI).toFloat()
                Connection.sendMotion(
                    e.timestamp / 1000,
                    a[0] * toG, a[1] * toG, a[2] * toG,
                    g[0] * toDeg, -g[1] * toDeg, -g[2] * toDeg,
                )
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
