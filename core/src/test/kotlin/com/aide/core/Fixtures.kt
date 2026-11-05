package com.aide.core

import com.aide.core.model.ActionRecord
import com.aide.core.model.ActionStatus
import com.aide.core.model.ActionType
import com.aide.core.model.Analysis
import com.aide.core.model.AppData
import com.aide.core.model.Interaction
import com.aide.core.model.ProcessingStatus
import com.aide.core.model.ProposedAction
import com.aide.core.model.ResearchReport
import com.aide.core.model.ResearchStatus
import com.aide.core.model.SourceType
import java.time.LocalDateTime
import java.time.ZoneId

/** A small, realistic week of data shared by the tests. "Now" is Thursday 5 Nov 2026, 12:00 in India. */
object Fixtures {
    val zone: ZoneId = ZoneId.of("Asia/Kolkata")

    fun ms(month: Int, day: Int, hour: Int = 0, minute: Int = 0, year: Int = 2026): Long =
        LocalDateTime.of(year, month, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    val now = ms(11, 5, 12, 0)

    fun action(
        id: String, interactionId: String, type: ActionType, title: String,
        status: ActionStatus = ActionStatus.DONE, scheduledAt: Long? = null, completed: Boolean = false,
        details: String? = null, people: List<String> = emptyList(), recipient: String? = null,
        startLocal: String? = null, endLocal: String? = null, allDay: Boolean = false, location: String? = null,
        researchId: String? = null, updatedAt: Long = now, evidence: String? = null,
    ) = ActionRecord(
        id = id, interactionId = interactionId, status = status, scheduledAt = scheduledAt, completed = completed,
        researchId = researchId, createdAt = updatedAt, updatedAt = updatedAt,
        action = ProposedAction(
            type = type, title = title, details = details, people = people, recipient = recipient,
            startLocal = startLocal, endLocal = endLocal, allDay = allDay, location = location, evidence = evidence,
        ),
    )

    val rahulCall = Interaction(
        id = "i-rahul", source = SourceType.CALL, occurredAt = ms(11, 2, 14, 35), createdAt = ms(11, 2, 14, 40),
        contactName = "Rahul Sharma", contactNumber = "+91 98200 00000", callDirection = "incoming", durationSec = 252,
        transcript = "Hi Rahul. Yes I will meet you today at 5pm at Cafe Coffee Day. About the website, the quote is " +
            "forty five thousand rupees, I will send it to you by Friday. My new HDFC account number is 5010 0123 4567.",
        status = ProcessingStatus.DONE,
        analysis = Analysis(
            title = "Call with Rahul - meeting and website quote",
            summary = "Agreed to meet Rahul at 5pm at Cafe Coffee Day. The website quote is 45,000 rupees and will be sent by Friday.",
            keyPoints = listOf("Meet at Cafe Coffee Day, 5pm", "Website quote: Rs 45,000", "Quote due Friday"),
            peopleMentioned = listOf("Rahul"),
        ),
    )

    val priyaCall = Interaction(
        id = "i-priya", source = SourceType.CALL, occurredAt = ms(11, 4, 10, 0), createdAt = ms(11, 4, 10, 10),
        contactName = "Priya", callDirection = "outgoing", durationSec = 120,
        transcript = "Priya, can you find good co-working spaces in Andheri under ten thousand? I'll text you the address of the studio.",
        status = ProcessingStatus.DONE,
        analysis = Analysis(
            title = "Call with Priya - co-working search",
            summary = "Priya needs a co-working space in Andheri under Rs 10,000. The user will text her the studio address.",
            keyPoints = listOf("Budget under Rs 10,000", "Area: Andheri"),
            peopleMentioned = listOf("Priya"),
        ),
    )

    val typedNote = Interaction(
        id = "i-typed", source = SourceType.TYPED, occurredAt = ms(11, 5, 9, 0), createdAt = ms(11, 5, 9, 0),
        transcript = "Remind me to pay the electricity bill on Friday", status = ProcessingStatus.DONE,
        analysis = Analysis(title = "Pay the electricity bill", summary = "Reminder set for Friday morning."),
    )

    val dentist = Interaction(
        id = "i-dentist", source = SourceType.SHARED_TEXT, occurredAt = ms(10, 10, 18, 0), createdAt = ms(10, 10, 18, 0),
        transcript = "Your dentist appointment with Dr. Mehta is confirmed for 14 October at 11:00.", status = ProcessingStatus.DONE,
        analysis = Analysis(title = "Dentist appointment", summary = "Appointment with Dr. Mehta on 14 October at 11:00."),
    )

    val research = ResearchReport(
        id = "r1", topic = "Co-working spaces in Andheri under 10k", question = "Best co-working in Andheri under 10k?",
        status = ResearchStatus.DONE, createdAt = ms(11, 4, 10, 5), finishedAt = ms(11, 4, 10, 9),
        markdown = "# Co-working in Andheri\n**Bottom line:** Regus Lokhandwala is the best fit at about Rs 8,500 a month.",
    )

    val actions = listOf(
        action("a-meet", "i-rahul", ActionType.CALENDAR_EVENT, "Meet Rahul", scheduledAt = ms(11, 2, 17, 0),
            startLocal = "2026-11-02T17:00", location = "Cafe Coffee Day", people = listOf("Rahul Sharma"),
            details = "Discuss the website", updatedAt = ms(11, 2, 14, 41), evidence = "I will meet you today at 5pm"),
        action("a-quote", "i-rahul", ActionType.TODO, "Send quote to Rahul", scheduledAt = ms(11, 6, 9, 0),
            people = listOf("Rahul"), updatedAt = ms(11, 2, 14, 41)),
        action("a-account", "i-rahul", ActionType.NOTE, "Rahul's HDFC account number", details = "5010 0123 4567",
            updatedAt = ms(11, 2, 14, 41)),
        action("a-research", "i-priya", ActionType.RESEARCH, "Research co-working in Andheri", researchId = "r1",
            updatedAt = ms(11, 4, 10, 9)),
        action("a-draft", "i-priya", ActionType.MESSAGE_DRAFT, "Text Priya the studio address",
            status = ActionStatus.AWAITING_APPROVAL, recipient = "Priya", updatedAt = ms(11, 4, 10, 11)),
        action("a-bill", "i-typed", ActionType.REMINDER, "Pay electricity bill", scheduledAt = ms(11, 6, 9, 0),
            startLocal = "2026-11-06T09:00", updatedAt = ms(11, 5, 9, 1)),
    )

    fun data(): AppData = AppData(
        interactions = listOf(typedNote, priyaCall, rahulCall, dentist),
        actions = actions,
        research = listOf(research),
    )
}
