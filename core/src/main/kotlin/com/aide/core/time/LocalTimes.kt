package com.aide.core.time

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/** A time the model gave us, resolved to a real instant in the phone's time zone. */
data class ResolvedTime(
    val epochMillis: Long,
    /** True when the model only gave a date (no clock time). */
    val dateOnly: Boolean,
    val date: LocalDate,
)

object LocalTimes {
    private val friendly = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy, HH:mm", Locale.ENGLISH)
    private val shortFmt = DateTimeFormatter.ofPattern("EEE d MMM, h:mm a", Locale.ENGLISH)
    private val dayFmt = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

    /** Parses "yyyy-MM-ddTHH:mm[:ss]" or "yyyy-MM-dd" (what the prompt asks Claude for). */
    fun resolve(value: String?, zone: ZoneId): ResolvedTime? {
        val s = value?.trim()?.removeSuffix("Z")?.takeIf { it.isNotEmpty() } ?: return null
        return try {
            if (s.length <= 10) {
                val d = LocalDate.parse(s)
                ResolvedTime(d.atStartOfDay(zone).toInstant().toEpochMilli(), dateOnly = true, date = d)
            } else {
                // Tolerate an offset if the model added one anyway, e.g. 2026-11-02T17:00+05:30
                val ldt = try {
                    LocalDateTime.parse(s)
                } catch (e: DateTimeParseException) {
                    ZonedDateTime.parse(s).withZoneSameInstant(zone).toLocalDateTime()
                }
                ResolvedTime(ldt.atZone(zone).toInstant().toEpochMilli(), dateOnly = false, date = ldt.toLocalDate())
            }
        } catch (e: Exception) {
            null
        }
    }

    /** "Monday, 2 November 2026, 14:35" - used in prompts so the model can resolve "today", "tomorrow"... */
    fun describeForPrompt(epochMillis: Long, zone: ZoneId): String =
        friendly.format(Instant.ofEpochMilli(epochMillis).atZone(zone))

    fun short(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        shortFmt.format(Instant.ofEpochMilli(epochMillis).atZone(zone))

    fun day(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        dayFmt.format(Instant.ofEpochMilli(epochMillis).atZone(zone))

    fun isoDate(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate().toString()
}
