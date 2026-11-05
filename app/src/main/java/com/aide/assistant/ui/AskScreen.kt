package com.aide.assistant.ui

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.aide.assistant.actions.CalendarHelper
import com.aide.assistant.graph
import com.aide.core.brain.BrainException
import com.aide.core.brain.ChatTurn
import com.aide.core.brain.HistoryContext
import com.aide.core.brain.OwnerProfile
import com.aide.core.model.AppData
import com.aide.core.people.PeopleIndex
import com.aide.core.time.LocalTimes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId

/** One question in the chat and what came back for it. */
private data class AskTurn(
    val id: Int,
    val question: String,
    val answer: String? = null,
    /** Ids of the conversations / research reports the answer is based on. */
    val sources: List<String> = emptyList(),
    val error: String? = null,
    val loading: Boolean = true,
)

private class AskOutcome(val answer: String, val sources: List<String>)

private val SUGGESTIONS = listOf(
    "What's still pending for me?",
    "What did I agree to this week?",
    "Who do I need to follow up with?",
)

/**
 * Ask questions about your own calls, notes and to-dos ("what did Rahul say about the price?"), and get a
 * short briefing before you talk to someone. Answers come only from what Aide has recorded and always
 * link back to the conversations they are based on.
 */
@Composable
fun AskScreen(data: AppData, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val turns = remember { mutableStateListOf<AskTurn>() }
    var question by rememberSaveable { mutableStateOf("") }
    var nextId by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    val busy = turns.any { it.loading }
    val ownerName = context.graph.settings.userName
    val people = remember(data.interactions.size, data.actions.size, ownerName) {
        PeopleIndex.list(data, ownerName, limit = 8)
    }

    fun start(label: String, work: () -> AskOutcome) {
        val id = nextId++
        turns += AskTurn(id, label)
        scope.launch {
            val outcome = withContext(Dispatchers.IO) { runCatching(work) }
            val i = turns.indexOfFirst { it.id == id }
            if (i >= 0) {
                turns[i] = outcome.fold(
                    onSuccess = { turns[i].copy(answer = it.answer, sources = it.sources, loading = false) },
                    onFailure = {
                        val msg = (it as? BrainException)?.message ?: "Something went wrong: ${it.message}"
                        turns[i].copy(error = msg, loading = false)
                    },
                )
            }
        }
    }

    fun ask(q: String) {
        val text = q.trim()
        if (text.isEmpty()) return
        val earlier = turns.toList()
        start(text) { runAsk(context, text, earlier) }
    }

    fun brief(person: String) {
        start("Brief me before I talk to $person") { runBriefing(context, person) }
    }

    LaunchedEffect(turns.size, turns.lastOrNull()?.loading) {
        if (turns.isNotEmpty()) listState.animateScrollToItem(turns.size)
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        BackHeader("Ask Aide", onBack)
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Ask about your calls, notes and to-dos. I only answer from what I've recorded, and I show where it came from.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (turns.isEmpty()) {
                        SUGGESTIONS.forEach { s ->
                            OutlinedButton(onClick = { ask(s) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(s) }
                        }
                    }
                    if (people.isNotEmpty()) {
                        Text("Brief me before I talk to…", style = MaterialTheme.typography.labelLarge)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(people, key = { it.name }) { p ->
                                FilledTonalButton(onClick = { brief(p.name) }, enabled = !busy) {
                                    Text(p.name + if (p.openItems > 0) " · ${p.openItems} open" else "")
                                }
                            }
                        }
                    }
                    if (turns.isNotEmpty()) {
                        TextButton(onClick = { turns.clear() }, enabled = !busy) { Text("Clear conversation") }
                    }
                }
            }
            items(turns, key = { it.id }) { t -> TurnCard(t, data, onOpen) }
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = question,
                onValueChange = { question = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("e.g. What did Rahul say about the price?") },
                maxLines = 3,
            )
            IconButton(
                onClick = { val q = question; question = ""; ask(q) },
                enabled = question.isNotBlank() && !busy,
            ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Ask") }
        }
    }
}

@Composable
private fun TurnCard(t: AskTurn, data: AppData, onOpen: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        ) {
            Text(t.question, Modifier.padding(12.dp), fontWeight = FontWeight.Medium)
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    t.loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text("  Looking through your history…", style = MaterialTheme.typography.bodySmall)
                    }
                    t.error != null -> Text(t.error, color = MaterialTheme.colorScheme.error)
                    else -> {
                        Markdown(t.answer.orEmpty())
                        if (t.sources.isNotEmpty()) {
                            Text("From", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            t.sources.forEach { id -> SourceLink(id, data, onOpen) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceLink(id: String, data: AppData, onOpen: (String) -> Unit) {
    val interaction = data.interaction(id)
    val report = data.report(id)
    val (label, target) = when {
        interaction != null ->
            (emojiFor(interaction.source) + " " + (interaction.analysis?.title ?: sourceLabel(interaction)) +
                " · " + LocalTimes.short(interaction.occurredAt)) to "interaction:$id"
        report != null -> ("🔎 " + report.topic) to "research:$id"
        else -> return
    }
    Text(
        label,
        color = MaterialTheme.colorScheme.primary,
        textDecoration = TextDecoration.Underline,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.clickable { onOpen(target) },
    )
}

private fun runAsk(context: Context, question: String, earlier: List<AskTurn>): AskOutcome {
    val graph = context.graph
    val brain = graph.brain() ?: throw BrainException("Add your Anthropic API key in Settings first.", retryable = false)
    val settings = graph.settings
    val now = System.currentTimeMillis()
    val answered = earlier.filter { it.answer != null }
    val history = HistoryContext.build(
        graph.store.data, question,
        OwnerProfile(settings.userName, settings.outputLanguage, settings.region),
        ZoneId.systemDefault(), now,
        previousQuestions = answered.map { it.question },
    )
    val chat = answered.takeLast(4).flatMap { listOf(ChatTurn(true, it.question), ChatTurn(false, it.answer.orEmpty())) }
    val result = brain.ask(question, history.text, chat, history.recordIds)
    graph.store.update { it.recordUsage(LocalTimes.isoDate(now), result.model, result.usage.inputTokens, result.usage.outputTokens) }
    return AskOutcome(result.answer.answer, result.answer.sourceIds)
}

private fun runBriefing(context: Context, person: String): AskOutcome {
    val graph = context.graph
    val brain = graph.brain() ?: throw BrainException("Add your Anthropic API key in Settings first.", retryable = false)
    val settings = graph.settings
    val now = System.currentTimeMillis()
    val zone = ZoneId.systemDefault()
    val upcoming = CalendarHelper.upcoming(context, now, now + 14 * 24 * 60 * 60 * 1000L)
    val data = graph.store.data
    val material = PeopleIndex.briefingContext(
        data, person, OwnerProfile(settings.userName, settings.outputLanguage, settings.region), zone, now, upcoming,
    )
    val result = brain.briefing(material)
    graph.store.update { it.recordUsage(LocalTimes.isoDate(now), result.model, result.usage.inputTokens, result.usage.outputTokens) }
    val sources = PeopleIndex.conversationsWith(data, person).take(5).map { it.id }
    return AskOutcome(result.markdown, sources)
}
