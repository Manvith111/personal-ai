package com.aide.assistant.actions

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.aide.core.brain.UpcomingEvent
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.TimeZone

data class CalendarInfo(val id: Long, val name: String, val account: String, val primary: Boolean)

/**
 * Writes straight into the phone's calendar database. Events added to a Google account calendar sync
 * to Google Calendar automatically - no extra sign-in needed.
 */
object CalendarHelper {

    fun hasPermission(context: Context): Boolean =
        listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR).all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

    fun calendars(context: Context): List<CalendarInfo> {
        if (!hasPermission(context)) return emptyList()
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.IS_PRIMARY,
        )
        val selection = "${CalendarContract.Calendars.VISIBLE} = 1 AND " +
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR}"
        val out = mutableListOf<Pair<CalendarInfo, Boolean>>()
        try {
            context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, projection, selection, null, null)
                ?.use { c ->
                    while (c.moveToNext()) {
                        val isGoogle = c.getString(3) == "com.google"
                        out += CalendarInfo(
                            id = c.getLong(0),
                            name = c.getString(1) ?: "Calendar",
                            account = c.getString(2) ?: "",
                            primary = c.getInt(4) == 1,
                        ) to isGoogle
                    }
                }
        } catch (e: SecurityException) {
            return emptyList()
        }
        // Primary Google calendar first, then other Google calendars, then the rest.
        return out.sortedWith(compareByDescending<Pair<CalendarInfo, Boolean>> { it.first.primary && it.second }
            .thenByDescending { it.second }
            .thenByDescending { it.first.primary })
            .map { it.first }
    }

    fun resolveCalendarId(context: Context, preferred: Long): Long? {
        val all = calendars(context)
        return all.firstOrNull { it.id == preferred }?.id ?: all.firstOrNull()?.id
    }

    /** Inserts an event and a 30-minute alert. Returns the new event id. */
    fun insertEvent(
        context: Context,
        calendarId: Long,
        title: String,
        description: String,
        startMillis: Long,
        endMillis: Long,
        allDay: Boolean,
        location: String?,
    ): Long? {
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DESCRIPTION, description)
            if (!location.isNullOrBlank()) put(CalendarContract.Events.EVENT_LOCATION, location)
            if (allDay) {
                // All-day events must be stored as UTC midnights.
                val day = java.time.Instant.ofEpochMilli(startMillis).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                put(CalendarContract.Events.DTSTART, utcMidnight(day))
                put(CalendarContract.Events.DTEND, utcMidnight(day.plusDays(1)))
                put(CalendarContract.Events.ALL_DAY, 1)
                put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
            } else {
                put(CalendarContract.Events.DTSTART, startMillis)
                put(CalendarContract.Events.DTEND, endMillis)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            }
            put(CalendarContract.Events.HAS_ALARM, 1)
        }
        val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values) ?: return null
        val eventId = uri.lastPathSegment?.toLongOrNull() ?: return null
        val reminder = ContentValues().apply {
            put(CalendarContract.Reminders.EVENT_ID, eventId)
            put(CalendarContract.Reminders.MINUTES, if (allDay) 12 * 60 else 30)
            put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
        }
        runCatching { context.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, reminder) }
        return eventId
    }

    fun deleteEvent(context: Context, eventId: Long): Boolean = try {
        context.contentResolver.delete(
            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId), null, null,
        ) > 0
    } catch (e: SecurityException) {
        false
    }

    /** Event instances (recurring ones expanded) overlapping [from, to). */
    fun upcoming(context: Context, from: Long, to: Long): List<UpcomingEvent> {
        if (!hasPermission(context)) return emptyList()
        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, from)
        ContentUris.appendId(builder, to)
        val projection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
        )
        val out = mutableListOf<UpcomingEvent>()
        try {
            context.contentResolver.query(
                builder.build(), projection, null, null, "${CalendarContract.Instances.BEGIN} ASC",
            )?.use { c ->
                while (c.moveToNext()) {
                    out += UpcomingEvent(c.getString(0) ?: "(no title)", c.getLong(1), c.getLong(2), c.getInt(3) == 1)
                }
            }
        } catch (e: SecurityException) {
            return emptyList()
        }
        return out
    }

    /** True when an event that looks like the same thing already sits at about the same time. */
    fun looksDuplicate(existing: List<UpcomingEvent>, title: String, start: Long): Boolean {
        val words = significantWords(title)
        return existing.any { e ->
            kotlin.math.abs(e.beginMillis - start) <= 15 * 60 * 1000L &&
                (e.title.equals(title, ignoreCase = true) || significantWords(e.title).any { it in words })
        }
    }

    private fun significantWords(s: String) =
        s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 4 && it !in stopWords }.toSet()

    private val stopWords = setOf("meet", "meeting", "call", "with", "about", "regarding", "event")

    fun viewEventIntent(eventId: Long): Intent =
        Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId))

    private fun utcMidnight(day: LocalDate): Long = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
}
