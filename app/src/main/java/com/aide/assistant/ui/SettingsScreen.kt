package com.aide.assistant.ui

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aide.assistant.Scheduler
import com.aide.assistant.actions.CalendarHelper
import com.aide.assistant.graph
import com.aide.core.brain.AssistantBrain
import com.aide.core.brain.BrainException
import com.aide.core.stt.SttProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class PermissionGroup(val title: String, val why: String, val permissions: List<String>)

private fun permissionGroups(): List<PermissionGroup> = buildList {
    add(PermissionGroup("Calls", "Know when a call ends and who it was with",
        listOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_CONTACTS)))
    add(PermissionGroup("Call recordings", "Read the recordings your phone's dialer saves",
        listOf(if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE)))
    add(PermissionGroup("Calendar", "Add the meetings you agree on",
        listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)))
    add(PermissionGroup("Microphone", "Voice notes and meeting recordings", listOf(Manifest.permission.RECORD_AUDIO)))
    if (Build.VERSION.SDK_INT >= 33) {
        add(PermissionGroup("Notifications", "Reminders, results and your daily review",
            listOf(Manifest.permission.POST_NOTIFICATIONS)))
    }
}

private fun granted(context: Context, p: String) =
    ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val settings = context.graph.settings
    val version by settings.version.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refresh++
        Scheduler.scanNow(context)
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            settings.recordingsFolder = uri.toString()
            Scheduler.scanNow(context)
        }
    }

    var anthropicKey by remember { mutableStateOf(settings.anthropicKey) }
    var sttKey by remember { mutableStateOf(settings.sttKey) }
    var name by remember { mutableStateOf(settings.userName) }
    var language by remember { mutableStateOf(settings.outputLanguage) }
    var testing by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().imePadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }

        item {
            SettingsCard("1 · Claude - the brain") {
                Text(
                    "Aide uses Claude to understand your conversations, research and write your review. " +
                        "Create an API key at console.anthropic.com → API keys, and paste it here.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = anthropicKey,
                    onValueChange = { anthropicKey = it; settings.anthropicKey = it },
                    label = { Text("Anthropic API key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Model", style = MaterialTheme.typography.labelLarge)
                AssistantBrain.MODEL_OPTIONS.forEach { option ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = settings.model == option.id && version >= 0, onClick = { settings.model = option.id })
                        Text(option.label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                FilledTonalButton(
                    enabled = !testing && anthropicKey.isNotBlank(),
                    onClick = {
                        testing = true
                        scope.launch {
                            val msg = withContext(Dispatchers.IO) {
                                try {
                                    context.graph.brain()?.ping() ?: "Add a key first"
                                } catch (e: BrainException) {
                                    e.message ?: "Failed"
                                } catch (e: Exception) {
                                    "Failed: ${e.message}"
                                }
                            }
                            testing = false
                            toast(context, msg)
                        }
                    },
                ) { Text(if (testing) "Testing…" else "Test connection") }
            }
        }

        item {
            SettingsCard("2 · Listening to recordings") {
                Text(
                    "To understand recorded calls and meetings, audio is converted to text by a speech-to-text " +
                        "service first. Typed and spoken requests don't need this.",
                    style = MaterialTheme.typography.bodySmall,
                )
                SttProvider.entries.forEach { p ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = settings.sttProvider == p && version >= 0, onClick = { settings.sttProvider = p })
                        Column {
                            Text(p.label, style = MaterialTheme.typography.bodyMedium)
                            Text(p.keyHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                OutlinedTextField(
                    value = sttKey,
                    onValueChange = { sttKey = it; settings.sttKey = it },
                    label = { Text("${settings.sttProvider.name.lowercase().replaceFirstChar { it.uppercase() }} API key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        item {
            SettingsCard("3 · About you") {
                OutlinedTextField(
                    value = name, onValueChange = { name = it; settings.userName = it },
                    label = { Text("Your name (so I know which side of the call is you)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = language, onValueChange = { language = it; settings.outputLanguage = it },
                    label = { Text("Language for summaries (e.g. English, Hindi)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        item {
            SettingsCard("4 · Permissions") {
                key(refresh) {
                    permissionGroups().forEach { g ->
                        val ok = g.permissions.all { granted(context, it) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text((if (ok) "✅ " else "⬜ ") + g.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                Text(g.why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (!ok) TextButton(onClick = { permissionLauncher.launch(g.permissions.toTypedArray()) }) { Text("Allow") }
                        }
                    }
                    val all = permissionGroups().flatMap { it.permissions }.filterNot { granted(context, it) }
                    if (all.isNotEmpty()) {
                        FilledTonalButton(onClick = { permissionLauncher.launch(all.toTypedArray()) }) { Text("Allow everything") }
                    }
                    BatteryRow(context)
                    ExactAlarmRow(context)
                }
            }
        }

        item {
            SettingsCard("5 · Call recordings") {
                Text(
                    "Turn on automatic call recording in your phone's dialer (Phone app → Settings → Call recording). " +
                        "Aide finds new recordings in folders containing \"call\" automatically. If yours are elsewhere, choose the folder.",
                    style = MaterialTheme.typography.bodySmall,
                )
                val folder = settings.recordingsFolder.takeIf { it.isNotBlank() && version >= 0 }
                Text(
                    if (folder != null) "Folder: " + Uri.decode(folder).substringAfterLast(':') else "Folder: automatic",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { folderPicker.launch(null) }) { Text("Choose folder") }
                    if (folder != null) TextButton(onClick = { settings.recordingsFolder = "" }) { Text("Use automatic") }
                    TextButton(onClick = { Scheduler.scanNow(context); toast(context, "Looking for new recordings…") }) { Text("Scan now") }
                }
            }
        }

        item {
            SettingsCard("6 · What I do automatically") {
                ToggleRow("Process call recordings", "Understand each recorded call", settings.processCalls) { settings.processCalls = it }
                ToggleRow("Ask me after unrecorded calls", "A notification to tell me what you agreed", settings.checkInAfterCalls) { settings.checkInAfterCalls = it }
                ToggleRow("Add meetings to my calendar", "Off = ask me first", settings.autoCalendar) { settings.autoCalendar = it }
                ToggleRow("Set reminders and alarms", "Off = ask me first", settings.autoReminders) { settings.autoReminders = it }
                ToggleRow("Start research", "Off = ask me first", settings.autoResearch) { settings.autoResearch = it }
                ToggleRow("Daily review", "An evening summary of your day", settings.dailyReview) {
                    settings.dailyReview = it
                    Scheduler.scheduleDailyReview(context, replace = true)
                }
                if (settings.dailyReview) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Review time: ${settings.reviewHour}:00", modifier = Modifier.weight(1f))
                        OutlinedButton(onClick = {
                            settings.reviewHour = (settings.reviewHour + 23) % 24
                            Scheduler.scheduleDailyReview(context, replace = true)
                        }) { Text("−") }
                        OutlinedButton(onClick = {
                            settings.reviewHour = (settings.reviewHour + 1) % 24
                            Scheduler.scheduleDailyReview(context, replace = true)
                        }) { Text("+") }
                    }
                }
                Text("Messages are never sent without you - I prepare drafts you send with one tap.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        item {
            SettingsCard("7 · Calendar") {
                key(refresh) {
                    val calendars = CalendarHelper.calendars(context)
                    if (calendars.isEmpty()) {
                        Text("Allow calendar access above to choose a calendar.", style = MaterialTheme.typography.bodySmall)
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = settings.calendarId == -1L && version >= 0, onClick = { settings.calendarId = -1L })
                            Text("Automatic (your main Google calendar)")
                        }
                        calendars.forEach { c ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = settings.calendarId == c.id, onClick = { settings.calendarId = c.id })
                                Column {
                                    Text(c.name, style = MaterialTheme.typography.bodyMedium)
                                    Text(c.account, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }

        item {
            SettingsCard("Privacy") {
                Text(
                    "Everything Aide stores stays on this phone. To understand a conversation, its text (and for " +
                        "recordings, the audio) is sent to Anthropic's Claude API and to the speech-to-text service you " +
                        "chose, using your own API keys. Recording calls may require the other person's consent where you live.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    var value by remember(checked) { mutableStateOf(checked) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = value, onCheckedChange = { value = it; onChange(it) })
    }
}

@Composable
private fun BatteryRow(context: Context) {
    val pm = context.getSystemService(PowerManager::class.java)
    val ok = pm?.isIgnoringBatteryOptimizations(context.packageName) ?: true
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text((if (ok) "✅ " else "⬜ ") + "Run in background", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text("Stops the phone from putting Aide to sleep (on Xiaomi/Oppo/Vivo also enable Autostart)",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!ok) TextButton(onClick = { start(context, Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }) { Text("Open") }
    }
}

@Composable
private fun ExactAlarmRow(context: Context) {
    if (Build.VERSION.SDK_INT !in 31..32) return
    val am = context.getSystemService(AlarmManager::class.java) ?: return
    if (am.canScheduleExactAlarms()) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("⬜ On-time reminders", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text("Lets reminders fire at the exact minute", style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = {
            start(context, Intent(AndroidSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
        }) { Text("Allow") }
    }
}
