package com.aide.assistant.calls

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import com.aide.assistant.Scheduler
import com.aide.assistant.graph

/**
 * Listens for the phone's call state. When a call that was answered/placed goes back to idle, the
 * assistant looks for the call recording (and, if there is none, offers a quick voice debrief).
 */
class CallStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val settings = context.graph.settings
        if (!settings.processCalls && !settings.checkInAfterCalls) return
        when (intent.getStringExtra(TelephonyManager.EXTRA_STATE)) {
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                if (settings.callActiveSince == 0L) settings.callActiveSince = System.currentTimeMillis()
            }
            TelephonyManager.EXTRA_STATE_IDLE -> {
                if (settings.callActiveSince != 0L) {
                    settings.callActiveSince = 0L
                    Scheduler.scanAfterCall(context)
                }
            }
        }
    }
}
