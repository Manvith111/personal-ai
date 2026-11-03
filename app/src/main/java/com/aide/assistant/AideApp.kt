package com.aide.assistant

import android.app.Application
import android.content.Context
import com.aide.assistant.data.Settings
import com.aide.assistant.data.Store
import com.aide.core.brain.AssistantBrain
import com.aide.core.stt.Transcriber
import java.io.File

class AideApp : Application() {
    lateinit var graph: Graph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = Graph(this)
        Notifier.createChannels(this)
        Scheduler.ensureBackgroundWork(this)
    }
}

/** Simple service locator shared by the UI, workers and receivers. */
class Graph(private val context: Context) {
    val settings = Settings(context)
    val store = Store(File(context.filesDir, "aide-data.json"))

    private var cachedBrain: Pair<String, AssistantBrain>? = null

    /** The Claude-backed brain, or null until an Anthropic API key is set. */
    @Synchronized
    fun brain(): AssistantBrain? {
        val key = settings.anthropicKey.trim()
        if (key.isEmpty()) return null
        val cacheKey = key + "|" + settings.model
        cachedBrain?.let { (k, b) -> if (k == cacheKey) return b }
        return AssistantBrain(key, settings.model).also { cachedBrain = cacheKey to it }
    }

    /** Speech-to-text for recorded audio, or null until its key is set. */
    fun transcriber(): Transcriber? {
        val key = settings.sttKey.trim()
        if (key.isEmpty()) return null
        return Transcriber(settings.sttProvider, key)
    }
}

val Context.graph: Graph get() = (applicationContext as AideApp).graph
