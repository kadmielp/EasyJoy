package app.easyjoy

import android.content.Context
import android.content.SharedPreferences
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Persisted user settings (SharedPreferences) plus the haptics helper. */
object Settings {
    private var prefs: SharedPreferences? = null
    private var vibrator: Vibrator? = null

    var vibration by mutableStateOf(true)
        private set
    var sensitivity by mutableFloatStateOf(1.5f)
        private set
    /** Size multiplier for the gamepad controls (0.8..1.2). */
    var buttonScale by mutableFloatStateOf(1f)
        private set
    /** Tilt angle (degrees) treated as straight ahead, and the tilt that gives full steering. */
    var tiltCenter by mutableFloatStateOf(0f)
        private set
    var tiltMax by mutableFloatStateOf(45f)
        private set
    var lastIp: String? = null
        private set
    var lastName: String? = null
        private set

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val app = context.applicationContext
        val p = app.getSharedPreferences("easyjoy", Context.MODE_PRIVATE)
        prefs = p
        vibration = p.getBoolean("vibration", true)
        sensitivity = p.getFloat("sensitivity", 1.5f)
        buttonScale = p.getFloat("buttonScale", 1f).coerceIn(MIN_SCALE, MAX_SCALE)
        tiltCenter = p.getFloat("tiltCenter", 0f)
        tiltMax = p.getFloat("tiltMax", 45f).coerceIn(15f, 90f)
        Layouts.init(app)
        lastIp = p.getString("lastIp", null)
        lastName = p.getString("lastName", null)
        vibrator = app.getSystemService(Vibrator::class.java)
    }

    fun updateVibration(v: Boolean) {
        vibration = v
        prefs?.edit()?.putBoolean("vibration", v)?.apply()
    }

    fun updateSensitivity(v: Float) {
        sensitivity = v
        prefs?.edit()?.putFloat("sensitivity", v)?.apply()
    }

    const val MIN_SCALE = 0.8f
    const val MAX_SCALE = 1.2f

    fun updateButtonScale(v: Float) {
        buttonScale = v.coerceIn(MIN_SCALE, MAX_SCALE)
        prefs?.edit()?.putFloat("buttonScale", buttonScale)?.apply()
    }

    fun updateTiltCenter(v: Float) {
        tiltCenter = v
        prefs?.edit()?.putFloat("tiltCenter", v)?.apply()
    }

    fun updateTiltMax(v: Float) {
        tiltMax = v.coerceIn(15f, 90f)
        prefs?.edit()?.putFloat("tiltMax", tiltMax)?.apply()
    }

    fun updateLast(ip: String?, name: String?) {
        lastIp = ip
        lastName = name
        prefs?.edit()?.apply {
            if (ip == null) remove("lastIp") else putString("lastIp", ip)
            if (name == null) remove("lastName") else putString("lastName", name)
        }?.apply()
    }

    /** Short haptic tick, honouring the user setting. */
    fun buzz(ms: Long = 15) {
        if (!vibration) return
        try {
            vibrator?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {
        }
    }
}
