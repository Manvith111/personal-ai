package com.aide.assistant.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/** Renders the small Markdown subset Claude is asked to use: headings, bullets, bold, links, code. */
@Composable
fun Markdown(text: String, modifier: Modifier = Modifier) {
    val linkColor = MaterialTheme.colorScheme.primary
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (raw in text.lines()) {
            val line = raw.trimEnd()
            val trimmed = line.trimStart()
            val indent = ((line.length - trimmed.length) / 2).coerceAtMost(4)
            val numbered = Regex("^(\\d+)[.)]\\s+(.*)").find(trimmed)
            when {
                trimmed.isEmpty() -> Spacer(Modifier.height(4.dp))
                trimmed.startsWith("### ") -> Text(
                    inline(trimmed.removePrefix("### "), linkColor),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
                trimmed.startsWith("## ") -> Text(
                    inline(trimmed.removePrefix("## "), linkColor),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 8.dp),
                )
                trimmed.startsWith("# ") -> Text(
                    inline(trimmed.removePrefix("# "), linkColor),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                trimmed == "---" || trimmed == "***" -> HorizontalDivider(Modifier.padding(vertical = 4.dp))
                trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("• ") -> Row {
                    Spacer(Modifier.width((indent * 12).dp))
                    Text("•", modifier = Modifier.width(16.dp), style = MaterialTheme.typography.bodyMedium)
                    Text(inline(trimmed.substring(2), linkColor), style = MaterialTheme.typography.bodyMedium)
                }
                numbered != null -> Row {
                    Spacer(Modifier.width((indent * 12).dp))
                    Text("${numbered.groupValues[1]}.", modifier = Modifier.width(22.dp), style = MaterialTheme.typography.bodyMedium)
                    Text(inline(numbered.groupValues[2], linkColor), style = MaterialTheme.typography.bodyMedium)
                }
                else -> Text(inline(trimmed, linkColor), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private val inlinePattern = Regex("""\*\*(.+?)\*\*|\[([^\]]+)]\((https?://[^)\s]+)\)|`([^`]+)`""")

fun inline(s: String, linkColor: Color): AnnotatedString = buildAnnotatedString {
    var last = 0
    for (m in inlinePattern.findAll(s)) {
        append(s.substring(last, m.range.first))
        when {
            m.groups[1] != null -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(m.groupValues[1]) }
            m.groups[2] != null -> withLink(
                LinkAnnotation.Url(
                    m.groupValues[3],
                    TextLinkStyles(style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)),
                ),
            ) { append(m.groupValues[2]) }
            else -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(m.groupValues[4]) }
        }
        last = m.range.last + 1
    }
    append(s.substring(last))
}
