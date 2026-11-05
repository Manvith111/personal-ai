package com.aide.assistant.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aide.assistant.Scheduler
import com.aide.assistant.graph
import com.aide.core.model.AppData
import com.aide.core.model.ReviewKind

@Composable
fun ReviewScreen(data: AppData) {
    val context = LocalContext.current
    val settings = context.graph.settings
    val reviews = data.reviews.sortedByDescending { it.date }
    var expanded by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Text("Daily review", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    if (settings.dailyReview) "Every evening at ${settings.reviewHour}:00 I send you a review of your day: " +
                        "what I handled, what needs you, and what's coming tomorrow."
                    else "The automatic daily review is turned off in Settings.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = {
                        Scheduler.reviewNow(context)
                        toast(context, "Writing your review… I'll notify you")
                    }) { Text("Review my day now") }
                    FilledTonalButton(onClick = {
                        Scheduler.weeklyReviewNow(context)
                        toast(context, "Writing your weekly review… I'll notify you")
                    }) { Text("Review my week") }
                }
            }
        }
        if (reviews.isEmpty()) {
            item { Text("No reviews yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        items(reviews, key = { it.id }) { review ->
            val isOpen = expanded == review.id || (expanded == null && review == reviews.first())
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.padding(14.dp)) {
                    TextButton(onClick = { expanded = if (isOpen) "" else review.id }) {
                        Text((if (review.kind == ReviewKind.WEEKLY) "🗓 Week to " else "🌙 ") + review.date, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    }
                    if (isOpen) Markdown(review.markdown)
                }
            }
        }
    }
}
