package com.aide.core.people

import com.aide.core.brain.ContextBuilder
import com.aide.core.brain.OwnerProfile
import com.aide.core.brain.UpcomingEvent
import com.aide.core.brain.sanitizeForPrompt
import com.aide.core.model.ActionRecord
import com.aide.core.model.ActionStatus
import com.aide.core.model.ActionType
import com.aide.core.model.AppData
import com.aide.core.model.Interaction
import com.aide.core.time.LocalTimes
import java.time.ZoneId

/** Someone the user has talked to or about, with how much history there is. */
data class PersonSummary(
    val name: String,
    val conversations: Int,
    val lastMillis: Long,
    /** Things still open that involve this person (to-dos, reminders, drafts waiting for the user). */
    val openItems: Int,
)

/**
 * Builds a picture of the people in the user's life from the conversations Aide has seen: who they talk
 * to most, what is still open with each of them, and the briefing material for "prep me for my call
 * with X". Names come straight from the phone's contacts (the call's contact name) and from what Claude
 * extracted as people mentioned, so matching is deliberately forgiving about first names.
 */
object PeopleIndex {

    /** Lower-cased, whitespace-collapsed form used to compare names. */
    fun normalize(name: String): String =
        name.trim().lowercase().replace(Regex("\\s+"), " ")

    /**
     * Same person? Equal names, or a single first name matching the first name of a longer one
     * ("Rahul" ~ "Rahul Sharma"). Two different full names never match.
     */
    fun samePerson(a: String, b: String): Boolean {
        val x = normalize(a)
        val y = normalize(b)
        if (x.isEmpty() || y.isEmpty()) return false
        if (x == y) return true
        val xp = x.split(' ')
        val yp = y.split(' ')
        return when {
            xp.size == 1 && yp.size > 1 -> xp[0] == yp[0]
            yp.size == 1 && xp.size > 1 -> yp[0] == xp[0]
            else -> false
        }
    }

    /** True for contact "names" that are really phone numbers (the app falls back to the number). */
    fun looksLikeNumber(s: String): Boolean {
        val digits = s.count { it.isDigit() }
        return digits >= 6 && s.all { it.isDigit() || it in "+-() ." }
    }

    /** Does [interaction] involve [person] - as the contact, a mentioned person, or in one of its actions? */
    fun involves(interaction: Interaction, data: AppData, person: String): Boolean {
        if (interaction.contactName?.let { samePerson(it, person) } == true) return true
        val a = interaction.analysis
        if (a?.peopleMentioned?.any { samePerson(it, person) } == true) return true
        return data.actionsFor(interaction.id).any { r ->
            r.action.people.any { samePerson(it, person) } || r.action.recipient?.let { samePerson(it, person) } == true
        }
    }

    fun conversationsWith(data: AppData, person: String): List<Interaction> =
        data.interactions.filter { involves(it, data, person) }.sortedByDescending { it.occurredAt }

    /** Open items that involve [person]: awaiting the user's OK, failed, or open to-dos/reminders. */
    fun openItemsFor(data: AppData, person: String): List<ActionRecord> {
        val convIds = conversationsWith(data, person).map { it.id }.toSet()
        return data.actions.filter { r ->
            isOpen(r) && (
                r.action.people.any { samePerson(it, person) } ||
                    r.action.recipient?.let { samePerson(it, person) } == true ||
                    (r.interactionId in convIds && r.action.people.isEmpty() && r.action.recipient == null)
                )
        }
    }

    private fun isOpen(r: ActionRecord): Boolean = when (r.status) {
        ActionStatus.AWAITING_APPROVAL, ActionStatus.FAILED -> r.action.type != ActionType.RESEARCH
        ActionStatus.DONE -> !r.completed && (r.action.type == ActionType.TODO || r.action.type == ActionType.REMINDER)
        else -> false
    }

    /**
     * Everyone who appears in the data, most recently talked-to first.
     * @param ownerName the phone owner, left out of the list
     * @param sinceMillis only count conversations that happened at or after this time
     */
    fun list(
        data: AppData,
        ownerName: String = "",
        sinceMillis: Long = 0,
        limit: Int = 50,
    ): List<PersonSummary> {
        // Collect every name with the conversations it appears in.
        val names = LinkedHashMap<String, MutableSet<String>>() // display name -> interaction ids
        fun add(name: String?, interactionId: String) {
            val n = name?.trim().orEmpty()
            if (n.isEmpty() || looksLikeNumber(n)) return
            if (ownerName.isNotBlank() && samePerson(n, ownerName)) return
            names.getOrPut(n) { linkedSetOf() } += interactionId
        }
        val recent = data.interactions.filter { it.occurredAt >= sinceMillis }
        for (i in recent) {
            add(i.contactName, i.id)
            i.analysis?.peopleMentioned?.forEach { add(it, i.id) }
            data.actionsFor(i.id).forEach { r ->
                r.action.people.forEach { add(it, i.id) }
                add(r.action.recipient, i.id)
            }
        }

        // Merge spellings of the same person under the longest (most complete) name.
        val clusters = ArrayList<Pair<String, MutableSet<String>>>()
        for ((name, ids) in names.entries.sortedByDescending { it.key.length }) {
            val existing = clusters.firstOrNull { samePerson(it.first, name) }
            if (existing != null) existing.second += ids else clusters += name to ids.toMutableSet()
        }

        val byId = recent.associateBy { it.id }
        return clusters.map { (name, ids) ->
            PersonSummary(
                name = name,
                conversations = ids.size,
                lastMillis = ids.maxOf { byId[it]?.occurredAt ?: 0L },
                openItems = openItemsFor(data, name).size,
            )
        }.sortedWith(compareByDescending<PersonSummary> { it.lastMillis }.thenByDescending { it.conversations })
            .take(limit)
    }

    /**
     * The material Claude needs to brief the user before a call or meeting with [person]: the history
     * with them, what is open, what was noted, and any upcoming calendar entries that mention them.
     */
    fun briefingContext(
        data: AppData,
        person: String,
        owner: OwnerProfile,
        zone: ZoneId,
        nowMillis: Long,
        upcoming: List<UpcomingEvent> = emptyList(),
        maxConversations: Int = 8,
    ): String = buildString {
        appendLine("Current time: ${LocalTimes.describeForPrompt(nowMillis, zone)} ($zone)")
        appendLine("Phone owner: ${owner.name.ifBlank { "the user" }}")
        appendLine("Output language: ${owner.outputLanguage}")
        appendLine("Person to brief the owner about: ${sanitizeForPrompt(person)}")
        appendLine()

        val conversations = conversationsWith(data, person)
        appendLine("<history total_conversations=\"${conversations.size}\">")
        if (conversations.isEmpty()) appendLine("(no recorded conversations with this person)")
        conversations.take(maxConversations).reversed().forEach { i ->
            appendLine("<conversation when=\"${LocalTimes.short(i.occurredAt, zone)}\" source=\"${sanitizeForPrompt(ContextBuilder.describeSource(i))}\">")
            val a = i.analysis
            if (a != null) {
                appendLine("Title: ${sanitizeForPrompt(a.title)}")
                appendLine("Summary: ${sanitizeForPrompt(a.summary)}")
                if (a.keyPoints.isNotEmpty()) {
                    appendLine("Key points:")
                    a.keyPoints.forEach { appendLine("- ${sanitizeForPrompt(it)}") }
                }
            } else {
                appendLine("(not analysed yet)")
            }
            data.actionsFor(i.id).filter { it.status != ActionStatus.UNDONE && it.status != ActionStatus.DISMISSED }
                .forEach { r ->
                    val whenText = r.scheduledAt?.let { " @ ${LocalTimes.short(it, zone)}" } ?: ""
                    appendLine("Action: ${r.action.type.name.lowercase()} - ${sanitizeForPrompt(r.action.title)}$whenText [${r.status.name.lowercase()}]")
                }
            appendLine("</conversation>")
        }
        appendLine("</history>")
        appendLine()

        val open = openItemsFor(data, person)
        appendLine("<open_items>")
        if (open.isEmpty()) appendLine("(none)")
        open.forEach { r ->
            val whenText = r.scheduledAt?.let { " (due ${LocalTimes.short(it, zone)})" } ?: ""
            val overdue = if ((r.scheduledAt ?: Long.MAX_VALUE) < nowMillis) " OVERDUE" else ""
            appendLine("- ${r.action.type.name.lowercase()}: ${sanitizeForPrompt(r.action.title)}$whenText$overdue [${r.status.name.lowercase()}]")
        }
        appendLine("</open_items>")

        val first = normalize(person).substringBefore(' ')
        val calendar = upcoming.filter { it.title.lowercase().contains(first) }
        if (calendar.isNotEmpty()) {
            appendLine()
            appendLine("<upcoming_calendar>")
            calendar.take(10).forEach { e ->
                val time = if (e.allDay) LocalTimes.day(e.beginMillis, zone) + " (all day)" else LocalTimes.short(e.beginMillis, zone)
                appendLine("- $time: ${sanitizeForPrompt(e.title)}")
            }
            appendLine("</upcoming_calendar>")
        }
    }
}
