package com.aide.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Where a piece of input came from. */
@Serializable
enum class SourceType {
    CALL,          // a phone call recording picked up automatically
    VOICE_NOTE,    // the user spoke to the assistant (incl. after-call debriefs)
    MEETING,       // an in-person conversation recorded with the in-app recorder
    TYPED,         // a typed instruction
    SHARED_TEXT,   // text shared into the app from another app
    SHARED_AUDIO,  // an audio file shared/imported into the app (e.g. a WhatsApp voice note)
}

@Serializable
enum class ProcessingStatus { QUEUED, TRANSCRIBING, ANALYZING, DONE, FAILED, NEEDS_SETUP }

/** One conversation / instruction the assistant has seen. */
@Serializable
data class Interaction(
    val id: String,
    val source: SourceType,
    /** When the conversation happened (epoch millis). "Today at 5pm" is resolved against this. */
    val occurredAt: Long,
    val createdAt: Long,
    val contactName: String? = null,
    val contactNumber: String? = null,
    /** "incoming" / "outgoing" for calls. */
    val callDirection: String? = null,
    val durationSec: Long? = null,
    /** content:// or file:// URI of the audio, if any. */
    val audioUri: String? = null,
    val audioName: String? = null,
    val transcript: String? = null,
    val status: ProcessingStatus = ProcessingStatus.QUEUED,
    val error: String? = null,
    val analysis: Analysis? = null,
    val modelUsed: String? = null,
)

/** What the assistant understood from an interaction. Field names match the JSON schema sent to Claude. */
@Serializable
data class Analysis(
    val title: String,
    val summary: String,
    @SerialName("key_points") val keyPoints: List<String> = emptyList(),
    @SerialName("people_mentioned") val peopleMentioned: List<String> = emptyList(),
    val actions: List<ProposedAction> = emptyList(),
)

@Serializable
enum class ActionType {
    @SerialName("calendar_event") CALENDAR_EVENT,
    @SerialName("reminder") REMINDER,
    @SerialName("todo") TODO,
    @SerialName("research") RESEARCH,
    @SerialName("message_draft") MESSAGE_DRAFT,
    @SerialName("note") NOTE,
    @SerialName("alarm") ALARM,
}

/** One thing the assistant decided to do. Field names match the JSON schema sent to Claude. */
@Serializable
data class ProposedAction(
    val type: ActionType,
    val title: String,
    val details: String? = null,
    /** Local date-time "yyyy-MM-ddTHH:mm" (or a date "yyyy-MM-dd"), in the phone's time zone. */
    @SerialName("start_local") val startLocal: String? = null,
    @SerialName("end_local") val endLocal: String? = null,
    @SerialName("all_day") val allDay: Boolean = false,
    val location: String? = null,
    val people: List<String> = emptyList(),
    val recipient: String? = null,
    @SerialName("message_body") val messageBody: String? = null,
    @SerialName("research_question") val researchQuestion: String? = null,
    /** Short quote from the conversation that justifies the action. */
    val evidence: String? = null,
    val confidence: Double = 0.8,
)

@Serializable
enum class ActionStatus { DONE, AWAITING_APPROVAL, IN_PROGRESS, FAILED, UNDONE, DISMISSED }

/** The record of an action the assistant executed (or is waiting to execute). */
@Serializable
data class ActionRecord(
    val id: String,
    val interactionId: String,
    val action: ProposedAction,
    val status: ActionStatus,
    val resultMessage: String? = null,
    val calendarEventId: Long? = null,
    /** Event start / reminder time (epoch millis), if the action is time-based. */
    val scheduledAt: Long? = null,
    val researchId: String? = null,
    /** For to-dos and reminders: the user ticked it off. */
    val completed: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
enum class ResearchStatus { QUEUED, RUNNING, DONE, FAILED }

@Serializable
data class ResearchSource(val title: String, val url: String)

@Serializable
data class ResearchReport(
    val id: String,
    val interactionId: String? = null,
    val actionId: String? = null,
    val topic: String,
    val question: String,
    val context: String? = null,
    val status: ResearchStatus = ResearchStatus.QUEUED,
    val markdown: String? = null,
    val sources: List<ResearchSource> = emptyList(),
    val createdAt: Long,
    val finishedAt: Long? = null,
    val error: String? = null,
    val modelUsed: String? = null,
)

@Serializable
data class DailyReview(
    val id: String,
    /** yyyy-MM-dd */
    val date: String,
    val markdown: String,
    val createdAt: Long,
)

/** Everything the app stores, persisted as one JSON file. */
@Serializable
data class AppData(
    val interactions: List<Interaction> = emptyList(),
    val actions: List<ActionRecord> = emptyList(),
    val research: List<ResearchReport> = emptyList(),
    val reviews: List<DailyReview> = emptyList(),
    /** Audio URIs already turned into interactions (so recordings are never processed twice). */
    val processedAudio: List<String> = emptyList(),
    /** Call-log ids we already asked the user about (after-call check-in). */
    val checkedInCalls: List<String> = emptyList(),
) {
    fun interaction(id: String) = interactions.firstOrNull { it.id == id }
    fun action(id: String) = actions.firstOrNull { it.id == id }
    fun report(id: String) = research.firstOrNull { it.id == id }
    fun actionsFor(interactionId: String) = actions.filter { it.interactionId == interactionId }

    fun upsert(i: Interaction): AppData =
        copy(interactions = listOf(i) + interactions.filterNot { it.id == i.id })

    fun upsert(a: ActionRecord): AppData =
        if (actions.any { it.id == a.id }) copy(actions = actions.map { if (it.id == a.id) a else it })
        else copy(actions = actions + a)

    fun upsert(r: ResearchReport): AppData =
        copy(research = listOf(r) + research.filterNot { it.id == r.id })

    fun upsert(r: DailyReview): AppData =
        copy(reviews = listOf(r) + reviews.filterNot { it.date == r.date })

    fun markAudioProcessed(uri: String): AppData =
        copy(processedAudio = (processedAudio + uri).distinct().takeLast(MAX_REMEMBERED))

    fun markCallCheckedIn(callId: String): AppData =
        copy(checkedInCalls = (checkedInCalls + callId).distinct().takeLast(MAX_REMEMBERED))

    fun removeInteraction(id: String): AppData = copy(
        interactions = interactions.filterNot { it.id == id },
        actions = actions.filterNot { it.interactionId == id },
    )

    companion object { const val MAX_REMEMBERED = 1000 }
}
