package com.aide.core.brain

import com.aide.core.model.Interaction
import com.aide.core.model.SourceType
import com.aide.core.time.LocalTimes
import java.time.ZoneId

data class UpcomingEvent(val title: String, val beginMillis: Long, val endMillis: Long, val allDay: Boolean)

data class OwnerProfile(
    val name: String,
    val outputLanguage: String,
    val region: String,
)

/** Renders the context block that accompanies every conversation sent to Claude. */
object ContextBuilder {

    fun analysisContext(
        interaction: Interaction,
        owner: OwnerProfile,
        zone: ZoneId,
        nowMillis: Long,
        upcoming: List<UpcomingEvent>,
    ): String = buildString {
        appendLine("Reference time (when this conversation happened): " +
            "${LocalTimes.describeForPrompt(interaction.occurredAt, zone)} ($zone)")
        appendLine("Current time: ${LocalTimes.describeForPrompt(nowMillis, zone)} ($zone)")
        appendLine("Source: ${describeSource(interaction)}")
        appendLine("Phone owner: ${owner.name.ifBlank { "the user" }}")
        appendLine("User region: ${owner.region}")
        appendLine("Output language: ${owner.outputLanguage}")
        if (upcoming.isEmpty()) {
            appendLine("User's upcoming calendar (next 14 days): nothing found or calendar not connected")
        } else {
            appendLine("User's upcoming calendar (next 14 days):")
            upcoming.take(40).forEach { e ->
                val time = if (e.allDay) LocalTimes.day(e.beginMillis, zone) + " (all day)"
                else LocalTimes.short(e.beginMillis, zone)
                appendLine("- $time: ${e.title}")
            }
        }
    }

    fun describeSource(i: Interaction): String {
        val who = listOfNotNull(i.contactName, i.contactNumber?.let { "($it)" }).joinToString(" ")
            .ifBlank { "an unknown number" }
        val duration = i.durationSec?.takeIf { it > 0 }?.let { ", duration ${it / 60}m ${it % 60}s" } ?: ""
        return when (i.source) {
            SourceType.CALL -> "transcript of a ${i.callDirection ?: ""} phone call with $who$duration"
                .replace("  ", " ")
            SourceType.VOICE_NOTE -> if (i.contactName != null)
                "voice note the user dictated to you right after a call with $who"
            else "voice note the user dictated to you"
            SourceType.MEETING -> "recording of an in-person conversation/meeting$duration"
            SourceType.TYPED -> "instruction the user typed to you"
            SourceType.SHARED_TEXT -> "text the user shared with you from another app" +
                (i.audioName?.let { " ($it)" } ?: "")
            SourceType.SHARED_AUDIO -> "transcript of an audio file the user shared with you" +
                (i.audioName?.let { " ($it)" } ?: "")
        }
    }
}
