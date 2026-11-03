package com.aide.assistant.actions

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.aide.assistant.Scheduler
import com.aide.assistant.calls.CallLogHelper
import com.aide.assistant.graph
import com.aide.core.model.ActionRecord
import com.aide.core.model.ActionStatus
import com.aide.core.model.ActionType
import com.aide.core.model.Interaction
import com.aide.core.model.ProposedAction
import com.aide.core.model.ResearchReport
import com.aide.core.model.ResearchStatus
import com.aide.core.time.LocalTimes
import java.time.ZoneId
import java.util.UUID

/** Turns what Claude decided into real things on the phone: events, reminders, research, drafts. */
class ActionExecutor(private val context: Context) {
    private val settings = context.graph.settings
    private val store = context.graph.store
    private val zone: ZoneId get() = ZoneId.systemDefault()

    /** Executes (or queues for approval) every proposed action; returns the stored records. */
    fun executeAll(interaction: Interaction, actions: List<ProposedAction>): List<ActionRecord> =
        actions.map { proposed ->
            val now = System.currentTimeMillis()
            val record = ActionRecord(
                id = UUID.randomUUID().toString(),
                interactionId = interaction.id,
                action = proposed,
                status = ActionStatus.IN_PROGRESS,
                createdAt = now,
                updatedAt = now,
            )
            val done = try {
                execute(interaction, record, approved = false)
            } catch (e: Exception) {
                record.copy(status = ActionStatus.FAILED, resultMessage = "Failed: ${e.message}")
            }
            store.update { it.upsert(done) }
            done
        }

    /** Run an action that was waiting for the user's approval. */
    fun approve(actionId: String): ActionRecord? {
        val record = store.data.action(actionId) ?: return null
        val interaction = store.data.interaction(record.interactionId) ?: return null
        val done = try {
            execute(interaction, record, approved = true)
        } catch (e: Exception) {
            record.copy(status = ActionStatus.FAILED, resultMessage = "Failed: ${e.message}")
        }
        store.update { it.upsert(done.copy(updatedAt = System.currentTimeMillis())) }
        return done
    }

    /** Reverse an action the assistant took (delete the event, cancel the reminder...). */
    fun undo(actionId: String): ActionRecord? {
        val record = store.data.action(actionId) ?: return null
        when (record.action.type) {
            ActionType.CALENDAR_EVENT -> record.calendarEventId?.let { CalendarHelper.deleteEvent(context, it) }
            ActionType.REMINDER, ActionType.ALARM -> ReminderScheduler.cancel(context, record.id)
            else -> Unit
        }
        val undone = record.copy(
            status = ActionStatus.UNDONE, resultMessage = "Undone by you", updatedAt = System.currentTimeMillis(),
        )
        store.update { it.upsert(undone) }
        return undone
    }

    private fun execute(interaction: Interaction, record: ActionRecord, approved: Boolean): ActionRecord {
        val a = record.action
        return when (a.type) {
            ActionType.CALENDAR_EVENT ->
                if (!approved && !settings.autoCalendar) awaiting(record, "Tap Approve to add it to your calendar")
                else addToCalendar(interaction, record)

            ActionType.REMINDER, ActionType.ALARM ->
                if (!approved && !settings.autoReminders) awaiting(record, "Tap Approve to schedule it")
                else scheduleReminder(record)

            ActionType.RESEARCH ->
                if (!approved && !settings.autoResearch) awaiting(record, "Tap Approve to start the research")
                else startResearch(interaction, record)

            ActionType.TODO -> record.copy(
                status = ActionStatus.DONE,
                resultMessage = "Added to your to-do list",
                scheduledAt = LocalTimes.resolve(a.startLocal, zone)?.epochMillis,
            )

            ActionType.NOTE -> record.copy(status = ActionStatus.DONE, resultMessage = "Saved to your notes")

            // Messages are never sent without you: Aide drafts them and you send with one tap.
            ActionType.MESSAGE_DRAFT -> record.copy(
                status = ActionStatus.AWAITING_APPROVAL,
                resultMessage = "Draft ready - tap Send to open it in WhatsApp or Messages",
            )
        }
    }

    private fun awaiting(record: ActionRecord, message: String) =
        record.copy(status = ActionStatus.AWAITING_APPROVAL, resultMessage = message)

    private fun addToCalendar(interaction: Interaction, record: ActionRecord): ActionRecord {
        val a = record.action
        if (!CalendarHelper.hasPermission(context)) {
            return awaiting(record, "Allow calendar access in Settings, then tap Approve")
        }
        val start = LocalTimes.resolve(a.startLocal, zone)
            ?: return record.copy(status = ActionStatus.FAILED, resultMessage = "No date/time was understood for this event")
        val allDay = a.allDay || start.dateOnly
        val end = LocalTimes.resolve(a.endLocal, zone)?.epochMillis?.takeIf { it > start.epochMillis }
            ?: (start.epochMillis + 60 * 60 * 1000L)
        val calendarId = CalendarHelper.resolveCalendarId(context, settings.calendarId)
            ?: return record.copy(status = ActionStatus.FAILED, resultMessage = "No writable calendar found on this phone")

        val nearby = CalendarHelper.upcoming(context, start.epochMillis - 3 * 60 * 60 * 1000L, end + 3 * 60 * 60 * 1000L)
        if (!allDay && CalendarHelper.looksDuplicate(nearby, a.title, start.epochMillis)) {
            return record.copy(
                status = ActionStatus.DONE, scheduledAt = start.epochMillis,
                resultMessage = "Already in your calendar - not added twice",
            )
        }
        val conflicts = if (allDay) emptyList() else nearby.filter { !it.allDay && it.beginMillis < end && it.endMillis > start.epochMillis }

        val description = buildString {
            a.details?.let { appendLine(it) }
            if (a.people.isNotEmpty()) appendLine("With: ${a.people.joinToString()}")
            a.evidence?.let { appendLine("From your conversation: \"$it\"") }
            append("Added automatically by Aide")
            interaction.contactName?.let { append(" after your call with $it") }
        }
        val eventId = CalendarHelper.insertEvent(
            context, calendarId, a.title, description, start.epochMillis, end, allDay, a.location,
        ) ?: return record.copy(status = ActionStatus.FAILED, resultMessage = "The calendar refused the event")

        val whenText = if (allDay) LocalTimes.day(start.epochMillis) else LocalTimes.short(start.epochMillis)
        val msg = buildString {
            append("Added to calendar for $whenText")
            if (conflicts.isNotEmpty()) append(" ⚠ overlaps with: ${conflicts.joinToString { it.title }}")
        }
        return record.copy(
            status = ActionStatus.DONE, calendarEventId = eventId, scheduledAt = start.epochMillis, resultMessage = msg,
        )
    }

    private fun scheduleReminder(record: ActionRecord): ActionRecord {
        val a = record.action
        val time = LocalTimes.resolve(a.startLocal, zone)
        if (time == null) {
            return record.copy(status = ActionStatus.DONE, resultMessage = "No time given - kept as a to-do")
        }
        var at = if (time.dateOnly) time.epochMillis + 9 * 60 * 60 * 1000L else time.epochMillis
        val now = System.currentTimeMillis()
        if (at <= now) at = now + 60 * 1000L // already due: remind in a minute rather than never
        val scheduled = record.copy(status = ActionStatus.DONE, scheduledAt = at)
        store.update { it.upsert(scheduled) } // the receiver looks the record up when it fires
        ReminderScheduler.schedule(context, record.id, at)
        val label = if (a.type == ActionType.ALARM) "Alarm set for" else "Reminder set for"
        return scheduled.copy(resultMessage = "$label ${LocalTimes.short(at)}")
    }

    private fun startResearch(interaction: Interaction, record: ActionRecord): ActionRecord {
        val a = record.action
        val question = a.researchQuestion?.takeIf { it.isNotBlank() } ?: a.title
        val report = ResearchReport(
            id = UUID.randomUUID().toString(),
            interactionId = interaction.id,
            actionId = record.id,
            topic = a.title,
            question = question,
            context = interaction.analysis?.summary,
            status = ResearchStatus.QUEUED,
            createdAt = System.currentTimeMillis(),
        )
        store.update { it.upsert(report) }
        Scheduler.research(context, report.id)
        return record.copy(status = ActionStatus.IN_PROGRESS, researchId = report.id, resultMessage = "Researching…")
    }

    /** Intent that opens WhatsApp (if the contact has it) or the SMS app with the draft filled in. */
    fun messageIntent(record: ActionRecord, preferWhatsApp: Boolean): Intent {
        val body = record.action.messageBody ?: record.action.title
        val interaction = store.data.interaction(record.interactionId)
        val recipient = record.action.recipient
        val sameAsCaller = recipient == null || recipient.equals(interaction?.contactName, ignoreCase = true)
        val number = (if (sameAsCaller) interaction?.contactNumber else null)
            ?: CallLogHelper.numberForName(context, recipient)
            ?: interaction?.contactNumber
        val digits = number?.filter { it.isDigit() || it == '+' }
        return if (preferWhatsApp && digits != null) {
            Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/${digits.trimStart('+')}?text=${Uri.encode(body)}"))
        } else {
            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${digits ?: ""}")).putExtra("sms_body", body)
        }
    }
}
