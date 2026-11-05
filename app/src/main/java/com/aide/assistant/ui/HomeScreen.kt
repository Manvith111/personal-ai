package com.aide.assistant.ui

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aide.assistant.AideController
import com.aide.assistant.graph
import com.aide.assistant.record.RecorderService
import com.aide.core.model.ActionStatus
import com.aide.core.model.AppData
import com.aide.core.model.SourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** A call that just ended without a recording; the user can tell Aide what was agreed. */
data class Debrief(val startMillis: Long, val durationSec: Long, val number: String?, val name: String?) {
    val who: String get() = name ?: number ?: "unknown number"

    companion object {
        fun parse(target: String): Debrief? {
            val p = target.removePrefix("debrief:").split("|")
            if (p.size < 4) return null
            return Debrief(p[0].toLongOrNull() ?: return null, p[1].toLongOrNull() ?: 0, p[2].ifBlank { null }, p[3].ifBlank { null })
        }
    }
}

/** Launches the phone's speech recogniser and returns the text. */
@Composable
fun rememberSpeechInput(prompt: String, onResult: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
                ?.takeIf { it.isNotBlank() }?.let(onResult)
        }
    }
    return {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
        try {
            launcher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            toast(context, "No speech recogniser on this phone - type instead")
        }
    }
}

@Composable
fun HomeScreen(
    data: AppData,
    debrief: Debrief?,
    onDebriefDone: () -> Unit,
    onOpen: (String) -> Unit,
    goSettings: () -> Unit,
) {
    val context = LocalContext.current
    val graph = context.graph
    val settingsVersion by graph.settings.version.collectAsStateWithLifecycle()
    val recorder by RecorderService.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var text by rememberSaveable { mutableStateOf("") }

    fun submit(value: String, source: SourceType) {
        val t = value.trim()
        if (t.isEmpty()) return
        scope.launch(Dispatchers.IO) { AideController.submitText(context, t, source) }
        toast(context, "On it ✨")
    }

    val speak = rememberSpeechInput("Tell Aide what to do") { spoken -> submit(spoken, SourceType.VOICE_NOTE) }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) RecorderService.start(context) else toast(context, "Microphone permission is needed to record")
    }
    val importAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                runCatching { AideController.submitSharedAudio(context, uri) }
                    .onFailure { e -> launch(Dispatchers.Main) { toast(context, "Could not import: ${e.message}") } }
            }
            toast(context, "Importing… Aide will listen to it")
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().imePadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            val hour = LocalTime.now().hour
            val hello = when (hour) { in 5..11 -> "Good morning"; in 12..16 -> "Good afternoon"; else -> "Good evening" }
            val name = graph.settings.userName.takeIf { it.isNotBlank() && settingsVersion >= 0 }
            Column {
                Text(hello + (name?.let { ", $it" } ?: ""), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    "I listen to your calls and notes, then schedule, remind and research for you.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (!graph.settings.isReady) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Finish setting me up", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("Add your Claude API key and allow the permissions I need. It takes two minutes.")
                        Button(onClick = goSettings) { Text("Open setup") }
                    }
                }
            }
        }

        if (debrief != null) {
            item { DebriefCard(debrief, onDone = onDebriefDone) }
        }

        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("What can I do for you?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = text,
                            onValueChange = { text = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("e.g. Meet Priya tomorrow 4pm at Starbucks") },
                            maxLines = 4,
                        )
                        IconButton(onClick = { submit(text, SourceType.TYPED); text = "" }, enabled = text.isNotBlank()) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        FilledTonalButton(onClick = speak, modifier = Modifier.weight(1f)) { Text("🎙 Speak") }
                        if (recorder.recording) {
                            Button(onClick = { RecorderService.stop(context) }, modifier = Modifier.weight(1f)) {
                                RecordingTimer(recorder.startedAt)
                            }
                        } else {
                            OutlinedButton(onClick = {
                                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                                    PackageManager.PERMISSION_GRANTED
                                ) RecorderService.start(context) else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                            }, modifier = Modifier.weight(1f)) { Text("⏺ Meeting") }
                        }
                        OutlinedButton(onClick = { importAudio.launch(arrayOf("audio/*")) }, modifier = Modifier.weight(1f)) {
                            Text("📁 Audio")
                        }
                    }
                    recorder.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }

        item {
            OutlinedButton(onClick = { onOpen("ask") }, modifier = Modifier.fillMaxWidth()) {
                Text("💬 Ask Aide about your conversations")
            }
        }

        item { TodayStrip(data, onOpen) }

        item { SectionTitle("Recent") }
        if (data.interactions.isEmpty()) {
            item {
                Text(
                    "Nothing yet. After your next phone call I'll show what I understood and did here. " +
                        "You can also type or speak a request above, or share a WhatsApp voice note with Aide.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(data.interactions.sortedByDescending { it.occurredAt }.take(150), key = { it.id }) { i ->
            InteractionCard(i, data.actionsFor(i.id)) { onOpen("interaction:${i.id}") }
        }
    }
}

@Composable
private fun RecordingTimer(startedAt: Long) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startedAt) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val s = ((now - startedAt) / 1000).coerceAtLeast(0)
    Text("⏹ %d:%02d".format(s / 60, s % 60))
}

@Composable
private fun TodayStrip(data: AppData, onOpen: (String) -> Unit) {
    val zone = ZoneId.systemDefault()
    val start = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
    val conversations = data.interactions.count { it.createdAt >= start }
    val handled = data.actions.count { it.updatedAt >= start && it.status == ActionStatus.DONE }
    val waiting = data.actions.count { it.status == ActionStatus.AWAITING_APPROVAL }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            Stat("$conversations", "conversations today")
            Stat("$handled", "things handled")
            Stat("$waiting", "need your OK")
        }
    }
}

@Composable
private fun Stat(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DebriefCard(debrief: Debrief, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by rememberSaveable { mutableStateOf("") }
    fun send(value: String) {
        val t = value.trim()
        if (t.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            AideController.submitText(
                context, t, SourceType.VOICE_NOTE,
                occurredAt = debrief.startMillis, contactName = debrief.name ?: debrief.number,
                contactNumber = debrief.number, durationSec = debrief.durationSec,
            )
        }
        toast(context, "Got it - handling it now")
        onDone()
    }
    val speak = rememberSpeechInput("What did you agree with ${debrief.who}?") { send(it) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("📞 Your call with ${debrief.who}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("This call wasn't recorded. Tell me in a sentence what you agreed and I'll take care of it.")
            OutlinedTextField(
                value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("e.g. Meeting him Friday 11am at his office, need to send the quote") },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = speak) { Text("🎙 Speak") }
                FilledTonalButton(onClick = { send(text) }, enabled = text.isNotBlank()) { Text("Send") }
                TextButton(onClick = onDone) { Text("Nothing to do") }
            }
        }
    }
}
