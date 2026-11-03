package com.aide.assistant.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aide.assistant.AideController
import com.aide.assistant.actions.ActionExecutor
import com.aide.assistant.actions.CalendarHelper
import com.aide.core.model.ActionRecord
import com.aide.core.model.ActionStatus
import com.aide.core.model.ActionType
import com.aide.core.model.Interaction
import com.aide.core.model.ProcessingStatus
import com.aide.core.model.SourceType
import com.aide.core.time.LocalTimes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun emojiFor(type: ActionType) = when (type) {
    ActionType.CALENDAR_EVENT -> "📅"
    ActionType.REMINDER -> "🔔"
    ActionType.ALARM -> "⏰"
    ActionType.TODO -> "☑️"
    ActionType.RESEARCH -> "🔎"
    ActionType.MESSAGE_DRAFT -> "💬"
    ActionType.NOTE -> "📝"
}

fun emojiFor(source: SourceType) = when (source) {
    SourceType.CALL -> "📞"
    SourceType.VOICE_NOTE -> "🎙️"
    SourceType.MEETING -> "👥"
    SourceType.TYPED -> "⌨️"
    SourceType.SHARED_TEXT -> "📤"
    SourceType.SHARED_AUDIO -> "🎧"
}

fun sourceLabel(i: Interaction): String = when (i.source) {
    SourceType.CALL -> "Call" + (i.contactName?.let { " with $it" } ?: i.contactNumber?.let { " with $it" } ?: "")
    SourceType.VOICE_NOTE -> if (i.contactName != null) "Debrief: call with ${i.contactName}" else "Voice note"
    SourceType.MEETING -> "Meeting recording"
    SourceType.TYPED -> "You asked"
    SourceType.SHARED_TEXT -> "Shared text"
    SourceType.SHARED_AUDIO -> "Shared audio" + (i.audioName?.let { " · $it" } ?: "")
}

@Composable
fun Pill(text: String, color: Color) {
    Surface(color = color.copy(alpha = 0.15f), shape = RoundedCornerShape(50)) {
        Text(
            text,
            color = color,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
fun statusPill(status: ProcessingStatus) = when (status) {
    ProcessingStatus.QUEUED -> Pill("Queued", MaterialTheme.colorScheme.secondary)
    ProcessingStatus.TRANSCRIBING -> Pill("Listening…", MaterialTheme.colorScheme.primary)
    ProcessingStatus.ANALYZING -> Pill("Thinking…", MaterialTheme.colorScheme.primary)
    ProcessingStatus.DONE -> Pill("Done", MaterialTheme.colorScheme.tertiary)
    ProcessingStatus.FAILED -> Pill("Problem", MaterialTheme.colorScheme.error)
    ProcessingStatus.NEEDS_SETUP -> Pill("Needs setup", MaterialTheme.colorScheme.error)
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(top = 8.dp, bottom = 2.dp),
    )
}

@Composable
fun InteractionCard(interaction: Interaction, actions: List<ActionRecord>, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(emojiFor(interaction.source), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        interaction.analysis?.title ?: sourceLabel(interaction),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        sourceLabel(interaction) + " · " + LocalTimes.short(interaction.occurredAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                statusPill(interaction.status)
            }
            interaction.analysis?.summary?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (interaction.status != ProcessingStatus.DONE && interaction.error != null) {
                Text(interaction.error!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (actions.isNotEmpty()) {
                Text(
                    actions.joinToString("   ") { emojiFor(it.action.type) + " " + it.action.title },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** One action with the buttons that make sense for its type and state. */
@Composable
fun ActionCard(record: ActionRecord, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val a = record.action
    fun io(block: () -> Unit) = scope.launch(Dispatchers.IO) { block() }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (a.type == ActionType.TODO && record.status == ActionStatus.DONE) {
                    Checkbox(
                        checked = record.completed,
                        onCheckedChange = { checked -> io { AideController.setCompleted(context, record.id, checked) } },
                    )
                } else {
                    Text(emojiFor(a.type), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.width(8.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(a.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    val sub = record.resultMessage ?: record.scheduledAt?.let { LocalTimes.short(it) }
                    if (sub != null) {
                        Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                actionStatusPill(record)
            }
            a.details?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (a.type == ActionType.MESSAGE_DRAFT) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = RoundedCornerShape(10.dp)) {
                    Column(Modifier.padding(10.dp)) {
                        a.recipient?.let { Text("To: $it", style = MaterialTheme.typography.labelMedium) }
                        Text(a.messageBody ?: "", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (a.type == ActionType.RESEARCH && a.researchQuestion != null) {
                Text("Question: ${a.researchQuestion}", style = MaterialTheme.typography.bodySmall)
            }
            a.evidence?.takeIf { it.isNotBlank() }?.let {
                Text("“$it”", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    a.type == ActionType.MESSAGE_DRAFT && record.status == ActionStatus.AWAITING_APPROVAL -> {
                        FilledTonalButton(onClick = {
                            openMessage(context, record, whatsApp = true)
                            io { AideController.markMessageSent(context, record.id) }
                        }) { Text("WhatsApp") }
                        OutlinedButton(onClick = {
                            openMessage(context, record, whatsApp = false)
                            io { AideController.markMessageSent(context, record.id) }
                        }) { Text("SMS") }
                        TextButton(onClick = { copy(context, a.messageBody ?: "") }) { Text("Copy") }
                    }
                    record.status == ActionStatus.AWAITING_APPROVAL -> {
                        FilledTonalButton(onClick = {
                            scope.launch {
                                val r = withContext(Dispatchers.IO) { ActionExecutor(context).approve(record.id) }
                                r?.resultMessage?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
                            }
                        }) { Text("Approve") }
                        TextButton(onClick = { io { AideController.dismiss(context, record.id) } }) { Text("Dismiss") }
                    }
                    record.status == ActionStatus.FAILED -> {
                        OutlinedButton(onClick = { io { ActionExecutor(context).approve(record.id) } }) { Text("Try again") }
                        TextButton(onClick = { io { AideController.dismiss(context, record.id) } }) { Text("Dismiss") }
                    }
                    a.type == ActionType.CALENDAR_EVENT && record.status == ActionStatus.DONE -> {
                        record.calendarEventId?.let { id ->
                            OutlinedButton(onClick = { start(context, CalendarHelper.viewEventIntent(id)) }) { Text("Open") }
                            TextButton(onClick = { io { ActionExecutor(context).undo(record.id) } }) { Text("Undo") }
                        }
                    }
                    (a.type == ActionType.REMINDER || a.type == ActionType.ALARM) && record.status == ActionStatus.DONE -> {
                        if (!record.completed) {
                            OutlinedButton(onClick = { io { AideController.setCompleted(context, record.id, true) } }) { Text("Mark done") }
                        }
                        TextButton(onClick = { io { ActionExecutor(context).undo(record.id) } }) { Text("Undo") }
                    }
                    a.type == ActionType.RESEARCH && record.researchId != null -> {
                        FilledTonalButton(onClick = { onOpen("research:${record.researchId}") }) { Text("View research") }
                    }
                    else -> Unit
                }
            }
        }
    }
}

@Composable
private fun actionStatusPill(record: ActionRecord) = when {
    record.completed -> Pill("Done", MaterialTheme.colorScheme.tertiary)
    record.status == ActionStatus.DONE -> Pill("Handled", MaterialTheme.colorScheme.tertiary)
    record.status == ActionStatus.AWAITING_APPROVAL -> Pill("Needs OK", MaterialTheme.colorScheme.primary)
    record.status == ActionStatus.IN_PROGRESS -> Pill("Working…", MaterialTheme.colorScheme.primary)
    record.status == ActionStatus.FAILED -> Pill("Failed", MaterialTheme.colorScheme.error)
    record.status == ActionStatus.UNDONE -> Pill("Undone", MaterialTheme.colorScheme.secondary)
    else -> Pill("Dismissed", MaterialTheme.colorScheme.secondary)
}

private fun openMessage(context: Context, record: ActionRecord, whatsApp: Boolean) {
    val intent = ActionExecutor(context).messageIntent(record, whatsApp)
    if (!start(context, intent) && whatsApp) start(context, ActionExecutor(context).messageIntent(record, false))
}

fun start(context: Context, intent: Intent): Boolean = try {
    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (e: ActivityNotFoundException) {
    Toast.makeText(context, "No app found to open this", Toast.LENGTH_SHORT).show()
    false
}

fun copy(context: Context, text: String) {
    val cm = context.getSystemService(ClipboardManager::class.java) ?: return
    cm.setPrimaryClip(ClipData.newPlainText("Aide", text))
    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}

fun toast(context: Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
