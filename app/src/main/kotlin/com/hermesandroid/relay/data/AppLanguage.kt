package com.hermesandroid.relay.data

import androidx.core.os.LocaleListCompat
import java.util.Locale

/** Languages exposed by the in-app picker and Android's per-app language UI. */
enum class AppLanguage(val languageTag: String) {
    SYSTEM_DEFAULT(""),
    ENGLISH("en"),
    SIMPLIFIED_CHINESE("zh-Hans"),
    ;

    fun toLocaleList(): LocaleListCompat = if (languageTag.isEmpty()) {
        LocaleListCompat.getEmptyLocaleList()
    } else {
        LocaleListCompat.forLanguageTags(languageTag)
    }

    companion object {
        fun fromLanguageTags(languageTags: String): AppLanguage {
            val primaryTag = languageTags
                .substringBefore(',')
                .trim()
                .takeIf { it.isNotEmpty() }
                ?: return SYSTEM_DEFAULT
            val locale = Locale.forLanguageTag(primaryTag)

            return when (locale.language.lowercase(Locale.ROOT)) {
                "en" -> ENGLISH
                "zh" -> {
                    val simplified = locale.script.equals("Hans", ignoreCase = true) ||
                        locale.script.isEmpty() ||
                        locale.country.equals("CN", ignoreCase = true) ||
                        locale.country.equals("SG", ignoreCase = true)
                    if (simplified) SIMPLIFIED_CHINESE else SYSTEM_DEFAULT
                }
                else -> SYSTEM_DEFAULT
            }
        }
    }
}
