package com.aide.assistant.calls

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import kotlin.math.abs

data class CallInfo(
    val id: String,
    val number: String?,
    val name: String?,
    /** "incoming" / "outgoing" / "missed" ... */
    val direction: String,
    val startMillis: Long,
    val durationSec: Long,
) {
    val endMillis: Long get() = startMillis + durationSec * 1000
}

object CallLogHelper {

    fun hasPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) ==
            PackageManager.PERMISSION_GRANTED

    /** Calls that started after [sinceMillis], newest first. */
    fun recentCalls(context: Context, sinceMillis: Long, limit: Int = 20): List<CallInfo> {
        if (!hasPermission(context)) return emptyList()
        val projection = arrayOf(
            CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME,
            CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION,
        )
        val result = mutableListOf<CallInfo>()
        try {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI, projection,
                "${CallLog.Calls.DATE} > ?", arrayOf(sinceMillis.toString()),
                "${CallLog.Calls.DATE} DESC",
            )?.use { c ->
                while (c.moveToNext() && result.size < limit) {
                    val number = c.getString(1)
                    result += CallInfo(
                        id = c.getLong(0).toString(),
                        number = number,
                        name = c.getString(2)?.takeIf { it.isNotBlank() } ?: lookupContactName(context, number),
                        direction = directionOf(c.getInt(3)),
                        startMillis = c.getLong(4),
                        durationSec = c.getLong(5),
                    )
                }
            }
        } catch (e: SecurityException) {
            return emptyList()
        }
        return result
    }

    /**
     * The call a recording belongs to: recordings are usually finalised right when the call ends,
     * so pick the call whose end time is closest to the file time (within 5 minutes).
     */
    fun matchRecording(context: Context, fileTimeMillis: Long): CallInfo? =
        recentCalls(context, fileTimeMillis - 4 * 60 * 60 * 1000L, limit = 50)
            .filter { abs(it.endMillis - fileTimeMillis) < 5 * 60 * 1000L || fileTimeMillis in it.startMillis..it.endMillis }
            .minByOrNull { abs(it.endMillis - fileTimeMillis) }

    private fun directionOf(type: Int) = when (type) {
        CallLog.Calls.INCOMING_TYPE -> "incoming"
        CallLog.Calls.OUTGOING_TYPE -> "outgoing"
        CallLog.Calls.MISSED_TYPE -> "missed"
        CallLog.Calls.REJECTED_TYPE -> "rejected"
        else -> "phone"
    }

    fun lookupContactName(context: Context, number: String?): String? {
        if (number.isNullOrBlank()) return null
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED
        ) return null
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        return try {
            context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (e: Exception) {
            null
        }
    }

    /** The phone number for a contact name (used to address message drafts). */
    fun numberForName(context: Context, name: String?): String? {
        if (name.isNullOrBlank()) return null
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED
        ) return null
        return try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$name%"),
                null,
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (e: Exception) {
            null
        }
    }
}
