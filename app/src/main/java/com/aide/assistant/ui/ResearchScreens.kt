package com.aide.assistant.ui

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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aide.assistant.AideController
import com.aide.core.model.AppData
import com.aide.core.model.ResearchReport
import com.aide.core.model.ResearchStatus
import com.aide.core.time.LocalTimes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun ResearchListScreen(data: AppData, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var question by rememberSaveable { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier.fillMaxSize().imePadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Text("Research", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Ask me to look into anything - I search the web and give you the insights.")
                    OutlinedTextField(
                        value = question,
                        onValueChange = { question = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("e.g. Best 5G phone under ₹30,000 for camera") },
                        maxLines = 4,
                    )
                    Button(
                        enabled = question.isNotBlank(),
                        onClick = {
                            val q = question
                            question = ""
                            scope.launch(Dispatchers.IO) { AideController.startResearch(context, q) }
                            toast(context, "Researching… I'll notify you when it's ready")
                        },
                    ) { Text("Research it") }
                }
            }
        }
        if (data.research.isEmpty()) {
            item {
                Text(
                    "When you say things like \"I need to research…\" on a call, the research shows up here automatically.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(data.research.sortedByDescending { it.createdAt }, key = { it.id }) { r ->
            ResearchCard(r) { onOpen("research:${r.id}") }
        }
    }
}

@Composable
private fun ResearchCard(r: ResearchReport, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🔎 " + r.topic, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                researchPill(r.status)
            }
            Text(LocalTimes.short(r.createdAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            bottomLine(r)?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis) }
            r.error?.takeIf { r.status == ResearchStatus.FAILED }?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun bottomLine(r: ResearchReport): String? =
    r.markdown?.lineSequence()?.firstOrNull { it.contains("Bottom line", ignoreCase = true) }
        ?.replace("**", "")?.trim()

@Composable
private fun researchPill(status: ResearchStatus) = when (status) {
    ResearchStatus.QUEUED -> Pill("Queued", MaterialTheme.colorScheme.secondary)
    ResearchStatus.RUNNING -> Pill("Researching…", MaterialTheme.colorScheme.primary)
    ResearchStatus.DONE -> Pill("Ready", MaterialTheme.colorScheme.tertiary)
    ResearchStatus.FAILED -> Pill("Failed", MaterialTheme.colorScheme.error)
}

@Composable
fun ResearchDetailScreen(id: String, data: AppData, onBack: () -> Unit) {
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val r = data.report(id)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { BackHeader("Research", onBack) }
        if (r == null) {
            item { Text("This report was deleted.", Modifier.padding(start = 8.dp)) }
            return@LazyColumn
        }
        item {
            Column(Modifier.padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(r.question, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    researchPill(r.status)
                    if (r.status == ResearchStatus.RUNNING || r.status == ResearchStatus.QUEUED) {
                        CircularProgressIndicator(modifier = Modifier.padding(start = 8.dp).size(16.dp), strokeWidth = 2.dp)
                        Text("  This usually takes 1-3 minutes.", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (r.status == ResearchStatus.FAILED) {
                    Text(r.error ?: "Failed", color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = { scope.launch(Dispatchers.IO) { AideController.retryResearch(context, id) } }) {
                        Text("Try again")
                    }
                }
            }
        }
        r.markdown?.let { md ->
            item {
                Card(
                    modifier = Modifier.padding(start = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                ) {
                    Markdown(md, Modifier.padding(14.dp))
                }
            }
        }
        if (r.sources.isNotEmpty()) {
            item { SectionTitle("Sources (${r.sources.size})", Modifier.padding(start = 8.dp)) }
            items(r.sources, key = { it.url }) { s ->
                Text(
                    s.title,
                    color = MaterialTheme.colorScheme.primary,
                    textDecoration = TextDecoration.Underline,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 8.dp).clickable { runCatching { uri.openUri(s.url) } },
                )
            }
        }
        r.modelUsed?.let { m ->
            item {
                Text("Researched by $m", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}
