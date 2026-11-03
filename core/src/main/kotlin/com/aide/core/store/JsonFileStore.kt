package com.aide.core.store

import com.aide.core.model.AppData
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A tiny, crash-safe JSON "database". The whole [AppData] lives in memory and is written to disk
 * atomically (write temp file, then rename) after every change. Thread-safe within one process.
 */
class JsonFileStore(private val file: File) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }
    private val lock = Any()
    private val listeners = CopyOnWriteArrayList<(AppData) -> Unit>()

    @Volatile
    private var current: AppData = load()

    fun snapshot(): AppData = current

    fun update(transform: (AppData) -> AppData): AppData {
        val next = synchronized(lock) {
            val updated = transform(current)
            write(updated)
            current = updated
            updated
        }
        listeners.forEach { it(next) }
        return next
    }

    fun addListener(listener: (AppData) -> Unit) {
        listeners += listener
    }

    private fun load(): AppData {
        if (!file.exists()) return AppData()
        return try {
            json.decodeFromString(AppData.serializer(), file.readText())
        } catch (e: Exception) {
            // Keep the unreadable file for inspection instead of silently overwriting it.
            file.copyTo(File(file.parentFile, file.name + ".corrupt-" + System.currentTimeMillis()), overwrite = true)
            AppData()
        }
    }

    private fun write(data: AppData) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(AppData.serializer(), data))
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) error("Could not save ${file.absolutePath}")
        }
    }
}
