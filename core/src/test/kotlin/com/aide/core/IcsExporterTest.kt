package com.aide.core

import com.aide.core.export.IcsExporter
import com.aide.core.model.ActionStatus
import com.aide.core.model.ActionType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IcsExporterTest {
    private val zone = Fixtures.zone
    private val now = Fixtures.now

    /** Undo RFC 5545 line folding so tests can assert on logical lines. */
    private fun unfold(ics: String): List<String> =
        ics.split("\r\n").dropLast(1).fold(ArrayList<String>()) { acc, l ->
            if (l.startsWith(" ") && acc.isNotEmpty()) acc[acc.size - 1] = acc.last() + l.substring(1) else acc += l
            acc
        }

    @Test
    fun exportsEventsRemindersAndTodosAsValidStructure() {
        val ics = IcsExporter.export(Fixtures.actions, zone, now)
        assertTrue(ics.startsWith("BEGIN:VCALENDAR\r\nVERSION:2.0\r\n"))
        assertTrue(ics.endsWith("END:VCALENDAR\r\n"))
        assertFalse(ics.replace("\r\n", "").contains('\n'), "only CRLF line endings")
        val lines = unfold(ics)
        assertEquals(2, lines.count { it == "BEGIN:VEVENT" }, "meeting + reminder")
        assertEquals(1, lines.count { it == "BEGIN:VTODO" })
        assertEquals(lines.count { it.startsWith("BEGIN:") }, lines.count { it.startsWith("END:") })
        // the draft awaiting approval, the research and the note are not exported
        assertFalse(lines.any { it.contains("Text Priya") || it.contains("HDFC") || it.contains("Research") })
    }

    @Test
    fun eventTimesAreUtcWithDefaultOneHourAndAlert() {
        val lines = unfold(IcsExporter.export(Fixtures.actions.filter { it.id == "a-meet" }, zone, now))
        // 17:00 IST on 2 Nov = 11:30 UTC
        assertTrue("DTSTART:20261102T113000Z" in lines, lines.toString())
        assertTrue("DTEND:20261102T123000Z" in lines)
        assertTrue("DTSTAMP:20261105T063000Z" in lines)
        assertTrue("UID:a-meet@aide.local" in lines)
        assertTrue("SUMMARY:Meet Rahul" in lines)
        assertTrue("LOCATION:Cafe Coffee Day" in lines)
        assertTrue("TRIGGER:-PT30M" in lines)
        assertTrue(lines.any { it.startsWith("DESCRIPTION:Discuss the website\\nWith: Rahul Sharma") })
    }

    @Test
    fun explicitEndTimeIsUsed() {
        val r = Fixtures.action("e1", "i", ActionType.CALENDAR_EVENT, "Workshop", scheduledAt = Fixtures.ms(11, 8, 10),
            startLocal = "2026-11-08T10:00", endLocal = "2026-11-08T12:30")
        val lines = unfold(IcsExporter.export(listOf(r), zone, now))
        assertTrue("DTSTART:20261108T043000Z" in lines)
        assertTrue("DTEND:20261108T070000Z" in lines)
    }

    @Test
    fun allDayEventsUseDateValuesAndExclusiveEnd() {
        val r = Fixtures.action("d1", "i", ActionType.CALENDAR_EVENT, "Diwali", scheduledAt = Fixtures.ms(11, 8),
            startLocal = "2026-11-08", allDay = true)
        val lines = unfold(IcsExporter.export(listOf(r), zone, now))
        assertTrue("DTSTART;VALUE=DATE:20261108" in lines, lines.toString())
        assertTrue("DTEND;VALUE=DATE:20261109" in lines)
        // not "30 minutes before midnight": the alert is at 09:00 on the day itself
        assertTrue("TRIGGER:PT9H" in lines && lines.none { it == "TRIGGER:-PT30M" }, lines.toString())
    }

    @Test
    fun remindersAreShortEventsAlertingAtTheStart() {
        val lines = unfold(IcsExporter.export(Fixtures.actions.filter { it.id == "a-bill" }, zone, now))
        assertTrue("DTSTART:20261106T033000Z" in lines)
        assertTrue("DTEND:20261106T034500Z" in lines)
        assertTrue("TRIGGER:PT0S" in lines)
    }

    @Test
    fun todoHasDueTimeAndNeedsActionStatus() {
        val lines = unfold(IcsExporter.export(Fixtures.actions.filter { it.id == "a-quote" }, zone, now))
        assertTrue("DUE:20261106T033000Z" in lines)
        assertTrue("STATUS:NEEDS-ACTION" in lines)
        assertTrue("SUMMARY:Send quote to Rahul" in lines)
    }

    @Test
    fun onlyCarriedOutItemsAreExportedAndCompletedOnesOnRequest() {
        val statuses = listOf(ActionStatus.UNDONE, ActionStatus.DISMISSED, ActionStatus.FAILED, ActionStatus.AWAITING_APPROVAL, ActionStatus.IN_PROGRESS)
        for (st in statuses) {
            val rec = Fixtures.actions.first { it.id == "a-meet" }.copy(status = st)
            assertFalse(IcsExporter.export(listOf(rec), zone, now).contains("BEGIN:VEVENT"), "$st must not be exported")
            assertFalse(IcsExporter.isExportable(rec))
        }
        val done = Fixtures.actions.first { it.id == "a-bill" }.copy(completed = true)
        assertFalse(IcsExporter.export(listOf(done), zone, now).contains("BEGIN:VEVENT"))
        assertTrue(IcsExporter.export(listOf(done), zone, now, includeCompleted = true).contains("BEGIN:VEVENT"))
    }

    @Test
    fun itemsWithoutAnyTimeAreSkippedInsteadOfCrashing() {
        val r = Fixtures.action("n1", "i", ActionType.CALENDAR_EVENT, "Mystery meeting")
        assertFalse(IcsExporter.export(listOf(r), zone, now).contains("VEVENT"))
        // a to-do without a time is still a valid VTODO, just without DUE
        val t = Fixtures.action("t1", "i", ActionType.TODO, "Water plants")
        val lines = unfold(IcsExporter.export(listOf(t), zone, now))
        assertTrue("BEGIN:VTODO" in lines && lines.none { it.startsWith("DUE") })
    }

    @Test
    fun escapesSpecialCharacters() {
        assertEquals("a\\;b\\,c\\\\d\\ne", IcsExporter.escapeText("a;b,c\\d\ne"))
        val r = Fixtures.action("x", "i", ActionType.CALENDAR_EVENT, "Lunch; with, Priya", scheduledAt = Fixtures.ms(11, 8, 13),
            startLocal = "2026-11-08T13:00", location = "Social, Bandra")
        val lines = unfold(IcsExporter.export(listOf(r), zone, now))
        assertTrue("SUMMARY:Lunch\\; with\\, Priya" in lines)
        assertTrue("LOCATION:Social\\, Bandra" in lines)
    }

    /**
     * Writes a realistic export (Hindi title, commas, long multi-line description, all-day entry, to-do) to
     * build/live-out/aide-export.ics so it can be checked with an independent iCalendar parser afterwards.
     */
    @Test
    fun writesASampleFileForExternalValidation() {
        val extra = listOf(
            Fixtures.action("h1", "i", ActionType.CALENDAR_EVENT, "राहुल से मिलना, कैफे कॉफी डे", scheduledAt = Fixtures.ms(11, 8, 17),
                startLocal = "2026-11-08T17:00", location = "Café Coffee Day; Andheri West, Mumbai",
                details = "Discuss the website redesign, budget (Rs 45,000) and the launch timeline; bring the signed quote.\nSecond line.",
                people = listOf("Rahul Sharma", "Priya")),
            Fixtures.action("d2", "i", ActionType.CALENDAR_EVENT, "Diwali", scheduledAt = Fixtures.ms(11, 8), startLocal = "2026-11-08", allDay = true),
        )
        val ics = IcsExporter.export(Fixtures.actions + extra, zone, now)
        val out = java.io.File("build/live-out").apply { mkdirs() }.resolve("aide-export.ics")
        out.writeText(ics)
        assertTrue(out.length() > 500)
    }

    @Test
    fun longLinesFoldAt75OctetsWithoutSplittingCharacters() {
        val long = "SUMMARY:" + "राहुल से मिलना ".repeat(12) + "🙂".repeat(10)
        val physical = IcsExporter.fold(long)
        assertTrue(physical.size > 2)
        physical.forEach { assertTrue(it.toByteArray(Charsets.UTF_8).size <= 75, "too long: ${it.toByteArray().size}") }
        assertTrue(physical.drop(1).all { it.startsWith(" ") })
        // unfolding gives back the original, and every piece is valid text (no broken surrogate pairs)
        assertEquals(long, physical.first() + physical.drop(1).joinToString("") { it.substring(1) })
        assertTrue(physical.none { it.contains('�') })
        assertEquals(listOf("short"), IcsExporter.fold("short"))
    }
}
