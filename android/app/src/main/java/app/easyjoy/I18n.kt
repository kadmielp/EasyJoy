package app.easyjoy

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/**
 * UI language. "auto" follows the phone: Portuguese and Spanish phones get their language, everything else English.
 * [lang] is Compose state, so switching it redraws the menus right away.
 */
object I18n {
    const val AUTO = "auto"
    val Choices = listOf(AUTO, "pt", "en", "es")

    var lang by mutableStateOf(resolve(AUTO))
        private set

    fun apply(choice: String) {
        lang = resolve(choice)
    }

    private fun resolve(choice: String): String = when (choice) {
        "pt", "en", "es" -> choice
        else -> when (Locale.getDefault().language) {
            "pt" -> "pt"
            "es" -> "es"
            else -> "en"
        }
    }

    /** Name of a language choice, shown in its own language ("auto" in the current one). */
    fun choiceLabel(choice: String) = when (choice) {
        "pt" -> "Português"
        "en" -> "English"
        "es" -> "Español"
        else -> tr("Automático (idioma do celular)", "Automatic (phone language)", "Automático (idioma del teléfono)")
    }
}

/** Picks the text for the current UI language. */
fun tr(pt: String, en: String, es: String): String = when (I18n.lang) {
    "en" -> en
    "es" -> es
    else -> pt
}
