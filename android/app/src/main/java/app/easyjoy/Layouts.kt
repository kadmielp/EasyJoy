package app.easyjoy

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

/**
 * One on-screen control of a gamepad layout.
 * x, y = centre as a fraction of the view width / height; w, h = size in units of the view height.
 */
data class Control(val id: String, val x: Float, val y: Float, val w: Float, val h: Float, val visible: Boolean)

/** A user-saved gamepad layout. [base] is one of [Layouts.FULL], [Layouts.SIMPLE], [Layouts.WHEEL]. */
data class Layout(val name: String, val base: String, val tilt: Boolean, val controls: List<Control>)

/** Saved layouts, persisted as JSON in SharedPreferences. */
object Layouts {
    const val FULL = "full"
    const val SIMPLE = "simple"
    const val WHEEL = "wheel"

    fun baseLabel(base: String) = when (base) {
        FULL -> "Completo"
        SIMPLE -> "Simples"
        else -> "Volante"
    }

    var saved by mutableStateOf<List<Layout>>(emptyList())
        private set

    private var prefs: SharedPreferences? = null

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("easyjoy_layouts", Context.MODE_PRIVATE)
        prefs = p
        saved = try {
            parse(p.getString("layouts", "[]") ?: "[]")
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun get(name: String?): Layout? = name?.let { n -> saved.firstOrNull { it.name == n } }

    /** Saves (or replaces, by name) a layout. */
    fun save(layout: Layout) {
        saved = (saved.filter { it.name != layout.name } + layout).sortedBy { it.name.lowercase() }
        persist()
    }

    fun delete(name: String) {
        saved = saved.filter { it.name != name }
        persist()
    }

    private fun persist() {
        val arr = JSONArray()
        for (l in saved) {
            val controls = JSONArray()
            for (c in l.controls) {
                controls.put(
                    JSONObject()
                        .put("id", c.id)
                        .put("x", c.x.toDouble()).put("y", c.y.toDouble())
                        .put("w", c.w.toDouble()).put("h", c.h.toDouble())
                        .put("visible", c.visible)
                )
            }
            arr.put(JSONObject().put("name", l.name).put("base", l.base).put("tilt", l.tilt).put("controls", controls))
        }
        prefs?.edit()?.putString("layouts", arr.toString())?.apply()
    }

    private fun parse(json: String): List<Layout> {
        val arr = JSONArray(json)
        val out = ArrayList<Layout>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val cs = o.getJSONArray("controls")
            val controls = (0 until cs.length()).map { j ->
                val c = cs.getJSONObject(j)
                Control(
                    c.getString("id"),
                    c.getDouble("x").toFloat(), c.getDouble("y").toFloat(),
                    c.getDouble("w").toFloat(), c.getDouble("h").toFloat(),
                    c.optBoolean("visible", true),
                )
            }
            out += Layout(o.getString("name"), o.optString("base", FULL), o.optBoolean("tilt", false), controls)
        }
        return out
    }
}
