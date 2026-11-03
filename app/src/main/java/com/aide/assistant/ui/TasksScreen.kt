package com.aide.assistant.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aide.core.model.ActionStatus
import com.aide.core.model.ActionType
import com.aide.core.model.AppData

/** Everything Aide is tracking for you: approvals, upcoming items, to-dos and saved notes. */
@Composable
fun TasksScreen(data: AppData, onOpen: (String) -> Unit) {
    val now = System.currentTimeMillis()
    var showDone by remember { mutableStateOf(false) }
    val live = data.actions.filter { it.status != ActionStatus.DISMISSED && it.status != ActionStatus.UNDONE }

    val waiting = live.filter { it.status == ActionStatus.AWAITING_APPROVAL || it.status == ActionStatus.FAILED }
    val upcoming = live.filter {
        it.status == ActionStatus.DONE && !it.completed &&
            it.action.type in setOf(ActionType.CALENDAR_EVENT, ActionType.REMINDER, ActionType.ALARM) &&
            (it.scheduledAt ?: 0) > now - 60 * 60 * 1000L
    }.sortedBy { it.scheduledAt }
    val todos = live.filter {
        it.action.type == ActionType.TODO && it.status == ActionStatus.DONE && !it.completed
    }.sortedBy { it.scheduledAt ?: Long.MAX_VALUE }
    val research = live.filter { it.action.type == ActionType.RESEARCH && it.status == ActionStatus.IN_PROGRESS }
    val notes = live.filter { it.action.type == ActionType.NOTE }.sortedByDescending { it.createdAt }
    val done = data.actions.filter { it.completed }.sortedByDescending { it.updatedAt }.take(30)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Text("Your list", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        if (waiting.isEmpty() && upcoming.isEmpty() && todos.isEmpty() && notes.isEmpty() && research.isEmpty()) {
            item {
                Text(
                    "Nothing to track yet. Things you agree on calls, or ask me to do, will appear here.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        section("Needs your OK", waiting, onOpen)
        section("Coming up", upcoming, onOpen)
        section("To-do", todos, onOpen)
        section("Research in progress", research, onOpen)
        section("Notes I saved", notes, onOpen)
        if (done.isNotEmpty()) {
            item {
                TextButton(onClick = { showDone = !showDone }) {
                    Text(if (showDone) "Hide completed" else "Show completed (${done.size})")
                }
            }
            if (showDone) items(done, key = { "done-" + it.id }) { ActionCard(it, onOpen) }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.section(
    title: String,
    records: List<com.aide.core.model.ActionRecord>,
    onOpen: (String) -> Unit,
) {
    if (records.isEmpty()) return
    item(key = "title-$title") { SectionTitle("$title (${records.size})") }
    items(records, key = { title + it.id }) { ActionCard(it, onOpen) }
}
