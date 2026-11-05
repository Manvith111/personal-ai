package com.aide.assistant.work

import android.content.Context
import android.net.Uri
import com.aide.assistant.Notifier
import com.aide.assistant.actions.ActionExecutor
import com.aide.assistant.actions.CalendarHelper
import com.aide.assistant.audio.AudioPrep
import com.aide.assistant.graph
import com.aide.core.brain.AnalysisInput
import com.aide.core.brain.BrainException
import com.aide.core.brain.ContextBuilder
import com.aide.core.brain.OwnerProfile
import com.aide.core.model.ActionRecord
import com.aide.core.model.ActionStatus
import com.aide.core.model.ActionType
import com.aide.core.model.Interaction
import com.aide.core.model.ProcessingStatus
import com.aide.core.model.SourceType
import com.aide.core.stt.TranscriptionException
import com.aide.core.time.LocalTimes
import java.io.File
import java.time.ZoneId

/** The outcome of one processing attempt, mapped onto WorkManager results by the worker. */
enum class Outcome { SUCCESS, RETRY, FAILED }

/**
 * Turns an interaction into understanding and action:
 *   audio -> transcript (speech-to-text) -> analysis (Claude) -> actions (calendar, reminders, research...)
 */
class Pipeline(private val context: Context) {
    private val graph = context.graph
    private val store = graph.store

    fun process(interactionId: String, attempt: Int): Outcome {
        var interaction = store.data.interaction(interactionId) ?: return Outcome.FAILED
        if (interaction.status == ProcessingStatus.DONE) return Outcome.SUCCESS

        // 1) Speech to text, if this is audio we have not transcribed yet.
        if (interaction.transcript.isNullOrBlank()) {
            val audio = interaction.audioUri ?: return fail(interaction, "Nothing to process.")
            val transcriber = graph.transcriber()
                ?: return needsSetup(interaction, "Add a speech-to-text key in Settings so I can listen to recordings.")
            interaction = save(interaction.copy(status = ProcessingStatus.TRANSCRIBING, error = null))
            val workDir = File(context.cacheDir, "stt-${interaction.id}")
            try {
                val parts = AudioPrep.prepare(context, Uri.parse(audio), interaction.audioName ?: "audio.m4a", workDir)
                val hint = listOfNotNull(graph.settings.userName.takeIf { it.isNotBlank() }, interaction.contactName)
                    .joinToString(", ").takeIf { it.isNotBlank() }?.let { "Conversation between $it." }
                val text = parts.joinToString("\n") { transcriber.transcribe(it.file, it.uploadName, hint) }.trim()
                if (text.isBlank()) return fail(interaction, "No speech was found in the recording.")
                interaction = save(interaction.copy(transcript = text))
            } catch (e: TranscriptionException) {
                return if (e.retryable && attempt < MAX_ATTEMPTS) retry(interaction, e.message) else fail(interaction, e.message)
            } catch (e: Exception) {
                return fail(interaction, "Could not read the recording: ${e.message}")
            } finally {
                workDir.deleteRecursively()
            }
        }

        // 2) Understand it.
        val brain = graph.brain() ?: return needsSetup(interaction, "Add your Anthropic API key in Settings.")
        interaction = save(interaction.copy(status = ProcessingStatus.ANALYZING, error = null))
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val upcoming = CalendarHelper.upcoming(context, now - 60 * 60 * 1000L, now + 14 * 24 * 60 * 60 * 1000L)
        val settings = graph.settings
        val contextText = ContextBuilder.analysisContext(
            interaction,
            OwnerProfile(settings.userName, settings.outputLanguage, settings.region),
            zone, now, upcoming,
        )
        val result = try {
            brain.analyze(AnalysisInput(contextText, interaction.transcript!!))
        } catch (e: BrainException) {
            return if (e.retryable && attempt < MAX_ATTEMPTS) retry(interaction, e.message) else fail(interaction, e.message)
        }

        store.update { it.recordUsage(LocalTimes.isoDate(now), result.model, result.usage.inputTokens, result.usage.outputTokens) }

        // 3) Act on it.
        interaction = save(
            interaction.copy(analysis = result.analysis, modelUsed = result.model, status = ProcessingStatus.DONE, error = null),
        )
        val records = ActionExecutor(context).executeAll(interaction, result.analysis.actions)
        notifyDone(interaction, records)
        return Outcome.SUCCESS
    }

    private fun save(i: Interaction): Interaction {
        store.update { it.upsert(i) }
        return i
    }

    private fun fail(i: Interaction, message: String?): Outcome {
        save(i.copy(status = ProcessingStatus.FAILED, error = message ?: "Unknown error"))
        Notifier.update(context, i.id.hashCode(), "Couldn't process ${label(i)}", message ?: "Unknown error", "interaction:${i.id}")
        return Outcome.FAILED
    }

    private fun retry(i: Interaction, message: String?): Outcome {
        save(i.copy(status = ProcessingStatus.QUEUED, error = message))
        return Outcome.RETRY
    }

    private fun needsSetup(i: Interaction, message: String): Outcome {
        save(i.copy(status = ProcessingStatus.NEEDS_SETUP, error = message))
        Notifier.update(context, 7001, "Aide needs a quick setup", message, "settings")
        return Outcome.FAILED
    }

    private fun notifyDone(i: Interaction, records: List<ActionRecord>) {
        val analysis = i.analysis ?: return
        // Typed / shared instructions are handled while you are looking at the app; only notify for
        // things that happened in the background, or when something needs your attention.
        val needsYou = records.any { it.status == ActionStatus.AWAITING_APPROVAL || it.status == ActionStatus.FAILED }
        if (i.source == SourceType.TYPED && !needsYou) return
        val lines = records.map { r ->
            val icon = when (r.action.type) {
                ActionType.CALENDAR_EVENT -> "📅"
                ActionType.REMINDER -> "🔔"
                ActionType.ALARM -> "⏰"
                ActionType.TODO -> "☑️"
                ActionType.RESEARCH -> "🔎"
                ActionType.MESSAGE_DRAFT -> "💬"
                ActionType.NOTE -> "📝"
            }
            val status = when (r.status) {
                ActionStatus.AWAITING_APPROVAL -> " (needs your OK)"
                ActionStatus.FAILED -> " (failed)"
                else -> ""
            }
            "$icon ${r.action.title}$status"
        }
        val text = if (lines.isEmpty()) analysis.summary else lines.joinToString("\n")
        Notifier.update(context, i.id.hashCode(), analysis.title, text, "interaction:${i.id}")
    }

    private fun label(i: Interaction) = when (i.source) {
        SourceType.CALL -> "call" + (i.contactName?.let { " with $it" } ?: "")
        SourceType.MEETING -> "meeting recording"
        else -> "your note"
    }

    companion object { const val MAX_ATTEMPTS = 5 }
}
