package com.aide.assistant.data

import android.content.Context
import android.content.SharedPreferences
import com.aide.core.brain.AssistantBrain
import com.aide.core.stt.SttProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/** User preferences, stored privately on the device. */
class Settings(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("aide-settings", Context.MODE_PRIVATE)

    /** Bumped on every change so Compose screens can recompose. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    private fun str(key: String, def: String) = prefs.getString(key, def) ?: def
    private fun putStr(key: String, value: String) { prefs.edit().putString(key, value).apply(); _version.value++ }
    private fun bool(key: String, def: Boolean) = prefs.getBoolean(key, def)
    private fun putBool(key: String, value: Boolean) { prefs.edit().putBoolean(key, value).apply(); _version.value++ }
    private fun long(key: String, def: Long) = prefs.getLong(key, def)
    private fun putLong(key: String, value: Long) { prefs.edit().putLong(key, value).apply() }
    private fun int(key: String, def: Int) = prefs.getInt(key, def)
    private fun putInt(key: String, value: Int) { prefs.edit().putInt(key, value).apply(); _version.value++ }

    // --- AI ---
    var anthropicKey: String
        get() = str("anthropic_key", "")
        set(v) = putStr("anthropic_key", v.trim())
    var model: String
        get() = str("model", AssistantBrain.DEFAULT_MODEL)
        set(v) = putStr("model", v)
    var sttProvider: SttProvider
        get() = runCatching { SttProvider.valueOf(str("stt_provider", SttProvider.GROQ.name)) }
            .getOrDefault(SttProvider.GROQ)
        set(v) = putStr("stt_provider", v.name)
    var sttKey: String
        get() = str("stt_key", "")
        set(v) = putStr("stt_key", v.trim())

    // --- About you ---
    var userName: String
        get() = str("user_name", "")
        set(v) = putStr("user_name", v.trim())
    var outputLanguage: String
        get() = str("language", "English")
        set(v) = putStr("language", v.trim().ifEmpty { "English" })
    val region: String
        get() = Locale.getDefault().displayCountry.ifBlank { "unknown" }

    // --- What to automate ---
    var processCalls: Boolean
        get() = bool("process_calls", true)
        set(v) = putBool("process_calls", v)
    var checkInAfterCalls: Boolean
        get() = bool("check_in", true)
        set(v) = putBool("check_in", v)
    var autoCalendar: Boolean
        get() = bool("auto_calendar", true)
        set(v) = putBool("auto_calendar", v)
    var autoReminders: Boolean
        get() = bool("auto_reminders", true)
        set(v) = putBool("auto_reminders", v)
    var autoResearch: Boolean
        get() = bool("auto_research", true)
        set(v) = putBool("auto_research", v)
    var dailyReview: Boolean
        get() = bool("daily_review", true)
        set(v) = putBool("daily_review", v)
    var reviewHour: Int
        get() = int("review_hour", 21)
        set(v) = putInt("review_hour", v.coerceIn(0, 23))
    var minCallSeconds: Int
        get() = int("min_call_seconds", 20)
        set(v) = putInt("min_call_seconds", v.coerceIn(0, 600))

    /** Calendar to write to (-1 = automatic: the primary Google calendar). */
    var calendarId: Long
        get() = long("calendar_id", -1L)
        set(v) { putLong("calendar_id", v); _version.value++ }

    /** Optional folder (Storage Access Framework tree URI) where the dialer saves call recordings. */
    var recordingsFolder: String
        get() = str("recordings_folder", "")
        set(v) = putStr("recordings_folder", v)

    // --- Internal bookkeeping ---
    /** Recordings older than this are never auto-processed (set when the app is first opened). */
    var scanSince: Long
        get() = long("scan_since", 0L)
        set(v) = putLong("scan_since", v)
    var callActiveSince: Long
        get() = long("call_active_since", 0L)
        set(v) = putLong("call_active_since", v)
    var onboarded: Boolean
        get() = bool("onboarded", false)
        set(v) = putBool("onboarded", v)

    val isReady: Boolean get() = anthropicKey.isNotBlank()
}
