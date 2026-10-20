package com.nuvio.app.features.plugins

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import java.io.File

internal class PluginScraperCodeFileStore(
    private val root: File,
) {
    private val lock = SynchronizedObject()

    fun contains(profileId: Int, scraperId: String): Boolean = synchronized(lock) {
        scraperCodeFile(profileId, scraperId).isFile
    }

    fun load(profileId: Int, scraperId: String): String? = synchronized(lock) {
        val file = scraperCodeFile(profileId, scraperId).takeIf(File::isFile)
            ?: return@synchronized null
        runCatching { file.readText() }.getOrNull()
    }

    fun save(
        profileId: Int,
        scraperId: String,
        code: String,
        overwrite: Boolean,
    ): Boolean {
        val target = scraperCodeFile(profileId, scraperId)
        if (!overwrite && target.isFile) return true
        return synchronized(lock) {
            if (!overwrite && target.isFile) return@synchronized true
            val directory = target.parentFile ?: return@synchronized false
            if (!directory.exists() && !directory.mkdirs()) return@synchronized false

            val temporary = runCatching {
                File.createTempFile("scraper-", ".tmp", directory)
            }.getOrNull() ?: return@synchronized false
            val backup = directory.resolve("${target.name}.backup")

            try {
                temporary.bufferedWriter().use { writer -> writer.write(code) }
                if (!overwrite && target.isFile) return@synchronized true
                if (backup.exists() && !backup.delete()) return@synchronized false
                val hadTarget = target.exists()
                if (hadTarget && !target.renameTo(backup)) return@synchronized false
                if (!temporary.renameTo(target)) {
                    if (hadTarget) backup.renameTo(target)
                    return@synchronized false
                }
                if (backup.exists()) backup.delete()
                true
            } catch (_: Throwable) {
                false
            } finally {
                if (temporary.exists()) temporary.delete()
                if (backup.exists() && !target.exists()) backup.renameTo(target)
            }
        }
    }

    private fun scraperCodeFile(profileId: Int, scraperId: String): File {
        val fileName = "${pluginDigestHex("SHA256", scraperId)}.js"
        return root.resolve(profileId.toString()).resolve(fileName)
    }

    /**
     * Deletes a scraper's cached code, including the backup and any temp file
     * a [save] may have left behind. Best-effort: returns false if any delete fails.
     */
    fun delete(profileId: Int, scraperId: String): Boolean = synchronized(lock) {
        val target = scraperCodeFile(profileId, scraperId)
        val directory = target.parentFile ?: return@synchronized false
        // Temp files from save() are named "scraper-<random>.tmp"; under the lock no
        // save can be in flight, so sweeping them is safe.
        directory.listFiles { file ->
            file.isFile && file.name.startsWith("scraper-") && file.name.endsWith(".tmp")
        }?.forEach { it.delete() }
        var ok = true
        if (target.exists() && !target.delete()) ok = false
        val backup = directory.resolve("${target.name}.backup")
        if (backup.exists() && !backup.delete()) ok = false
        ok
    }

    /** Deletes the whole cached-code directory for a profile. Missing dir counts as success. */
    fun deleteProfileDir(profileId: Int): Boolean = synchronized(lock) {
        val dir = root.resolve(profileId.toString())
        if (!dir.exists()) return@synchronized true
        dir.deleteRecursively()
    }
}
