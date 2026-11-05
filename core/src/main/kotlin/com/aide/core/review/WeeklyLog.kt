package com.aide.core.review

import com.aide.core.brain.ContextBuilder
import com.aide.core.brain.OwnerProfile
import com.aide.core.brain.UpcomingEvent
import com.aide.core.brain.sanitizeForPrompt
import com.aide.core.model.ActionStatus
import com.aide.core.model.ActionType
import com.aide.core.model.AppData
import com.aide.core.model.ProcessingStatus
import com.aide.core.model.ResearchStatus
import com.aide.core.people.PeopleIndex
import com.aide.core.time.LocalTimes
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/** Counts for one 7-day window, computed in code so the review never relies on the model to count. */
data class WeekStats(
    val from: LocalDate,
    val to: LocalDate,
    val conversations: Int,
    val conversationsByDay: Map<LocalDate, Int>,
    val actionsByType: Map<ActionType, Int>,
    val handled: Int,
    val waitingForUser: Int,
    val failed: Int,
    val openTodos: Int,
    val overdueTodos: Int,
    val completedItems: Int,
    val researchDone: Int,
    val busiestDay: LocalDate?,
    val topPeople: List<Pair<String, Int>>,
)

/** Builds the plain-text log of the past week that Claude turns into the weekly review. */
object WeeklyLog {

    /** The window is the 7 days ending on [endDate] (inclusive). */
    fun stats(data: AppData, endDate: LocalDate, zone: ZoneId, nowMillis: Long, ownerName: String = ""): WeekStats {
        val from = endDate.minusDays(6)
        val startMs = from.atStartOfDay(zone).toInstant().toEpochMilli()
        val endMs = endDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        val conversations = data.interactions.filter { it.occurredAt in startMs until endMs }
        val byDay = conversations.groupingBy { LocalDate.ofInstant(java.time.Instant.ofEpochMilli(it.occurredAt), zone) }
            .eachCount().toSortedMap()
        val actions = data.actions.filter { it.updatedAt in startMs until endMs || it.createdAt in startMs until endMs }
        val liveActions = actions.filter { it.status != ActionStatus.UNDONE && it.status != ActionStatus.DISMISSED }
        val openTodos = data.actions.filter {
            it.action.type == ActionType.TODO && it.status == ActionStatus.DONE && !it.completed
        }
        val research = data.research.filter {
            it.status == ResearchStatus.DONE && (it.finishedAt ?: it.createdAt) in startMs until endMs
        }
        val people = PeopleIndex.list(data, ownerName, sinceMillis = startMs, limit = 5)
            .map { it.name to it.conversations }

        return WeekStats(
            from = from,
            to = endDate,
            conversations = conversations.size,
            conversationsByDay = byDay,
            actionsByType = liveActions.groupingBy { it.action.type }.eachCount()
                .toSortedMap(compareBy { it.ordinal }),
            handled = liveActions.count { it.status == ActionStatus.DONE },
            waitingForUser = data.actions.count { it.status == ActionStatus.AWAITING_APPROVAL },
            failed = actions.count { it.status == ActionStatus.FAILED },
            openTodos = openTodos.size,
            overdueTodos = openTodos.count { (it.scheduledAt ?: Long.MAX_VALUE) < nowMillis },
            completedItems = actions.count { it.completed },
            researchDone = research.size,
            busiestDay = byDay.maxByOrNull { it.value }?.key,
            topPeople = people,
        )
    }

    /** Returns null when nothing at all happened in the window. */
    fun build(
        data: AppData,
        owner: OwnerProfile,
        zone: ZoneId,
        nowMillis: Long,
        upcoming: List<UpcomingEvent> = emptyList(),
        endDate: LocalDate = LocalDate.ofInstant(java.time.Instant.ofEpochMilli(nowMillis), zone),
    ): String? {
        val stats = stats(data, endDate, zone, nowMillis, owner.name)
        val startMs = stats.from.atStartOfDay(zone).toInstant().toEpochMilli()
        val endMs = stats.to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val conversations = data.interactions.filter { it.occurredAt in startMs until endMs }.sortedBy { it.occurredAt }
        val research = data.research.filter { (it.finishedAt ?: it.createdAt) in startMs until endMs }
        if (conversations.isEmpty() && stats.handled == 0 && research.isEmpty()) return null

        return buildString {
            appendLine("Week: ${stats.from} to ${stats.to}")
            appendLine("User: ${sanitizeForPrompt(owner.name.ifBlank { "the user" })}")
            appendLine("Output language: ${owner.outputLanguage}")
            appendLine()
            appendLine("## Numbers (computed by the app - use these exact figures)")
            appendLine("- Conversations: ${stats.conversations}")
            stats.busiestDay?.let {
                appendLine("- Busiest day: ${it.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)} ${it} (${stats.conversationsByDay[it]} conversations)")
            }
            appendLine("- Things Aide handled: ${stats.handled}")
            if (stats.actionsByType.isNotEmpty()) {
                appendLine("- By type: " + stats.actionsByType.entries.joinToString(", ") { "${it.key.name.lowercase()} ${it.value}" })
            }
            appendLine("- Waiting for the user's approval right now: ${stats.waitingForUser}")
            appendLine("- Failed actions: ${stats.failed}")
            appendLine("- Open to-dos: ${stats.openTodos} (overdue: ${stats.overdueTodos})")
            appendLine("- Items the user ticked off: ${stats.completedItems}")
            appendLine("- Research reports finished: ${stats.researchDone}")
            if (stats.topPeople.isNotEmpty()) {
                appendLine("- Most-discussed people: " + stats.topPeople.joinToString(", ") { "${sanitizeForPrompt(it.first)} (${it.second})" })
            }
            appendLine()
            appendLine("## Conversations")
            conversations.forEach { i ->
                val title = i.analysis?.title ?: "(not processed)"
                val day = LocalDate.ofInstant(java.time.Instant.ofEpochMilli(i.occurredAt), zone).dayOfWeek
                    .getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
                append("- $day ${LocalTimes.short(i.occurredAt, zone)} | ${sanitizeForPrompt(ContextBuilder.describeSource(i))} | ${sanitizeForPrompt(title)}")
                if (i.status == ProcessingStatus.FAILED || i.status == ProcessingStatus.NEEDS_SETUP) {
                    append(" | PROBLEM: ${sanitizeForPrompt(i.error ?: "unknown")}")
                }
                appendLine()
                i.analysis?.summary?.let { appendLine("  Summary: ${sanitizeForPrompt(it)}") }
            }
            appendLine()
            appendLine("## Still open")
            val waiting = data.actions.filter { it.status == ActionStatus.AWAITING_APPROVAL }
            val todos = data.actions.filter { it.action.type == ActionType.TODO && it.status == ActionStatus.DONE && !it.completed }
            if (waiting.isEmpty() && todos.isEmpty()) appendLine("- nothing")
            waiting.forEach { appendLine("- waiting for approval: ${it.action.type.name.lowercase()} - ${sanitizeForPrompt(it.action.title)}") }
            todos.forEach { t ->
                val due = t.scheduledAt?.let { " (due ${LocalTimes.short(it, zone)})" } ?: ""
                val overdue = if ((t.scheduledAt ?: Long.MAX_VALUE) < nowMillis) " OVERDUE" else ""
                appendLine("- to-do: ${sanitizeForPrompt(t.action.title)}$due$overdue")
            }
            appendLine()
            appendLine("## Research finished")
            val finished = research.filter { it.status == ResearchStatus.DONE }
            if (finished.isEmpty()) appendLine("- none")
            finished.forEach { r ->
                val bottom = r.markdown?.lineSequence()?.firstOrNull { it.contains("Bottom line", ignoreCase = true) }
                    ?.replace("**", "")?.trim().orEmpty()
                appendLine("- ${sanitizeForPrompt(r.topic)}: ${sanitizeForPrompt(bottom)}")
            }
            appendLine()
            appendLine("## Next week's calendar")
            val nextStart = stats.to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val nextEnd = stats.to.plusDays(8).atStartOfDay(zone).toInstant().toEpochMilli()
            val next = upcoming.filter { it.beginMillis in nextStart until nextEnd }.sortedBy { it.beginMillis }
            if (next.isEmpty()) appendLine("- nothing scheduled (or calendar not connected)")
            next.take(30).forEach { e ->
                appendLine("- " + (if (e.allDay) LocalTimes.day(e.beginMillis, zone) + " all day" else LocalTimes.short(e.beginMillis, zone)) +
                    ": ${sanitizeForPrompt(e.title)}")
            }
        }
    }
}
