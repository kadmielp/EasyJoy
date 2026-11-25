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
    /** Send the gyroscope to emulators on the PC (DSU). */
    var motion by mutableStateOf(true)
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
    /** Gamepad button pressed by the phone's volume keys on controller screens ("none" = normal volume). */
    var volumeUp by mutableStateOf(NONE)
        private set
    var volumeDown by mutableStateOf(NONE)
        private set
    /** UI language choice: [I18n.AUTO], "pt", "en" or "es". */
    var language by mutableStateOf(I18n.AUTO)
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
        motion = p.getBoolean("motion", true)
        sensitivity = p.getFloat("sensitivity", 1.5f)
        buttonScale = p.getFloat("buttonScale", 1f).coerceIn(MIN_SCALE, MAX_SCALE)
        tiltCenter = p.getFloat("tiltCenter", 0f)
        tiltMax = p.getFloat("tiltMax", 45f).coerceIn(15f, 90f)
        volumeUp = p.getString("volumeUp", NONE)!!.takeIf { t -> VolumeTargets.any { it.id == t } } ?: NONE
        volumeDown = p.getString("volumeDown", NONE)!!.takeIf { t -> VolumeTargets.any { it.id == t } } ?: NONE
        language = p.getString("language", I18n.AUTO)!!.takeIf { it in I18n.Choices } ?: I18n.AUTO
        I18n.apply(language)
        Layouts.init(app)
        lastIp = p.getString("lastIp", null)
        lastName = p.getString("lastName", null)
        vibrator = app.getSystemService(Vibrator::class.java)
    }

    fun updateVibration(v: Boolean) {
        vibration = v
        prefs?.edit()?.putBoolean("vibration", v)?.apply()
    }

    fun updateMotion(v: Boolean) {
        motion = v
        prefs?.edit()?.putBoolean("motion", v)?.apply()
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

    fun updateVolumeUp(id: String) {
        volumeUp = id
        prefs?.edit()?.putString("volumeUp", id)?.apply()
    }

    fun updateVolumeDown(id: String) {
        volumeDown = id
        prefs?.edit()?.putString("volumeDown", id)?.apply()
    }

    fun updateLanguage(choice: String) {
        language = choice
        I18n.apply(choice)
        prefs?.edit()?.putString("language", choice)?.apply()
    }

    const val NONE = "none"

    /** A gamepad button a volume key can press: XInput [mask], or [trigger] 1 = LT, 2 = RT. */
    data class VolumeTarget(val id: String, private val name: String, val mask: Int = 0, val trigger: Int = 0) {
        val label: String get() = when (id) {
            NONE -> tr("Nenhum (volume)", "None (volume)", "Ninguno (volumen)")
            "UP", "DOWN", "LEFT", "RIGHT" -> tr("Direcional", "D-pad", "Cruceta") + " " + name
            else -> name
        }
    }

    val VolumeTargets = listOf(
        VolumeTarget(NONE, ""),
        VolumeTarget("A", "A", 0x1000), VolumeTarget("B", "B", 0x2000),
        VolumeTarget("X", "X", 0x4000), VolumeTarget("Y", "Y", 0x8000),
        VolumeTarget("LB", "LB", 0x0100), VolumeTarget("RB", "RB", 0x0200),
        VolumeTarget("LT", "LT", trigger = 1), VolumeTarget("RT", "RT", trigger = 2),
        VolumeTarget("BACK", "Back", 0x0020), VolumeTarget("START", "Start", 0x0010), VolumeTarget("HOME", "Home", 0x0400),
        VolumeTarget("UP", "↑", 0x0001), VolumeTarget("DOWN", "↓", 0x0002),
        VolumeTarget("LEFT", "←", 0x0004), VolumeTarget("RIGHT", "→", 0x0008),
        VolumeTarget("L3", "L3", 0x0040), VolumeTarget("R3", "R3", 0x0080),
    )

    fun volumeTarget(id: String) = VolumeTargets.firstOrNull { it.id == id && it.id != NONE }

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
