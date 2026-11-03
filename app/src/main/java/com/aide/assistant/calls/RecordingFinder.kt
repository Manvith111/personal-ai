package com.aide.assistant.calls

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile

data class FoundRecording(
    val uri: Uri,
    val name: String,
    val sizeBytes: Long,
    /** When the file was last written - for call recordings that is roughly when the call ended. */
    val timeMillis: Long,
)

/**
 * Finds call recordings saved by the phone's built-in dialer (Samsung, Xiaomi, OnePlus, Oppo, Realme,
 * Vivo... save them as normal audio files). Two sources:
 *  1. MediaStore - every audio file whose folder or name contains "call" (works automatically).
 *  2. A folder the user picked in Settings (for phones that use a different folder name).
 */
object RecordingFinder {

    fun hasAudioPermission(context: Context): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE
        return ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
    }

    fun findSince(context: Context, sinceMillis: Long, folderTreeUri: String?): List<FoundRecording> {
        val found = LinkedHashMap<String, FoundRecording>()
        if (!folderTreeUri.isNullOrBlank()) {
            fromFolder(context, Uri.parse(folderTreeUri), sinceMillis).forEach { found[it.uri.toString()] = it }
        }
        if (hasAudioPermission(context)) {
            fromMediaStore(context, sinceMillis).forEach { found.putIfAbsent(it.uri.toString(), it) }
        }
        return found.values.sortedBy { it.timeMillis }
    }

    private fun fromMediaStore(context: Context, sinceMillis: Long): List<FoundRecording> {
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        @Suppress("DEPRECATION")
        val pathColumn = if (Build.VERSION.SDK_INT >= 29) MediaStore.Audio.Media.RELATIVE_PATH
        else MediaStore.Audio.Media.DATA
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.Media.DATE_ADDED,
        )
        val selection = "(${MediaStore.Audio.Media.DATE_ADDED} >= ? OR ${MediaStore.Audio.Media.DATE_MODIFIED} >= ?) AND " +
            "(LOWER($pathColumn) LIKE ? OR LOWER(${MediaStore.Audio.Media.DISPLAY_NAME}) LIKE ?)"
        val sinceSec = (sinceMillis / 1000).toString()
        val args = arrayOf(sinceSec, sinceSec, "%call%", "%call%")
        val out = mutableListOf<FoundRecording>()
        try {
            context.contentResolver.query(
                collection, projection, selection, args, "${MediaStore.Audio.Media.DATE_ADDED} ASC",
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val modified = c.getLong(3) * 1000
                    val added = c.getLong(4) * 1000
                    out += FoundRecording(
                        uri = ContentUris.withAppendedId(collection, id),
                        name = c.getString(1) ?: "call_$id",
                        sizeBytes = c.getLong(2),
                        timeMillis = maxOf(modified, added),
                    )
                }
            }
        } catch (e: SecurityException) {
            return emptyList()
        }
        return out
    }

    private fun fromFolder(context: Context, tree: Uri, sinceMillis: Long): List<FoundRecording> {
        val root = try {
            DocumentFile.fromTreeUri(context, tree)
        } catch (e: Exception) {
            null
        } ?: return emptyList()
        val out = mutableListOf<FoundRecording>()
        fun walk(dir: DocumentFile, depth: Int) {
            val children: Array<DocumentFile> = try { dir.listFiles() } catch (e: Exception) { emptyArray() }
            for (f in children) {
                if (f.isDirectory && depth < 2) walk(f, depth + 1)
                else if (f.isFile && f.lastModified() >= sinceMillis && looksLikeAudio(f)) {
                    out += FoundRecording(f.uri, f.name ?: "recording", f.length(), f.lastModified())
                }
            }
        }
        walk(root, 0)
        return out
    }

    private val audioExtensions = setOf("m4a", "mp3", "aac", "amr", "3gp", "wav", "ogg", "opus", "flac", "mp4", "awb", "webm")

    private fun looksLikeAudio(f: DocumentFile): Boolean {
        if (f.type?.startsWith("audio/") == true) return true
        val ext = f.name?.substringAfterLast('.', "")?.lowercase() ?: return false
        return ext in audioExtensions
    }

    /** Dialers often name files like "Rahul Sharma_20261102_143512.m4a" - recover the name part. */
    fun nameHintFromFile(fileName: String): String? {
        val base = fileName.substringBeforeLast('.')
            .replace(Regex("(?i)call|recording|rec"), " ")
            .replace(Regex("[_\\-()+]+"), " ")
            .replace(Regex("\\d{4,}"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return base.takeIf { it.length >= 2 && it.any(Char::isLetter) }
    }
}
