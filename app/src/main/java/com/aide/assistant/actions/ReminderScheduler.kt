package com.aide.assistant.actions

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.aide.assistant.NotificationActionReceiver
import com.aide.assistant.Notifier
import com.aide.assistant.graph
import com.aide.core.model.ActionStatus
import com.aide.core.model.ActionType

/** Fires reminders and alarms at the right time, even if the phone is asleep. */
object ReminderScheduler {
    private const val EXTRA_ACTION_ID = "action_id"

    fun schedule(context: Context, actionId: String, atMillis: Long) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = pendingIntent(context, actionId)
        val canExact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        try {
            if (canExact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
        }
    }

    fun cancel(context: Context, actionId: String) {
        context.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent(context, actionId))
    }

    /** Alarms are cleared on reboot; put back every reminder that is still in the future. */
    fun rescheduleAll(context: Context) {
        val now = System.currentTimeMillis()
        context.graph.store.data.actions
            .filter {
                (it.action.type == ActionType.REMINDER || it.action.type == ActionType.ALARM) &&
                    it.status == ActionStatus.DONE && !it.completed && (it.scheduledAt ?: 0) > now
            }
            .forEach { schedule(context, it.id, it.scheduledAt!!) }
    }

    private fun pendingIntent(context: Context, actionId: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            actionId.hashCode(),
            Intent(context, ReminderReceiver::class.java).putExtra(EXTRA_ACTION_ID, actionId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    internal fun actionIdFrom(intent: Intent): String? = intent.getStringExtra(EXTRA_ACTION_ID)
}

/** Shows the reminder / alarm notification when its time comes. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = ReminderScheduler.actionIdFrom(intent) ?: return
        val record = context.graph.store.data.action(id) ?: return
        if (record.completed || record.status != ActionStatus.DONE) return
        val isAlarm = record.action.type == ActionType.ALARM
        val text = record.action.details ?: record.action.evidence?.let { "From your conversation: \"$it\"" } ?: ""
        val builder = Notifier.base(
            context,
            if (isAlarm) Notifier.CH_ALARMS else Notifier.CH_REMINDERS,
            (if (isAlarm) "⏰ " else "🔔 ") + record.action.title,
            text,
        )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(
                if (isAlarm) NotificationCompat.CATEGORY_ALARM
                else NotificationCompat.CATEGORY_REMINDER,
            )
            .setContentIntent(Notifier.openAppIntent(context, "interaction:${record.interactionId}"))
            .addAction(0, "Done", NotificationActionReceiver.intent(context, NotificationActionReceiver.DONE, id))
            .addAction(0, "Snooze 15 min", NotificationActionReceiver.intent(context, NotificationActionReceiver.SNOOZE, id))
        Notifier.show(context, id.hashCode(), builder)
    }
}
