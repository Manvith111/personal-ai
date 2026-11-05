package com.aide.core

import com.aide.core.brain.OwnerProfile
import com.aide.core.brain.UpcomingEvent
import com.aide.core.model.ActionStatus
import com.aide.core.model.ActionType
import com.aide.core.model.AppData
import com.aide.core.people.PeopleIndex
import com.aide.core.review.WeeklyLog
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PeopleAndWeeklyTest {
    private val owner = OwnerProfile("Nakul", "English", "India")
    private val data = Fixtures.data()

    // ---- people ----

    @Test
    fun firstNamesMatchLongerNamesButDifferentFullNamesDoNot() {
        assertTrue(PeopleIndex.samePerson("Rahul", "Rahul Sharma"))
        assertTrue(PeopleIndex.samePerson("rahul  SHARMA", "Rahul Sharma"))
        assertFalse(PeopleIndex.samePerson("Rahul Sharma", "Rahul Verma"))
        assertFalse(PeopleIndex.samePerson("Rahul", "Priya"))
        assertFalse(PeopleIndex.samePerson("", "Priya"))
    }

    @Test
    fun phoneNumbersAreNotPeople() {
        assertTrue(PeopleIndex.looksLikeNumber("+91 98200 00000"))
        assertTrue(PeopleIndex.looksLikeNumber("(022) 2345-6789"))
        assertFalse(PeopleIndex.looksLikeNumber("Rahul 2"))
        assertFalse(PeopleIndex.looksLikeNumber("12345"))
    }

    @Test
    fun listMergesSpellingsExcludesOwnerAndNumbersAndSortsByRecency() {
        val extra = Fixtures.typedNote.copy(id = "i-num", contactName = "+91 99999 11111", occurredAt = Fixtures.ms(11, 3))
        val ownerMention = Fixtures.typedNote.copy(
            id = "i-own", occurredAt = Fixtures.ms(11, 1),
            analysis = Fixtures.typedNote.analysis!!.copy(peopleMentioned = listOf("Nakul")),
        )
        val d = data.copy(interactions = data.interactions + extra + ownerMention)
        val people = PeopleIndex.list(d, ownerName = "Nakul")
        assertEquals(listOf("Priya", "Rahul Sharma"), people.map { it.name }, "newest first; Rahul merged into Rahul Sharma")
        val rahul = people.single { it.name == "Rahul Sharma" }
        assertEquals(1, rahul.conversations)
        assertEquals(Fixtures.rahulCall.occurredAt, rahul.lastMillis)
    }

    @Test
    fun openItemsOfAPerson() {
        val rahul = PeopleIndex.openItemsFor(data, "Rahul").map { it.id }
        assertEquals(listOf("a-quote"), rahul)
        val priya = PeopleIndex.openItemsFor(data, "Priya").map { it.id }
        assertEquals(listOf("a-draft"), priya, "a draft awaiting approval is open; the running research is not")
        // completed to-dos are no longer open
        val done = data.copy(actions = data.actions.map { if (it.id == "a-quote") it.copy(completed = true) else it })
        assertTrue(PeopleIndex.openItemsFor(done, "Rahul").isEmpty())
    }

    @Test
    fun conversationsWithAPersonIncludeMentionsNewestFirst() {
        val mention = Fixtures.typedNote.copy(
            id = "i-mention", occurredAt = Fixtures.ms(11, 5, 11, 0),
            analysis = Fixtures.typedNote.analysis!!.copy(peopleMentioned = listOf("Rahul")),
        )
        val ids = PeopleIndex.conversationsWith(data.copy(interactions = data.interactions + mention), "Rahul Sharma").map { it.id }
        assertEquals(listOf("i-mention", "i-rahul"), ids)
    }

    @Test
    fun briefingContextHasHistoryOpenItemsAndCalendar() {
        val ctx = PeopleIndex.briefingContext(
            data, "Rahul", owner, Fixtures.zone, Fixtures.now,
            upcoming = listOf(
                UpcomingEvent("Lunch with Rahul", Fixtures.ms(11, 7, 13), Fixtures.ms(11, 7, 14), false),
                UpcomingEvent("Dentist", Fixtures.ms(11, 7, 9), Fixtures.ms(11, 7, 10), false),
            ),
        )
        assertTrue(ctx.contains("Person to brief the owner about: Rahul"))
        assertTrue(ctx.contains("total_conversations=\"1\""))
        assertTrue(ctx.contains("Website quote: Rs 45,000"))
        assertTrue(ctx.contains("todo: Send quote to Rahul (due Fri 6 Nov, 9:00 AM)"))
        assertTrue(ctx.contains("Lunch with Rahul") && !ctx.contains("Dentist"))
        assertFalse(ctx.contains("Priya"), "other people's conversations must not leak into the brief")
    }

    @Test
    fun briefingContextForAStrangerSaysThereIsNoHistory() {
        val ctx = PeopleIndex.briefingContext(data, "Zed", owner, Fixtures.zone, Fixtures.now)
        assertTrue(ctx.contains("(no recorded conversations with this person)"))
        assertTrue(ctx.contains("total_conversations=\"0\""))
    }

    // ---- weekly review ----

    @Test
    fun weekStatsAreComputedInCode() {
        val s = WeeklyLog.stats(data, LocalDate.of(2026, 11, 5), Fixtures.zone, Fixtures.now, "Nakul")
        assertEquals(LocalDate.of(2026, 10, 30), s.from)
        assertEquals(3, s.conversations, "dentist note is from October")
        assertEquals(LocalDate.of(2026, 11, 2), s.busiestDay, "three days with one conversation each: the earliest wins the tie")
        assertEquals(1, s.conversationsByDay[LocalDate.of(2026, 11, 4)])
        assertEquals(1, s.waitingForUser)
        assertEquals(1, s.openTodos)
        assertEquals(0, s.overdueTodos, "the quote is due tomorrow")
        assertEquals(1, s.researchDone)
        assertEquals(setOf("Priya", "Rahul Sharma"), s.topPeople.map { it.first }.toSet())
        assertEquals(1, s.actionsByType[ActionType.CALENDAR_EVENT])
    }

    @Test
    fun overdueTodosAreCounted() {
        val late = data.copy(actions = data.actions.map { if (it.id == "a-quote") it.copy(scheduledAt = Fixtures.ms(11, 4, 9)) else it })
        val s = WeeklyLog.stats(late, LocalDate.of(2026, 11, 5), Fixtures.zone, Fixtures.now)
        assertEquals(1, s.overdueTodos)
    }

    @Test
    fun weeklyLogContainsNumbersConversationsAndNextWeek() {
        val next = UpcomingEvent("Board meeting", Fixtures.ms(11, 9, 10), Fixtures.ms(11, 9, 11), false)
        val tooFar = UpcomingEvent("Diwali party", Fixtures.ms(11, 20, 19), Fixtures.ms(11, 20, 22), false)
        val log = WeeklyLog.build(data, owner, Fixtures.zone, Fixtures.now, listOf(next, tooFar))
        assertNotNull(log)
        assertTrue(log.contains("Week: 2026-10-30 to 2026-11-05"))
        assertTrue(log.contains("Conversations: 3"))
        assertTrue(log.contains("Call with Rahul - meeting and website quote"))
        assertTrue(log.contains("to-do: Send quote to Rahul"))
        assertTrue(log.contains("waiting for approval: message_draft - Text Priya the studio address"))
        assertTrue(log.contains("Co-working spaces in Andheri under 10k: Bottom line: Regus Lokhandwala"))
        assertTrue(log.contains("Board meeting") && !log.contains("Diwali"))
        assertFalse(log.contains("Dentist appointment"), "last month's note is outside the week")
    }

    @Test
    fun quietWeekHasNoLog() {
        assertNull(WeeklyLog.build(AppData(), owner, Fixtures.zone, Fixtures.now))
        val old = AppData(interactions = listOf(Fixtures.dentist))
        assertNull(WeeklyLog.build(old, owner, Fixtures.zone, Fixtures.now))
    }

    @Test
    fun undoneActionsDoNotCountAsHandled() {
        val undone = data.copy(actions = data.actions.map { it.copy(status = ActionStatus.UNDONE) })
        assertEquals(0, WeeklyLog.stats(undone, LocalDate.of(2026, 11, 5), Fixtures.zone, Fixtures.now).handled)
    }
}
