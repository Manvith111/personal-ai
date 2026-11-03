package com.aide.assistant

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.aide.assistant.ui.AideRoot
import com.aide.assistant.ui.AideTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    /** Where a notification asked us to go ("interaction:<id>", "research:<id>", "review", ...). */
    private val openRequests = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val settings = graph.settings
        // Only recordings made after the app was first opened are processed automatically.
        if (settings.scanSince == 0L) settings.scanSince = System.currentTimeMillis()
        if (savedInstanceState == null) openRequests.value = intent?.getStringExtra(Notifier.EXTRA_OPEN)
        setContent {
            AideTheme { AideRoot(openRequests) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(Notifier.EXTRA_OPEN)?.let { openRequests.value = it }
    }

    override fun onResume() {
        super.onResume()
        // Opening the app is also a good moment to pick up any recordings that were missed.
        if (graph.settings.isReady) Scheduler.scanNow(this)
    }
}
