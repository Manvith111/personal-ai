package com.aide.assistant.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aide.assistant.AideController
import com.aide.core.model.AppData
import com.aide.core.model.ProcessingStatus
import com.aide.core.time.LocalTimes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun BackHeader(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun InteractionScreen(id: String, data: AppData, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val interaction = data.interaction(id)
    var showTranscript by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    if (interaction == null) {
        Column(Modifier.padding(16.dp)) {
            BackHeader("Not found", onBack)
            Text("This item was deleted.")
        }
        return
    }
    val actions = data.actionsFor(id)
    val analysis = interaction.analysis

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { BackHeader(analysis?.title ?: sourceLabel(interaction), onBack) }
        item {
            Column(Modifier.padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "${emojiFor(interaction.source)} ${sourceLabel(interaction)} · ${LocalTimes.short(interaction.occurredAt)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    statusPill(interaction.status)
                }
                if (interaction.status in listOf(ProcessingStatus.QUEUED, ProcessingStatus.TRANSCRIBING, ProcessingStatus.ANALYZING)) {
                    LinearProgressIndicator(modifier = Modifier.padding(vertical = 4.dp))
                }
                interaction.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        if (analysis != null) {
            item {
                Card(
                    modifier = Modifier.padding(start = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("What I understood", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(analysis.summary, style = MaterialTheme.typography.bodyMedium)
                        analysis.keyPoints.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
            item {
                SectionTitle(
                    if (actions.isEmpty()) "Nothing needed doing" else "What I did (${actions.size})",
                    Modifier.padding(start = 8.dp),
                )
            }
            items(actions, key = { it.id }) { record ->
                Column(Modifier.padding(start = 8.dp)) { ActionCard(record, onOpen) }
            }
        }

        interaction.transcript?.let { transcript ->
            item {
                Column(Modifier.padding(start = 8.dp)) {
                    TextButton(onClick = { showTranscript = !showTranscript }) {
                        Text(if (showTranscript) "Hide transcript" else "Show transcript")
                    }
                    if (showTranscript) Text(transcript, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        item {
            Row(Modifier.padding(start = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    scope.launch(Dispatchers.IO) { AideController.reprocess(context, id) }
                    toast(context, "Processing again…")
                }) { Text("Process again") }
                TextButton(onClick = { confirmDelete = true }) { Text("Delete") }
            }
        }
        interaction.modelUsed?.let { m ->
            item {
                Text(
                    "Understood by $m",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp),
                )
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this?") },
            text = { Text("Removes it from Aide. Calendar events already added stay in your calendar.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch(Dispatchers.IO) { AideController.delete(context, id) }
                    onBack()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
