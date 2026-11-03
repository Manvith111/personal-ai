package com.aide.assistant.work

import android.content.Context
import com.aide.assistant.actions.CalendarHelper
import com.aide.assistant.graph
import com.aide.core.brain.ContextBuilder
import com.aide.core.model.ActionStatus
import com.aide.core.model.ActionType
import com.aide.core.model.ProcessingStatus
import com.aide.core.model.ResearchStatus
import com.aide.core.time.LocalTimes
import java.time.LocalDate
import java.time.ZoneId

/** Builds the plain-text log of the day that Claude turns into the daily review. */
object ReviewLog {

    /** Returns null when nothing happened today. */
    fun build(context: Context): String? {
        val graph = context.graph
        val data = graph.store.data
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val start = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val tomorrowStart = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val now = System.currentTimeMillis()

        val interactions = data.interactions.filter { it.createdAt >= start }
        val actions = data.actions.filter { it.updatedAt >= start }
        val research = data.research.filter { (it.finishedAt ?: it.createdAt) >= start }
        if (interactions.isEmpty() && actions.isEmpty() && research.isEmpty()) return null

        val waiting = data.actions.filter { it.status == ActionStatus.AWAITING_APPROVAL }
        val openTodos = data.actions.filter {
            it.action.type == ActionType.TODO && it.status == ActionStatus.DONE && !it.completed
        }
        val tomorrow = CalendarHelper.upcoming(context, tomorrowStart, tomorrowStart + 24 * 60 * 60 * 1000L)

        return buildString {
            header(context, today.toString())
            appendLine("## Conversations today (${interactions.size})")
            interactions.sortedBy { it.occurredAt }.forEach { i ->
                val title = i.analysis?.title ?: "(not processed)"
                append("- ${LocalTimes.short(i.occurredAt, zone)} | ${ContextBuilder.describeSource(i)} | $title")
                if (i.status == ProcessingStatus.FAILED || i.status == ProcessingStatus.NEEDS_SETUP) append(" | PROBLEM: ${i.error}")
                appendLine()
                i.analysis?.summary?.let { appendLine("  Summary: $it") }
            }
            appendLine()
            appendLine("## Actions taken or updated today (${actions.size})")
            actions.forEach { a ->
                val time = a.scheduledAt?.let { " @ ${LocalTimes.short(it, zone)}" } ?: ""
                appendLine("- [${a.status}] ${a.action.type.name.lowercase()}: ${a.action.title}$time - ${a.resultMessage ?: ""}")
            }
            appendLine()
            appendLine("## Research")
            if (research.isEmpty()) appendLine("- none")
            research.forEach { r ->
                val bottom = r.markdown?.lineSequence()?.firstOrNull { it.contains("Bottom line") }?.replace("**", "") ?: ""
                appendLine("- [${r.status}] ${r.topic}: $bottom" + if (r.status == ResearchStatus.FAILED) " (error: ${r.error})" else "")
            }
            appendLine()
            appendLine("## Waiting for the user's approval")
            if (waiting.isEmpty()) appendLine("- none")
            waiting.forEach { appendLine("- ${it.action.type.name.lowercase()}: ${it.action.title}") }
            appendLine()
            appendLine("## Open to-dos")
            if (openTodos.isEmpty()) appendLine("- none")
            openTodos.forEach { t ->
                val due = t.scheduledAt?.let { " (due ${LocalTimes.short(it, zone)})" } ?: ""
                val overdue = if ((t.scheduledAt ?: Long.MAX_VALUE) < now) " OVERDUE" else ""
                appendLine("- ${t.action.title}$due$overdue")
            }
            appendLine()
            appendLine("## Tomorrow's calendar")
            if (tomorrow.isEmpty()) appendLine("- nothing scheduled (or calendar not connected)")
            tomorrow.forEach { e ->
                appendLine("- " + (if (e.allDay) "all day" else LocalTimes.short(e.beginMillis, zone)) + ": ${e.title}")
            }
        }
    }

    fun emptyDay(context: Context): String = buildString {
        header(context, LocalDate.now().toString())
        appendLine("No conversations, actions or research were recorded today.")
        val zone = ZoneId.systemDefault()
        val tomorrowStart = LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val tomorrow = CalendarHelper.upcoming(context, tomorrowStart, tomorrowStart + 24 * 60 * 60 * 1000L)
        appendLine("## Tomorrow's calendar")
        if (tomorrow.isEmpty()) appendLine("- nothing scheduled (or calendar not connected)")
        tomorrow.forEach { appendLine("- ${LocalTimes.short(it.beginMillis, zone)}: ${it.title}") }
    }

    private fun StringBuilder.header(context: Context, date: String) {
        val s = context.graph.settings
        appendLine("Date: $date")
        appendLine("User: ${s.userName.ifBlank { "the user" }}")
        appendLine("Output language: ${s.outputLanguage}")
        appendLine()
    }
}
