package com.nuvio.app.features.player

import java.util.Locale

internal actual object DeviceLanguagePreferences {
    actual fun preferredLanguageCodes(): List<String> {
        val languages = mutableListOf<String>()
        appendLocaleCodes(languages, Locale.getDefault())
        if (languages.isEmpty()) {
            appendLocaleCodes(languages, Locale.ENGLISH)
        }
        return languages
            .mapNotNull(::normalizeLanguageCode)
            .distinct()
    }

    private fun appendLocaleCodes(bucket: MutableList<String>, locale: Locale) {
        bucket += locale.toLanguageTag()
        bucket += locale.language
    }
}
