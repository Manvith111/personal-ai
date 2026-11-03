package com.aide.assistant

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.aide.assistant.actions.ActionExecutor
import com.aide.assistant.actions.ReminderScheduler
import com.aide.core.model.ActionStatus
import com.aide.core.model.Interaction
import com.aide.core.model.ProcessingStatus
import com.aide.core.model.ResearchReport
import com.aide.core.model.ResearchStatus
import com.aide.core.model.SourceType
import java.io.File
import java.util.UUID

/** Entry points shared by the UI, the share sheet, notifications and the recorder. */
object AideController {

    /** Something the user typed, dictated or shared. */
    fun submitText(
        context: Context,
        text: String,
        source: SourceType,
        occurredAt: Long = System.currentTimeMillis(),
        contactName: String? = null,
        contactNumber: String? = null,
        durationSec: Long? = null,
        label: String? = null,
    ): String {
        val now = System.currentTimeMillis()
        val interaction = Interaction(
            id = UUID.randomUUID().toString(),
            source = source,
            occurredAt = occurredAt,
            createdAt = now,
            contactName = contactName,
            contactNumber = contactNumber,
            durationSec = durationSec,
            audioName = label,
            transcript = text.trim(),
        )
        context.graph.store.update { it.upsert(interaction) }
        Scheduler.process(context, interaction.id)
        return interaction.id
    }

    /** An audio file that already lives in the app's private storage (meeting recordings). */
    fun submitAudioFile(context: Context, file: File, source: SourceType, occurredAt: Long, durationSec: Long?): String {
        val interaction = Interaction(
            id = UUID.randomUUID().toString(),
            source = source,
            occurredAt = occurredAt,
            createdAt = System.currentTimeMillis(),
            durationSec = durationSec,
            audioUri = Uri.fromFile(file).toString(),
            audioName = file.name,
        )
        context.graph.store.update { it.upsert(interaction).markAudioProcessed(interaction.audioUri!!) }
        Scheduler.process(context, interaction.id)
        return interaction.id
    }

    /**
     * Audio shared from another app or picked from storage. Access to such URIs is temporary, so the
     * file is copied into private storage first.
     */
    fun submitSharedAudio(context: Context, uri: Uri): String {
        val name = displayName(context, uri) ?: "shared-audio.m4a"
        val dir = File(context.filesDir, "shared").apply { mkdirs() }
        val safeName = name.replace(Regex("[^\\w.\\-]+"), "_")
        val target = File(dir, "${System.currentTimeMillis()}-$safeName")
        val input = context.contentResolver.openInputStream(uri) ?: error("Cannot read the shared file")
        input.use { stream -> target.outputStream().use { stream.copyTo(it) } }
        val interaction = Interaction(
            id = UUID.randomUUID().toString(),
            source = SourceType.SHARED_AUDIO,
            occurredAt = System.currentTimeMillis(),
            createdAt = System.currentTimeMillis(),
            audioUri = Uri.fromFile(target).toString(),
            audioName = name,
        )
        context.graph.store.update { it.upsert(interaction) }
        Scheduler.process(context, interaction.id)
        return interaction.id
    }

    fun reprocess(context: Context, interactionId: String) {
        val store = context.graph.store
        val i = store.data.interaction(interactionId) ?: return
        // Undo what the previous attempt did, so re-running does not duplicate events/reminders.
        val executor = ActionExecutor(context)
        store.data.actionsFor(interactionId)
            .filter { it.status == ActionStatus.DONE || it.status == ActionStatus.IN_PROGRESS }
            .forEach { executor.undo(it.id) }
        store.update { d ->
            d.copy(actions = d.actions.filterNot { it.interactionId == interactionId })
                .upsert(i.copy(status = ProcessingStatus.QUEUED, analysis = null, error = null))
        }
        Scheduler.process(context, interactionId)
    }

    fun delete(context: Context, interactionId: String) {
        val store = context.graph.store
        store.data.actionsFor(interactionId).forEach { ReminderScheduler.cancel(context, it.id) }
        val audio = store.data.interaction(interactionId)?.audioUri
        // Only delete audio files Aide owns (meeting recordings, shared copies) - never the dialer's.
        if (audio != null && audio.startsWith("file:")) {
            Uri.parse(audio).path?.let { File(it).delete() }
        }
        store.update { it.removeInteraction(interactionId) }
    }

    fun startResearch(context: Context, question: String): String {
        val report = ResearchReport(
            id = UUID.randomUUID().toString(),
            topic = question.trim().take(80),
            question = question.trim(),
            status = ResearchStatus.QUEUED,
            createdAt = System.currentTimeMillis(),
        )
        context.graph.store.update { it.upsert(report) }
        Scheduler.research(context, report.id)
        return report.id
    }

    fun retryResearch(context: Context, reportId: String) {
        val store = context.graph.store
        val r = store.data.report(reportId) ?: return
        store.update { it.upsert(r.copy(status = ResearchStatus.QUEUED, error = null)) }
        Scheduler.research(context, reportId)
    }

    fun setCompleted(context: Context, actionId: String, completed: Boolean) {
        val store = context.graph.store
        val a = store.data.action(actionId) ?: return
        if (completed) ReminderScheduler.cancel(context, actionId)
        store.update { it.upsert(a.copy(completed = completed, updatedAt = System.currentTimeMillis())) }
    }

    fun dismiss(context: Context, actionId: String) {
        val store = context.graph.store
        val a = store.data.action(actionId) ?: return
        store.update { it.upsert(a.copy(status = ActionStatus.DISMISSED, resultMessage = "Dismissed", updatedAt = System.currentTimeMillis())) }
    }

    fun markMessageSent(context: Context, actionId: String) {
        val store = context.graph.store
        val a = store.data.action(actionId) ?: return
        store.update {
            it.upsert(a.copy(status = ActionStatus.DONE, completed = true, resultMessage = "Opened for sending", updatedAt = System.currentTimeMillis()))
        }
    }

    private fun displayName(context: Context, uri: Uri): String? {
        val fromProvider = try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (e: Exception) {
            null
        }
        return fromProvider ?: uri.lastPathSegment
    }
}
