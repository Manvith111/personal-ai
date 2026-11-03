package com.aide.assistant.record

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.aide.assistant.AideController
import com.aide.assistant.Notifier
import com.aide.core.model.SourceType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Records an in-person meeting / conversation with the microphone, in the background with a
 * notification. When stopped, the recording is handed to the assistant like a call recording.
 */
class RecorderService : Service() {
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start()
            ACTION_STOP -> stop(save = true)
            ACTION_CANCEL -> stop(save = false)
        }
        return START_NOT_STICKY
    }

    private fun start() {
        if (recorder != null) return
        val notification = Notifier.base(this, Notifier.CH_RECORDING, "Aide is listening", "Recording your conversation. Tap Stop when you are done.")
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(Notifier.openAppIntent(this, "home"))
            .addAction(0, "Stop & process", servicePending(ACTION_STOP, 1))
            .addAction(0, "Discard", servicePending(ACTION_CANCEL, 2))
            .build()
        val type = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        } catch (e: Exception) {
            // Android 14+ refuses a microphone service without the RECORD_AUDIO permission.
            _state.value = RecorderState(error = "Microphone permission is needed to record.")
            stopSelf()
            return
        }

        val dir = File(filesDir, "recordings").apply { mkdirs() }
        val out = File(dir, "meeting-${System.currentTimeMillis()}.m4a")
        val r = newRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(16_000)
            r.setAudioEncodingBitRate(32_000) // ~14 MB per hour: long meetings stay under upload limits
            r.setOutputFile(out.absolutePath)
            r.prepare()
            r.start()
        } catch (e: Exception) {
            r.release()
            _state.value = RecorderState(error = "Could not start recording: ${e.message}")
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        recorder = r
        file = out
        startedAt = System.currentTimeMillis()
        _state.value = RecorderState(recording = true, startedAt = startedAt)
    }

    private fun stop(save: Boolean) {
        val r = recorder
        val f = file
        recorder = null
        file = null
        var ok = r != null
        if (r != null) {
            try { r.stop() } catch (e: Exception) { ok = false } // stop() throws if nothing was recorded
            r.release()
        }
        _state.value = RecorderState()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        if (f == null) return
        if (save && ok && f.length() > 0) {
            val seconds = (System.currentTimeMillis() - startedAt) / 1000
            AideController.submitAudioFile(applicationContext, f, SourceType.MEETING, startedAt, seconds)
        } else {
            f.delete()
        }
    }

    override fun onDestroy() {
        recorder?.let { runCatching { it.stop() }; it.release() }
        recorder = null
        _state.value = RecorderState()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun newRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()

    private fun servicePending(action: String, code: Int) = android.app.PendingIntent.getService(
        this, code, Intent(this, RecorderService::class.java).setAction(action),
        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val ACTION_CANCEL = "cancel"
        private const val NOTIFICATION_ID = 7200

        private val _state = MutableStateFlow(RecorderState())
        val state: StateFlow<RecorderState> = _state

        fun start(context: Context) = ContextCompat.startForegroundService(
            context, Intent(context, RecorderService::class.java).setAction(ACTION_START),
        )

        fun stop(context: Context) {
            context.startService(Intent(context, RecorderService::class.java).setAction(ACTION_STOP))
        }
    }
}

data class RecorderState(val recording: Boolean = false, val startedAt: Long = 0L, val error: String? = null)
