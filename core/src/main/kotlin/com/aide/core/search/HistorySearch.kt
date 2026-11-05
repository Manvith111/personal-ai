package com.aide.core.search

import com.aide.core.model.AppData
import com.aide.core.model.Interaction
import com.aide.core.model.ResearchReport
import kotlin.math.exp
import kotlin.math.ln

enum class HitKind { CONVERSATION, RESEARCH }

/** One search result. [id] is the interaction or research id; [snippet] shows where the words matched. */
data class SearchHit(
    val kind: HitKind,
    val id: String,
    val title: String,
    val snippet: String,
    val whenMillis: Long,
    val score: Double,
)

/**
 * Offline keyword search over everything Aide has stored (conversations, the actions taken in them,
 * notes, research). No network, no index to maintain: the data set is small (one phone's worth), so
 * documents are scored on the fly.
 *
 * Works for any script: words are runs of letters/digits/combining marks, so Hindi (Devanagari) and
 * other languages tokenise correctly, not just English.
 */
object HistorySearch {

    /** Words that carry no search meaning in a question like "what did Rahul say about the quote?". */
    private val STOP_WORDS = setOf(
        "a", "an", "the", "of", "to", "in", "on", "at", "for", "with", "about", "from", "by", "and", "or",
        "is", "are", "was", "were", "be", "been", "am", "do", "did", "does", "done", "have", "has", "had",
        "what", "when", "where", "who", "whom", "which", "how", "why", "whats",
        "i", "me", "my", "mine", "you", "your", "he", "him", "his", "she", "her", "it", "its", "we", "us",
        "our", "they", "them", "their", "that", "this", "those", "these", "there", "then", "than", "so",
        "say", "said", "tell", "told", "talk", "talked", "mention", "mentioned", "discuss", "discussed",
        "any", "all", "some", "can", "could", "would", "should", "will", "just", "please", "last", "ago",
    )

    private const val W_NAME = 3.0
    private const val W_TITLE = 3.0
    private const val W_PEOPLE = 2.5
    private const val W_SUMMARY = 2.0
    private const val W_POINTS = 2.0
    private const val W_ACTIONS = 2.0
    private const val W_TRANSCRIPT = 1.0
    private const val PREFIX_FACTOR = 0.7

    /** Fields weighted at or above this are the contact name and the title (see [snippetSource]). */
    private const val HEADLINE_WEIGHT = 3.0

    /** Lower-cased words of [text]; light plural stemming for plain-ASCII words ("quotes" -> "quote"). */
    fun tokens(text: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        fun flush() {
            if (sb.isNotEmpty()) {
                val word = sb.toString().lowercase()
                sb.setLength(0)
                if (word.length >= 2 || word.any { it.isDigit() }) out += stem(word)
            }
        }
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            if (isWordPart(cp)) sb.appendCodePoint(cp) else flush()
        }
        flush()
        return out
    }

    /** The query's meaningful words: no stop words, no duplicates. */
    fun queryTokens(query: String): List<String> =
        tokens(query).filter { it !in STOP_WORDS }.distinct()

    fun search(
        data: AppData,
        query: String,
        nowMillis: Long = System.currentTimeMillis(),
        limit: Int = 10,
    ): List<SearchHit> {
        val q = queryTokens(query)
        if (q.isEmpty()) return emptyList()
        val hits = ArrayList<SearchHit>()
        data.interactions.forEach { i -> scoreInteraction(i, data, q, nowMillis)?.let { hits += it } }
        data.research.forEach { r -> scoreResearch(r, q, nowMillis)?.let { hits += it } }
        return hits.sortedByDescending { it.score }.take(limit)
    }

    /** All text of a conversation that can be searched, by field. Shared with the context builder. */
    internal fun fields(i: Interaction, data: AppData): List<Pair<Double, String>> {
        val a = i.analysis
        val actions = data.actionsFor(i.id).map { it.action }
        return buildList {
            add(W_NAME to listOfNotNull(i.contactName, i.contactNumber).joinToString(" "))
            add(W_TITLE to (a?.title ?: ""))
            add(W_PEOPLE to (a?.peopleMentioned?.joinToString(" ") ?: ""))
            add(W_SUMMARY to (a?.summary ?: ""))
            add(W_POINTS to (a?.keyPoints?.joinToString("\n") ?: ""))
            add(
                W_ACTIONS to actions.joinToString("\n") { x ->
                    listOfNotNull(
                        x.title, x.details, x.location, x.recipient, x.messageBody, x.researchQuestion,
                        x.evidence, x.people.takeIf { it.isNotEmpty() }?.joinToString(" "),
                    ).joinToString(" ")
                },
            )
            add(W_TRANSCRIPT to (i.transcript ?: ""))
        }
    }

    private fun scoreInteraction(i: Interaction, data: AppData, q: List<String>, now: Long): SearchHit? {
        val fields = fields(i, data)
        val score = score(fields, q, i.occurredAt, now) ?: return null
        val title = i.analysis?.title ?: i.contactName ?: "Conversation"
        return SearchHit(HitKind.CONVERSATION, i.id, title, snippet(snippetSource(fields, q), q), i.occurredAt, score)
    }

    private fun scoreResearch(r: ResearchReport, q: List<String>, now: Long): SearchHit? {
        val fields = listOf(
            W_TITLE to r.topic,
            W_SUMMARY to r.question,
            W_TRANSCRIPT to (r.markdown ?: ""),
        )
        val when_ = r.finishedAt ?: r.createdAt
        val score = score(fields, q, when_, now) ?: return null
        return SearchHit(HitKind.RESEARCH, r.id, r.topic, snippet(snippetSource(fields, q), q), when_, score)
    }

    /**
     * Weighted term-frequency score. Each query word counts once per field with a diminishing return on
     * repeats; documents matching more of the query's words rank higher; newer documents get a mild boost.
     * Returns null when nothing matched.
     */
    private fun score(fields: List<Pair<Double, String>>, q: List<String>, whenMillis: Long, now: Long): Double? {
        val tokenized = fields.map { (w, text) -> w to tokens(text) }
        var total = 0.0
        var matched = 0
        for (term in q) {
            var best = 0.0
            for ((weight, words) in tokenized) {
                var exact = 0
                var prefix = 0
                for (w in words) {
                    if (w == term) exact++ else if (term.length >= 3 && w.startsWith(term)) prefix++
                }
                val tf = exact + PREFIX_FACTOR * prefix
                // A lone prefix match (tf = 0.7) must rank below an exact match (tf = 1).
                if (tf > 0) best += weight * (1 + ln(tf.coerceAtLeast(1.0))) * minOf(1.0, tf)
            }
            if (best > 0) {
                matched++
                total += best
            }
        }
        if (matched == 0) return null
        val coverage = matched.toDouble() / q.size
        val ageDays = ((now - whenMillis) / 86_400_000.0).coerceAtLeast(0.0)
        val recency = 1.0 + 0.25 * exp(-ageDays / 30.0)
        return total * (0.5 + 0.5 * coverage) * recency
    }

    /**
     * The text to excerpt for a result: the field containing the most of the query's words (ties go to the
     * more important field). The name and title are skipped when anything else matches, because they are
     * already shown as the result's title and would make a useless snippet.
     */
    private fun snippetSource(fields: List<Pair<Double, String>>, q: List<String>): String {
        val scored = fields.mapNotNull { (weight, text) ->
            val words = tokens(text)
            val hits = q.count { t -> words.any { it == t || (t.length >= 3 && it.startsWith(t)) } }
            if (hits > 0) Triple(weight, text, hits) else null
        }
        val pool = scored.filter { it.first < HEADLINE_WEIGHT }.ifEmpty { scored }
        return pool.maxWithOrNull(compareBy<Triple<Double, String, Int>> { it.third }.thenBy { it.first })?.second ?: ""
    }

    /** A short passage of [text] around the first query word, for showing in a result list. */
    fun snippet(text: String, q: List<String>, width: Int = 160): String {
        val flat = text.replace(Regex("\\s+"), " ").trim()
        if (flat.length <= width) return flat
        val lower = flat.lowercase()
        val at = q.map { lower.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: 0
        val start = (at - width / 4).coerceAtLeast(0)
        val end = (start + width).coerceAtMost(flat.length)
        return (if (start > 0) "…" else "") + flat.substring(start, end).trim() + (if (end < flat.length) "…" else "")
    }

    /**
     * Up to [windows] passages of [text] (each about [width] characters) around where the query words
     * occur, in reading order and not overlapping. Used to send Claude only the relevant part of a long
     * transcript. Falls back to the beginning of the text when nothing matches.
     */
    fun excerpts(text: String, q: List<String>, windows: Int = 3, width: Int = 320): List<String> {
        val flat = text.replace(Regex("\\s+"), " ").trim()
        if (flat.length <= width) return if (flat.isEmpty()) emptyList() else listOf(flat)
        val lower = flat.lowercase()
        val positions = q.flatMap { term ->
            val found = ArrayList<Int>()
            var from = 0
            while (found.size < 20) {
                val p = lower.indexOf(term, from)
                if (p < 0) break
                found += p
                from = p + term.length
            }
            found
        }.sorted()
        if (positions.isEmpty()) return listOf(flat.take(width) + "…")
        val spans = ArrayList<IntRange>()
        for (p in positions) {
            val start = (p - width / 3).coerceAtLeast(0)
            val end = (start + width).coerceAtMost(flat.length)
            val last = spans.lastOrNull()
            if (last != null && start <= last.last) {
                spans[spans.size - 1] = last.first..maxOf(last.last, end)
            } else {
                if (spans.size >= windows) break
                spans += start..end
            }
        }
        return spans.take(windows).map { r ->
            (if (r.first > 0) "…" else "") + flat.substring(r.first, r.last.coerceAtMost(flat.length)).trim() +
                (if (r.last < flat.length) "…" else "")
        }
    }

    private fun isWordPart(cp: Int): Boolean {
        if (Character.isLetterOrDigit(cp)) return true
        return when (Character.getType(cp).toByte()) {
            Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK -> true
            else -> false
        }
    }

    private fun stem(word: String): String =
        if (word.length > 3 && word.endsWith("s") && !word.endsWith("ss") && word.all { it.code < 128 }) word.dropLast(1)
        else word
}
