package com.aide.core

import com.aide.core.model.AppData
import com.aide.core.model.DailyReview
import com.aide.core.model.ReviewKind
import com.aide.core.store.JsonFileStore
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModelsTest {
    @Test
    fun usageIsTalliedPerDayAndModel() {
        var d = AppData()
        d = d.recordUsage("2026-11-04", "claude-opus-5-5", 1000, 200)
        d = d.recordUsage("2026-11-04", "claude-opus-5-5", 500, 100)
        d = d.recordUsage("2026-11-04", "claude-haiku-5-5", 50, 10)
        d = d.recordUsage("2026-11-05", "claude-opus-5-5", 10, 5)
        assertEquals(3, d.usage.size)
        val opus = d.usage.single { it.date == "2026-11-04" && it.model == "claude-opus-5-5" }
        assertEquals(2, opus.calls); assertEquals(1500, opus.inputTokens); assertEquals(300, opus.outputTokens)

        val month = d.usageSince("2026-11-01")
        assertEquals(4, month.calls); assertEquals(1560, month.inputTokens); assertEquals(315, month.outputTokens)
        val today = d.usageSince("2026-11-05")
        assertEquals(1, today.calls)
        assertEquals(0, d.usageSince("2027-01-01").calls)
    }

    @Test
    fun usageHistoryIsBounded() {
        var d = AppData()
        repeat(AppData.MAX_USAGE_ENTRIES + 25) { d = d.recordUsage("2026-01-01", "m$it", 1, 1) }
        assertEquals(AppData.MAX_USAGE_ENTRIES, d.usage.size)
        assertEquals("m${AppData.MAX_USAGE_ENTRIES + 24}", d.usage.last().model, "the newest entries are kept")
    }

    @Test
    fun weeklyAndDailyReviewsOfTheSameDateDoNotReplaceEachOther() {
        val daily = DailyReview("d", "2026-11-05", "daily", 1)
        val weekly = DailyReview("w", "2026-11-05", "weekly", 2, ReviewKind.WEEKLY)
        var d = AppData().upsert(daily).upsert(weekly)
        assertEquals(2, d.reviews.size)
        // regenerating the same kind replaces it
        d = d.upsert(daily.copy(id = "d2", markdown = "daily v2"))
        assertEquals(listOf("d2", "w"), d.reviews.map { it.id })
    }

    @Test
    fun dataSavedByTheOlderAppVersionStillLoads() {
        val dir = Files.createTempDirectory("aide-legacy").toFile()
        val file = File(dir, "db.json")
        // exactly what the previous version wrote: no "usage", no review "kind"
        file.writeText(
            """{"interactions":[],"actions":[],"research":[],
               "reviews":[{"id":"r","date":"2026-11-04","markdown":"old review","createdAt":5}],
               "processedAudio":["content://a"],"checkedInCalls":["9"]}""",
        )
        val loaded = JsonFileStore(file).snapshot()
        assertEquals(ReviewKind.DAILY, loaded.reviews.single().kind)
        assertTrue(loaded.usage.isEmpty())
        assertEquals(listOf("content://a"), loaded.processedAudio)
        // and the new fields survive a save/load round trip
        val store = JsonFileStore(file)
        store.update { it.recordUsage("2026-11-05", "m", 7, 3).upsert(DailyReview("w", "2026-11-05", "wk", 1, ReviewKind.WEEKLY)) }
        val again = JsonFileStore(file).snapshot()
        assertEquals(7, again.usage.single().inputTokens)
        assertEquals(setOf(ReviewKind.DAILY, ReviewKind.WEEKLY), again.reviews.map { it.kind }.toSet())
        dir.deleteRecursively()
    }
}
