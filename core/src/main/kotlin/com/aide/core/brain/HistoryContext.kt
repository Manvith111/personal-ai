package com.aide.core.brain

import com.aide.core.model.ActionRecord
import com.aide.core.model.ActionStatus
import com.aide.core.model.ActionType
import com.aide.core.model.AppData
import com.aide.core.model.Interaction
import com.aide.core.search.HistorySearch
import com.aide.core.search.HitKind
import com.aide.core.time.LocalTimes
import java.time.ZoneId

/**
 * Text that comes from phone calls, shared messages or the web is untrusted: it must not be able to
 * close the XML-style record it sits in, or pose as instructions. Angle brackets are swapped for
 * look-alike characters (the text stays readable), and the system prompts say records are data only.
 */
fun sanitizeForPrompt(text: String): String =
    text.replace('<', '‹').replace('>', '›')

/** The records picked for one question, rendered for Claude, plus the ids it is allowed to cite. */
data class HistoryContextResult(val text: String, val recordIds: Set<String>)

/**
 * Chooses and renders the part of the user's history that is relevant to a question. Claude is never
 * given the whole database: only the best-matching conversations (with excerpts of long transcripts),
 * the most recent few (so "what happened today?" works), open items, and saved notes - within a size cap
 * so that a question costs a predictable amount.
 */
object HistoryContext {

    fun build(
        data: AppData,
        question: String,
        owner: OwnerProfile,
        zone: ZoneId,
        nowMillis: Long,
        /** Earlier questions of the same chat; their words help resolve follow-ups like "and his number?". */
        previousQuestions: List<String> = emptyList(),
        maxRelevant: Int = 6,
        alwaysRecent: Int = 3,
        maxChars: Int = 30_000,
    ): HistoryContextResult {
        val searchText = (previousQuestions.takeLast(1) + question).joinToString(" ")
        val q = HistorySearch.queryTokens(searchText)
        val hits = HistorySearch.search(data, searchText, nowMillis, limit = maxRelevant + 4)

        val conversationIds = LinkedHashSet<String>()
        hits.filter { it.kind == HitKind.CONVERSATION }.take(maxRelevant).forEach { conversationIds += it.id }
        data.interactions.sortedByDescending { it.occurredAt }.take(alwaysRecent).forEach { conversationIds += it.id }
        val researchIds = hits.filter { it.kind == HitKind.RESEARCH }.take(3).map { it.id }

        val ids = LinkedHashSet<String>()
        val out = StringBuilder()
        out.appendLine("<history>")
        out.appendLine("Current time: ${LocalTimes.describeForPrompt(nowMillis, zone)} ($zone)")
        out.appendLine("User: ${sanitizeForPrompt(owner.name.ifBlank { "the user" })}")
        out.appendLine("Output language: ${owner.outputLanguage}")
        out.appendLine("Records in storage: ${data.interactions.size} conversations, ${data.research.size} research reports")
        out.appendLine()

        appendOpenItems(out, data, zone, nowMillis)
        // A note shown above names the conversation it came from, so that id may be cited too.
        ids += appendNotes(out, data, zone).filter { data.interaction(it) != null }

        val chosen = conversationIds.mapNotNull { data.interaction(it) }.sortedBy { it.occurredAt }
        for (i in chosen) {
            val block = renderConversation(i, data, q, zone)
            if (out.length + block.length > maxChars) break
            out.append(block)
            ids += i.id
        }
        for (rid in researchIds) {
            val r = data.report(rid) ?: continue
            val bottom = r.markdown?.lineSequence()?.firstOrNull { it.contains("Bottom line", ignoreCase = true) }
                ?.replace("**", "")?.trim().orEmpty()
            val block = "<research id=\"${r.id}\" when=\"${LocalTimes.short(r.finishedAt ?: r.createdAt, zone)}\">\n" +
                "Topic: ${sanitizeForPrompt(r.topic)}\n" +
                (if (bottom.isNotEmpty()) "${sanitizeForPrompt(bottom)}\n" else "") +
                "</research>\n"
            if (out.length + block.length > maxChars) break
            out.append(block)
            ids += r.id
        }
        out.appendLine("</history>")
        return HistoryContextResult(out.toString(), ids)
    }

    private fun renderConversation(i: Interaction, data: AppData, q: List<String>, zone: ZoneId): String = buildString {
        appendLine(
            "<conversation id=\"${i.id}\" when=\"${LocalTimes.short(i.occurredAt, zone)}\" " +
                "source=\"${sanitizeForPrompt(ContextBuilder.describeSource(i))}\">",
        )
        val a = i.analysis
        if (a != null) {
            appendLine("Title: ${sanitizeForPrompt(a.title)}")
            appendLine("Summary: ${sanitizeForPrompt(a.summary)}")
            if (a.keyPoints.isNotEmpty()) {
                appendLine("Key points:")
                a.keyPoints.forEach { appendLine("- ${sanitizeForPrompt(it)}") }
            }
        }
        val actions = data.actionsFor(i.id).filter { it.status != ActionStatus.UNDONE && it.status != ActionStatus.DISMISSED }
        actions.forEach { r ->
            val whenText = r.scheduledAt?.let { " @ ${LocalTimes.short(it, zone)}" } ?: ""
            val extra = listOfNotNull(r.action.location?.let { "at $it" }, r.action.details).joinToString("; ")
            appendLine("Action: ${r.action.type.name.lowercase()} - ${sanitizeForPrompt(r.action.title)}$whenText" +
                (if (extra.isNotEmpty()) " (${sanitizeForPrompt(extra)})" else "") + " [${r.status.name.lowercase()}]")
        }
        val transcript = i.transcript.orEmpty()
        if (transcript.isNotBlank()) {
            val passages = HistorySearch.excerpts(transcript, q, windows = 3, width = 400)
            appendLine(if (passages.size == 1 && transcript.length <= 400) "Transcript:" else "Transcript excerpts:")
            passages.forEach { appendLine("  \"${sanitizeForPrompt(it)}\"") }
        }
        appendLine("</conversation>")
    }

    private fun appendOpenItems(out: StringBuilder, data: AppData, zone: ZoneId, now: Long) {
        val open = data.actions.filter { r ->
            when (r.status) {
                ActionStatus.AWAITING_APPROVAL, ActionStatus.FAILED -> r.action.type != ActionType.RESEARCH
                ActionStatus.DONE -> !r.completed && (r.action.type == ActionType.TODO || r.action.type == ActionType.REMINDER)
                else -> false
            }
        }.sortedBy { it.scheduledAt ?: Long.MAX_VALUE }.take(25)
        out.appendLine("<open_items>")
        if (open.isEmpty()) out.appendLine("(none)")
        open.forEach { r ->
            val due = r.scheduledAt?.let { " (due ${LocalTimes.short(it, zone)})" } ?: ""
            val overdue = if ((r.scheduledAt ?: Long.MAX_VALUE) < now) " OVERDUE" else ""
            out.appendLine("- ${r.action.type.name.lowercase()}: ${sanitizeForPrompt(r.action.title)}$due$overdue [${r.status.name.lowercase()}]")
        }
        out.appendLine("</open_items>")
        out.appendLine()
    }

    /** Writes the saved-notes block; returns the ids of the conversations the notes came from. */
    private fun appendNotes(out: StringBuilder, data: AppData, zone: ZoneId): Set<String> {
        val notes: List<ActionRecord> = data.actions
            .filter { it.action.type == ActionType.NOTE && it.status != ActionStatus.UNDONE && it.status != ActionStatus.DISMISSED }
            .sortedByDescending { it.createdAt }.take(30)
        if (notes.isEmpty()) return emptySet()
        out.appendLine("<saved_notes>")
        notes.forEach { r ->
            val body = listOfNotNull(r.action.title, r.action.details).joinToString(": ")
            out.appendLine("- (${LocalTimes.day(r.createdAt, zone)}, from conversation ${r.interactionId}) ${sanitizeForPrompt(body)}")
        }
        out.appendLine("</saved_notes>")
        out.appendLine()
        return notes.map { it.interactionId }.toSet()
    }
}
