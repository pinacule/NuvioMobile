package com.nuvio.app.core.storage

import java.util.prefs.Preferences

/**
 * JVM/desktop key-value storage backing the `*Storage` expect objects.
 *
 * Uses the standard [Preferences] API, so values land in the platform's usual
 * place (e.g. `~/.java/.userPrefs` on Linux) without any extra dependencies.
 * Instances are cheap; each storage object keeps one for its own node.
 */
internal class DesktopPreferences(nodeName: String) {
    private val prefs: Preferences = Preferences.userRoot().node("com/nuvio/app/$nodeName")

    fun getString(key: String): String? = prefs.get(key, null)

    fun putString(key: String, value: String) {
        prefs.put(key, value)
        flushQuietly()
    }

    fun getBoolean(key: String): Boolean? =
        prefs.get(key, null)?.toBooleanStrictOrNull()

    fun putBoolean(key: String, value: Boolean) {
        prefs.put(key, value.toString())
        flushQuietly()
    }

    fun getInt(key: String): Int? = prefs.get(key, null)?.toIntOrNull()

    fun putInt(key: String, value: Int) {
        prefs.putInt(key, value)
        flushQuietly()
    }

    fun getLong(key: String): Long? = prefs.get(key, null)?.toLongOrNull()

    fun putLong(key: String, value: Long) {
        prefs.putLong(key, value)
        flushQuietly()
    }

    fun getFloat(key: String): Float? = prefs.get(key, null)?.toFloatOrNull()

    fun putFloat(key: String, value: Float) {
        prefs.putFloat(key, value)
        flushQuietly()
    }

    fun getStringSet(key: String): Set<String>? {
        val count = prefs.getInt("$key#count", -1)
        if (count < 0) return null
        return (0 until count).mapTo(LinkedHashSet()) { index ->
            prefs.get("$key#$index", "") ?: ""
        }
    }

    fun putStringSet(key: String, value: Set<String>) {
        val oldCount = prefs.getInt("$key#count", 0)
        value.forEachIndexed { index, item -> prefs.put("$key#$index", item) }
        // Drop stale entries when the new set is smaller.
        for (index in value.size until oldCount) prefs.remove("$key#$index")
        prefs.putInt("$key#count", value.size)
        flushQuietly()
    }

    fun remove(key: String) {
        prefs.remove(key)
        flushQuietly()
    }

    fun clear() {
        prefs.clear()
        flushQuietly()
    }

    companion object {
        /**
         * Removes every node created through [DesktopPreferences].
         * Used by the account-data wipe; never call for a single storage.
         */
        fun clearAll() {
            runCatching {
                val root = Preferences.userRoot().node("com/nuvio/app")
                root.childrenNames().forEach { child ->
                    runCatching { root.node(child).removeNode() }
                }
                root.flush()
            }
        }
    }

    private fun flushQuietly() {
        try {
            prefs.flush()
        } catch (_: Exception) {
            // Best effort: an unflushed write stays in memory for this session.
        }
    }
}
