package com.aide.core

import com.aide.core.model.AppData
import com.aide.core.search.HistorySearch
import com.aide.core.search.HitKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HistorySearchTest {
    private val data = Fixtures.data()
    private val now = Fixtures.now

    @Test
    fun tokenizerHandlesCaseNumbersPluralsAndPunctuation() {
        assertEquals(listOf("quote", "rahul", "45000", "5pm"), HistorySearch.tokens("Quotes, Rahul! 45000 & 5pm"))
        // single letters are noise, but single digits are kept
        assertEquals(listOf("5"), HistorySearch.tokens("a 5 x"))
    }

    @Test
    fun tokenizerKeepsHindiWordsWhole() {
        // Devanagari vowel signs are combining marks, not letters; they must not split the word.
        val words = HistorySearch.tokens("राहुल से कल मिलना है")
        assertEquals(listOf("राहुल", "से", "कल", "मिलना", "है"), words)
    }

    @Test
    fun queryDropsQuestionWordsAndDuplicates() {
        assertEquals(listOf("rahul", "quote"), HistorySearch.queryTokens("What did Rahul say about the quote? quote"))
        assertTrue(HistorySearch.queryTokens("what did we talk about").isEmpty())
    }

    @Test
    fun findsTheRightConversationAndShowsWhy() {
        val hits = HistorySearch.search(data, "what did Rahul say about the quote", now)
        assertEquals("i-rahul", hits.first().id)
        assertEquals(HitKind.CONVERSATION, hits.first().kind)
        assertTrue(hits.first().snippet.contains("quote", ignoreCase = true), hits.first().snippet)
    }

    @Test
    fun savedNoteContentIsSearchable() {
        val hits = HistorySearch.search(data, "HDFC account number", now)
        assertEquals("i-rahul", hits.first().id)
    }

    @Test
    fun researchReportsAreFoundToo() {
        val hits = HistorySearch.search(data, "Regus Lokhandwala", now)
        assertEquals("r1", hits.first().id)
        assertEquals(HitKind.RESEARCH, hits.first().kind)
    }

    @Test
    fun aContactNameFindsThatPersonsConversationOnly() {
        // "Priya" is the contact of i-priya; Rahul's call never mentions her.
        val hits = HistorySearch.search(data, "Priya", now)
        assertEquals("i-priya", hits.first().id)
        assertTrue(hits.none { it.id == "i-rahul" })
    }

    @Test
    fun exactWordBeatsPrefixMatch() {
        val exact = data.copy(interactions = data.interactions.map {
            if (it.id == "i-dentist") it.copy(transcript = "meeting with the dentist") else it
        })
        // "meet" is an exact word in Rahul's title/summary; "meeting" only a prefix match in the dentist note.
        val hits = HistorySearch.search(exact, "meet", now)
        assertEquals("i-rahul", hits.first().id)
        assertTrue(hits.any { it.id == "i-dentist" })
    }

    @Test
    fun newerConversationWinsATie() {
        val a = Fixtures.typedNote.copy(id = "old", occurredAt = Fixtures.ms(9, 1), transcript = "buy milk")
        val b = Fixtures.typedNote.copy(id = "new", occurredAt = Fixtures.ms(11, 4), transcript = "buy milk")
        val hits = HistorySearch.search(AppData(interactions = listOf(a, b)), "milk", now)
        assertEquals(listOf("new", "old"), hits.map { it.id })
    }

    @Test
    fun nothingMatchedOrOnlyStopWordsGivesNoHits() {
        assertTrue(HistorySearch.search(data, "xylophone", now).isEmpty())
        assertTrue(HistorySearch.search(data, "what did we", now).isEmpty())
        assertTrue(HistorySearch.search(data, "", now).isEmpty())
    }

    @Test
    fun hindiQueryFindsHindiTranscript() {
        val hindi = Fixtures.typedNote.copy(id = "hi", transcript = "मुझे कल राहुल से मिलना है", analysis = null)
        val hits = HistorySearch.search(AppData(interactions = listOf(hindi)), "राहुल", now)
        assertEquals("hi", hits.single().id)
    }

    @Test
    fun excerptsReturnNonOverlappingPassagesAroundMatches() {
        val filler = "lorem ipsum dolor sit amet ".repeat(40)
        val text = filler + "the quote is forty five thousand " + filler + "send the quote by Friday " + filler
        val passages = HistorySearch.excerpts(text, listOf("quote"), windows = 3, width = 200)
        assertEquals(2, passages.size)
        assertTrue(passages.all { it.contains("quote") })
        assertTrue(passages.all { it.length <= 210 })
    }

    @Test
    fun excerptsOfShortOrUnmatchedTextAreSensible() {
        assertEquals(listOf("hello there"), HistorySearch.excerpts("hello  there", listOf("zzz")))
        val long = "word ".repeat(300)
        val fallback = HistorySearch.excerpts(long, listOf("zzz"), width = 100)
        assertEquals(1, fallback.size)
        assertTrue(fallback.single().endsWith("…"))
        assertTrue(HistorySearch.excerpts("", listOf("a")).isEmpty())
    }
}
