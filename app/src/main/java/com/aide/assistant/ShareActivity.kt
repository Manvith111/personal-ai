package com.aide.assistant

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.IntentCompat
import com.aide.core.model.SourceType

/** Receives "Share -> Aide" from other apps (text, voice notes, recordings) and hands it over. */
class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val message = try {
            handle(intent)
        } catch (e: Exception) {
            "Aide could not read that: ${e.message}"
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }

    private fun handle(intent: Intent?): String {
        if (intent == null || intent.action != Intent.ACTION_SEND) return "Nothing to share"
        val stream: Uri? = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        val type = intent.type.orEmpty()
        if (stream != null && (type.startsWith("audio/") || type == "application/ogg")) {
            AideController.submitSharedAudio(applicationContext, stream)
            return "🎧 Got it - Aide will listen and take care of it."
        }
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()
        if (!text.isNullOrEmpty()) {
            val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)
            AideController.submitText(
                applicationContext, text, SourceType.SHARED_TEXT, label = subject,
            )
            return "✨ Got it - Aide is on it."
        }
        return "Nothing Aide can use in that share"
    }
}
