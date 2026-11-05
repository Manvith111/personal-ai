package com.aide.core.export

import com.aide.core.model.ActionRecord
import com.aide.core.model.ActionStatus
import com.aide.core.model.ActionType
import com.aide.core.time.LocalTimes
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Exports what Aide scheduled as an iCalendar (.ics, RFC 5545) file that any calendar app can import:
 * Google/Apple/Outlook calendars, or a friend's phone. Useful when the phone's calendar is not
 * connected, for moving to a new phone, and for sharing an event.
 *
 * - calendar events become VEVENTs with a 30-minute alert (like the in-app calendar entries);
 *   all-day events alert at 09:00 that morning instead
 * - reminders become short VEVENTs with an alert at the start
 * - to-dos become VTODOs (with a due time when there is one)
 * Alarms are device-specific and are not exported. Only actions that were really carried out (DONE)
 * are included - never undone, dismissed, failed or still-awaiting-approval ones.
 *
 * Times are written in UTC ("...Z"), so no VTIMEZONE block is needed and every client agrees.
 */
object IcsExporter {
    private val utcStamp = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
    private val dateOnly = DateTimeFormatter.ofPattern("yyyyMMdd")
    private const val DEFAULT_EVENT_MINUTES = 60L
    private const val REMINDER_MINUTES = 15L

    fun export(
        records: List<ActionRecord>,
        zone: ZoneId,
        nowMillis: Long,
        calendarName: String = "Aide",
        includeCompleted: Boolean = false,
    ): String {
        val lines = ArrayList<String>()
        lines += "BEGIN:VCALENDAR"
        lines += "VERSION:2.0"
        lines += "PRODID:-//Aide//Personal Assistant//EN"
        lines += "CALSCALE:GREGORIAN"
        lines += "X-WR-CALNAME:${escapeText(calendarName)}"
        for (r in records.sortedBy { it.scheduledAt ?: Long.MAX_VALUE }) {
            if (r.status != ActionStatus.DONE) continue
            if (r.completed && !includeCompleted) continue
            when (r.action.type) {
                ActionType.CALENDAR_EVENT -> lines += event(r, zone, nowMillis, alarmMinutesBefore = 30, defaultMinutes = DEFAULT_EVENT_MINUTES)
                ActionType.REMINDER -> lines += event(r, zone, nowMillis, alarmMinutesBefore = 0, defaultMinutes = REMINDER_MINUTES, reminder = true)
                ActionType.TODO -> lines += todo(r, zone, nowMillis)
                else -> Unit
            }
        }
        lines += "END:VCALENDAR"
        return lines.flatMap { fold(it) }.joinToString("\r\n", postfix = "\r\n")
    }

    /** True if [r] would appear in an export (so the UI can show a count or disable the button). */
    fun isExportable(r: ActionRecord, includeCompleted: Boolean = false): Boolean =
        r.status == ActionStatus.DONE && (!r.completed || includeCompleted) &&
            r.action.type in setOf(ActionType.CALENDAR_EVENT, ActionType.REMINDER, ActionType.TODO)

    private fun event(
        r: ActionRecord,
        zone: ZoneId,
        now: Long,
        alarmMinutesBefore: Long,
        defaultMinutes: Long,
        reminder: Boolean = false,
    ): List<String> {
        val a = r.action
        val start = LocalTimes.resolve(a.startLocal, zone)
        val startMs = r.scheduledAt ?: start?.epochMillis ?: return emptyList()
        val allDay = a.allDay || (start?.dateOnly == true && r.scheduledAt == null)
        val out = ArrayList<String>()
        out += "BEGIN:VEVENT"
        out += "UID:${r.id}@aide.local"
        out += "DTSTAMP:${utcStamp.format(Instant.ofEpochMilli(now))}"
        if (allDay) {
            val day = LocalDate.ofInstant(Instant.ofEpochMilli(startMs), zone)
            val endDay = LocalTimes.resolve(a.endLocal, zone)?.date?.plusDays(1)?.takeIf { it.isAfter(day) } ?: day.plusDays(1)
            out += "DTSTART;VALUE=DATE:${dateOnly.format(day)}"
            out += "DTEND;VALUE=DATE:${dateOnly.format(endDay)}"
        } else {
            val endMs = LocalTimes.resolve(a.endLocal, zone)?.epochMillis?.takeIf { it > startMs }
                ?: (startMs + defaultMinutes * 60_000L)
            out += "DTSTART:${utcStamp.format(Instant.ofEpochMilli(startMs))}"
            out += "DTEND:${utcStamp.format(Instant.ofEpochMilli(endMs))}"
        }
        out += "SUMMARY:${escapeText(a.title)}"
        description(r, reminder)?.let { out += "DESCRIPTION:${escapeText(it)}" }
        a.location?.takeIf { it.isNotBlank() }?.let { out += "LOCATION:${escapeText(it)}" }
        out += "BEGIN:VALARM"
        out += "ACTION:DISPLAY"
        out += "DESCRIPTION:${escapeText(a.title)}"
        out += when {
            // An all-day event starts at midnight: "30 minutes before" would ring at 23:30 the night before.
            allDay -> "TRIGGER:PT9H" // 09:00 that morning
            alarmMinutesBefore > 0 -> "TRIGGER:-PT${alarmMinutesBefore}M"
            else -> "TRIGGER:PT0S"
        }
        out += "END:VALARM"
        out += "END:VEVENT"
        return out
    }

    private fun todo(r: ActionRecord, zone: ZoneId, now: Long): List<String> {
        val a = r.action
        val due = r.scheduledAt ?: LocalTimes.resolve(a.startLocal, zone)?.epochMillis
        val out = ArrayList<String>()
        out += "BEGIN:VTODO"
        out += "UID:${r.id}@aide.local"
        out += "DTSTAMP:${utcStamp.format(Instant.ofEpochMilli(now))}"
        if (due != null) out += "DUE:${utcStamp.format(Instant.ofEpochMilli(due))}"
        out += "SUMMARY:${escapeText(a.title)}"
        description(r, reminder = false)?.let { out += "DESCRIPTION:${escapeText(it)}" }
        out += if (r.completed) "STATUS:COMPLETED" else "STATUS:NEEDS-ACTION"
        out += "END:VTODO"
        return out
    }

    private fun description(r: ActionRecord, reminder: Boolean): String? {
        val a = r.action
        val parts = listOfNotNull(
            a.details?.takeIf { it.isNotBlank() },
            a.people.takeIf { it.isNotEmpty() }?.let { "With: " + it.joinToString(", ") },
            a.evidence?.takeIf { it.isNotBlank() }?.let { "From the conversation: \"$it\"" },
            if (reminder) "Reminder set by Aide" else null,
        )
        return parts.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    /** iCalendar TEXT escaping: backslash, semicolon, comma, and newlines. */
    fun escapeText(s: String): String = buildString {
        for (ch in s) {
            when (ch) {
                '\\' -> append("\\\\")
                ';' -> append("\\;")
                ',' -> append("\\,")
                '\n' -> append("\\n")
                '\r' -> Unit
                else -> append(ch)
            }
        }
    }

    /**
     * Folds a content line so no physical line exceeds 75 octets (RFC 5545 3.1): continuation lines start
     * with one space. Cuts fall on character boundaries, never in the middle of a multi-byte UTF-8 sequence.
     */
    fun fold(line: String): List<String> {
        if (line.toByteArray(Charsets.UTF_8).size <= 75) return listOf(line)
        val out = ArrayList<String>()
        val current = StringBuilder()
        var bytes = 0
        var limit = 75
        var i = 0
        while (i < line.length) {
            val cp = line.codePointAt(i)
            val chars = Character.charCount(cp)
            val size = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
            if (bytes + size > limit) {
                out += current.toString()
                current.setLength(0)
                current.append(' ')
                bytes = 1
                limit = 75
            }
            current.appendCodePoint(cp)
            bytes += size
            i += chars
        }
        if (current.isNotEmpty()) out += current.toString()
        return out
    }
}
