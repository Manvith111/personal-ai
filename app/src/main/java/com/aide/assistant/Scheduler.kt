package com.aide.assistant

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.aide.assistant.work.ProcessWorker
import com.aide.assistant.work.ResearchWorker
import com.aide.assistant.work.ReviewWorker
import com.aide.assistant.work.ScanWorker
import com.aide.assistant.work.WeeklyReviewWorker
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/** Everything the assistant does in the background is scheduled through WorkManager. */
object Scheduler {
    private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun ensureBackgroundWork(context: Context) {
        val wm = WorkManager.getInstance(context)
        // Safety net: look for new call recordings every 15 minutes even if a call-end event was missed.
        wm.enqueueUniquePeriodicWork(
            "scan-periodic",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ScanWorker>(15, TimeUnit.MINUTES).build(),
        )
        scheduleDailyReview(context, replace = false)
    }

    /** Called when a phone call ends: look for the recording shortly after, and again a bit later. */
    fun scanAfterCall(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.enqueueUniqueWork(
            "scan-after-call",
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<ScanWorker>()
                .setInitialDelay(15, TimeUnit.SECONDS)
                .build(),
        )
        wm.enqueueUniqueWork(
            "scan-after-call-late",
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<ScanWorker>()
                .setInitialDelay(75, TimeUnit.SECONDS)
                .setInputData(workDataOf(ScanWorker.KEY_CHECK_IN to true))
                .build(),
        )
    }

    fun scanNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            "scan-now", ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<ScanWorker>().build(),
        )
    }

    fun process(context: Context, interactionId: String) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            "process-$interactionId",
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<ProcessWorker>()
                .setConstraints(online)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .setInputData(workDataOf(ProcessWorker.KEY_ID to interactionId))
                .build(),
        )
    }

    fun research(context: Context, reportId: String) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            "research-$reportId",
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<ResearchWorker>()
                .setConstraints(online)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 60, TimeUnit.SECONDS)
                .setInputData(workDataOf(ResearchWorker.KEY_ID to reportId))
                .build(),
        )
    }

    fun reviewNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            "review-now",
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<ReviewWorker>()
                .setConstraints(online)
                .setInputData(workDataOf(ReviewWorker.KEY_MANUAL to true))
                .build(),
        )
    }

    /** The review of the last 7 days, started from the Review tab. */
    fun weeklyReviewNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            "review-week-now",
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<WeeklyReviewWorker>()
                .setConstraints(online)
                .build(),
        )
    }

    /** Daily review at the hour chosen in Settings. */
    fun scheduleDailyReview(context: Context, replace: Boolean) {
        val wm = WorkManager.getInstance(context)
        val settings = context.graph.settings
        if (!settings.dailyReview) {
            wm.cancelUniqueWork("review-daily")
            return
        }
        val now = LocalDateTime.now()
        var next = now.toLocalDate().atTime(settings.reviewHour, 0)
        if (!next.isAfter(now)) next = next.plusDays(1)
        val delay = Duration.between(now.atZone(ZoneId.systemDefault()), next.atZone(ZoneId.systemDefault()))
        wm.enqueueUniquePeriodicWork(
            "review-daily",
            if (replace) ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE else ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ReviewWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(delay.toMinutes().coerceAtLeast(1), TimeUnit.MINUTES)
                .setConstraints(online)
                .build(),
        )
    }
}
