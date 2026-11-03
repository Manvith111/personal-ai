package com.aide.assistant

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.aide.assistant.actions.ReminderScheduler

/** Restores reminders and background work after a reboot or app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        ReminderScheduler.rescheduleAll(context)
        Scheduler.ensureBackgroundWork(context)
    }
}

/** Handles "Done" / "Snooze" buttons on reminder notifications. */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val actionId = intent.getStringExtra(EXTRA_ID) ?: return
        when (intent.action) {
            DONE -> AideController.setCompleted(context, actionId, true)
            SNOOZE -> {
                val at = System.currentTimeMillis() + 15 * 60 * 1000L
                val store = context.graph.store
                store.data.action(actionId)?.let { a -> store.update { it.upsert(a.copy(scheduledAt = at)) } }
                ReminderScheduler.schedule(context, actionId, at)
            }
        }
        Notifier.cancel(context, actionId.hashCode())
    }

    companion object {
        const val DONE = "com.aide.assistant.DONE"
        const val SNOOZE = "com.aide.assistant.SNOOZE"
        private const val EXTRA_ID = "action_id"

        fun intent(context: Context, action: String, actionId: String): PendingIntent = PendingIntent.getBroadcast(
            context,
            (action + actionId).hashCode(),
            Intent(context, NotificationActionReceiver::class.java).setAction(action).putExtra(EXTRA_ID, actionId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
