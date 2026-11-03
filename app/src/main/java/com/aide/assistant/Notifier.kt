package com.aide.assistant

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/** All notifications the assistant posts. */
object Notifier {
    const val CH_UPDATES = "updates"
    const val CH_REMINDERS = "reminders"
    const val CH_ALARMS = "alarms"
    const val CH_CHECKIN = "checkin"
    const val CH_REVIEW = "review"
    const val CH_RECORDING = "recording"

    const val EXTRA_OPEN = "open"

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val alarmChannel = NotificationChannel(CH_ALARMS, "Alarms", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Alarms you asked Aide to set"
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build(),
            )
            enableVibration(true)
        }
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CH_UPDATES, "What Aide did", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = "Results after a call or note is processed" },
                NotificationChannel(CH_REMINDERS, "Reminders", NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = "Reminders Aide scheduled for you"; enableVibration(true) },
                alarmChannel,
                NotificationChannel(CH_CHECKIN, "After-call check-ins", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = "Asks what you agreed when a call could not be recorded" },
                NotificationChannel(CH_REVIEW, "Daily review", NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(CH_RECORDING, "Recording", NotificationManager.IMPORTANCE_LOW),
            ),
        )
    }

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /** PendingIntent that opens the app at a given place, e.g. "interaction:<id>", "research:<id>". */
    fun openAppIntent(context: Context, target: String, requestCode: Int = target.hashCode()): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_OPEN, target)
        return PendingIntent.getActivity(
            context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun base(context: Context, channel: String, title: String, text: String): NotificationCompat.Builder =
        NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_aide)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)

    fun show(context: Context, id: Int, builder: NotificationCompat.Builder) {
        if (!canNotify(context)) return
        try {
            NotificationManagerCompat.from(context).notify(id, builder.build())
        } catch (e: SecurityException) {
            // Permission revoked between the check and the call - nothing else to do.
        }
    }

    fun cancel(context: Context, id: Int) = NotificationManagerCompat.from(context).cancel(id)

    fun update(context: Context, id: Int, title: String, text: String, open: String) =
        show(context, id, base(context, CH_UPDATES, title, text).setContentIntent(openAppIntent(context, open)))
}
