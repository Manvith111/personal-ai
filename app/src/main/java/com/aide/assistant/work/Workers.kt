package com.aide.assistant.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.aide.assistant.Notifier
import com.aide.assistant.Scheduler
import com.aide.assistant.calls.CallLogHelper
import com.aide.assistant.calls.RecordingFinder
import com.aide.assistant.graph
import com.aide.core.brain.BrainException
import com.aide.core.model.ActionStatus
import com.aide.core.model.DailyReview
import com.aide.core.model.Interaction
import com.aide.core.model.ResearchStatus
import com.aide.core.model.SourceType
import com.aide.core.time.LocalTimes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/** Transcribes + analyses one interaction and executes its actions. */
class ProcessWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val id = inputData.getString(KEY_ID) ?: return@withContext Result.failure()
        when (Pipeline(applicationContext).process(id, runAttemptCount)) {
            Outcome.SUCCESS -> Result.success()
            Outcome.RETRY -> Result.retry()
            Outcome.FAILED -> Result.failure()
        }
    }

    companion object { const val KEY_ID = "interaction_id" }
}

/** Researches a question on the web with Claude and stores the report. */
class ResearchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        val store = ctx.graph.store
        val id = inputData.getString(KEY_ID) ?: return@withContext Result.failure()
        val report = store.data.report(id) ?: return@withContext Result.failure()
        if (report.status == ResearchStatus.DONE) return@withContext Result.success()
        val brain = ctx.graph.brain() ?: run {
            store.update { it.upsert(report.copy(status = ResearchStatus.FAILED, error = "Add your Anthropic API key in Settings.")) }
            return@withContext Result.failure()
        }
        store.update { it.upsert(report.copy(status = ResearchStatus.RUNNING, error = null)) }
        val settings = ctx.graph.settings
        val researchContext = buildString {
            appendLine("Today: ${LocalTimes.describeForPrompt(System.currentTimeMillis(), java.time.ZoneId.systemDefault())}")
            appendLine("User region: ${settings.region}")
            appendLine("Write the answer in: ${settings.outputLanguage}")
            report.context?.let { appendLine("Background from the user's conversation: $it") }
        }
        try {
            val result = brain.research(report.question, researchContext)
            val done = report.copy(
                status = ResearchStatus.DONE, markdown = result.markdown, sources = result.sources,
                finishedAt = System.currentTimeMillis(), modelUsed = result.model, error = null,
            )
            store.update { data ->
                var d = data.upsert(done)
                report.actionId?.let { aid ->
                    d.action(aid)?.let { a ->
                        d = d.upsert(a.copy(status = ActionStatus.DONE, resultMessage = "Research ready", updatedAt = System.currentTimeMillis()))
                    }
                }
                d
            }
            val firstLine = result.markdown.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.startsWith("**Bottom line") }
                ?.replace("**", "")
                ?: "Tap to read the insights."
            Notifier.update(ctx, id.hashCode(), "🔎 Research ready: ${report.topic}", firstLine, "research:$id")
            Result.success()
        } catch (e: BrainException) {
            if (e.retryable && runAttemptCount < 4) {
                store.update { it.upsert(report.copy(status = ResearchStatus.QUEUED, error = e.message)) }
                Result.retry()
            } else {
                store.update { data ->
                    var d = data.upsert(report.copy(status = ResearchStatus.FAILED, error = e.message))
                    report.actionId?.let { aid ->
                        d.action(aid)?.let { a -> d = d.upsert(a.copy(status = ActionStatus.FAILED, resultMessage = e.message)) }
                    }
                    d
                }
                Result.failure()
            }
        }
    }

    companion object { const val KEY_ID = "research_id" }
}

/**
 * Looks for new call recordings and queues them for processing. After a call (KEY_CHECK_IN), if the
 * call was not recorded, asks the user for a 10-second voice debrief instead.
 */
class ScanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        val graph = ctx.graph
        val settings = graph.settings
        if (settings.scanSince == 0L) settings.scanSince = System.currentTimeMillis()

        if (settings.processCalls) {
            val found = RecordingFinder.findSince(ctx, settings.scanSince - 60_000, settings.recordingsFolder)
            for (rec in found) {
                val key = rec.uri.toString()
                if (key in graph.store.data.processedAudio) continue
                val call = CallLogHelper.matchRecording(ctx, rec.timeMillis)
                val interaction = Interaction(
                    id = UUID.randomUUID().toString(),
                    source = SourceType.CALL,
                    occurredAt = call?.startMillis ?: rec.timeMillis,
                    createdAt = System.currentTimeMillis(),
                    contactName = call?.name ?: RecordingFinder.nameHintFromFile(rec.name),
                    contactNumber = call?.number,
                    callDirection = call?.direction,
                    durationSec = call?.durationSec,
                    audioUri = key,
                    audioName = rec.name,
                )
                graph.store.update { d ->
                    var next = d.upsert(interaction).markAudioProcessed(key)
                    call?.let { next = next.markCallCheckedIn(it.id) }
                    next
                }
                Scheduler.process(ctx, interaction.id)
            }
            // Slide the scan window forward (keeping a 2-day overlap for late-indexed files); the
            // processed-audio list takes care of anything seen twice inside the overlap.
            settings.scanSince = maxOf(settings.scanSince, System.currentTimeMillis() - 48 * 60 * 60 * 1000L)
        }

        if (inputData.getBoolean(KEY_CHECK_IN, false) && settings.checkInAfterCalls) {
            val latest = CallLogHelper.recentCalls(ctx, System.currentTimeMillis() - 15 * 60 * 1000L, limit = 1).firstOrNull()
            if (latest != null && latest.durationSec >= settings.minCallSeconds &&
                latest.id !in graph.store.data.checkedInCalls
            ) {
                graph.store.update { it.markCallCheckedIn(latest.id) }
                val who = latest.name ?: latest.number ?: "unknown number"
                val minutes = (latest.durationSec + 59) / 60
                val builder = Notifier.base(
                    ctx, Notifier.CH_CHECKIN,
                    "📞 Call with $who ($minutes min)",
                    "Anything to handle? Tap and tell me what you agreed - I'll schedule it, remind you, or research it.",
                ).setContentIntent(
                    Notifier.openAppIntent(
                        ctx,
                        "debrief:${latest.startMillis}|${latest.durationSec}|${latest.number ?: ""}|${latest.name ?: ""}",
                    ),
                )
                Notifier.show(ctx, latest.id.hashCode(), builder)
            }
        }
        Result.success()
    }

    companion object { const val KEY_CHECK_IN = "check_in" }
}

/** Writes the end-of-day review. */
class ReviewWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        val graph = ctx.graph
        val manual = inputData.getBoolean(KEY_MANUAL, false)
        if (!manual && !graph.settings.dailyReview) return@withContext Result.success()
        val brain = graph.brain() ?: return@withContext Result.failure()
        val log = ReviewLog.build(ctx)
        if (log == null && !manual) return@withContext Result.success() // quiet day: no review
        try {
            val markdown = brain.dailyReview(log ?: ReviewLog.emptyDay(ctx))
            val now = System.currentTimeMillis()
            val review = DailyReview(UUID.randomUUID().toString(), LocalTimes.isoDate(now), markdown, now)
            graph.store.update { it.upsert(review) }
            val preview = markdown.lineSequence().map { it.trim() }
                .firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: "Tap to read."
            Notifier.show(
                ctx, 7100,
                Notifier.base(ctx, Notifier.CH_REVIEW, "🌙 Your day, reviewed", preview.replace("**", ""))
                    .setContentIntent(Notifier.openAppIntent(ctx, "review")),
            )
            Result.success()
        } catch (e: BrainException) {
            if (e.retryable && runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object { const val KEY_MANUAL = "manual" }
}
