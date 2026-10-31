package dev.wplayer.app.ui.settings

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

/**
 * The app's language: the system's language by default, or one picked in
 * Settings. It is the platform's per-app language, so the system keeps it
 * (and shows it in its own app settings on Android 13+); AppCompat stores
 * it on older versions.
 */
enum class AppLanguage(val tag: String) {
    SYSTEM(""),
    ENGLISH("en"),
    KOREAN("ko"),
    ;

    /** The language's name in itself ("한국어"), so it can be found whatever the current language. */
    val nativeName: String
        get() = Locale.forLanguageTag(tag).let { it.getDisplayName(it) }

    /** Switches to this language; open screens are recreated in it. */
    fun apply() = AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))

    companion object {
        fun current(): AppLanguage {
            val locale = AppCompatDelegate.getApplicationLocales()[0] ?: return SYSTEM
            return entries.firstOrNull { it != SYSTEM && it.tag == locale.language } ?: SYSTEM
        }
    }
}
