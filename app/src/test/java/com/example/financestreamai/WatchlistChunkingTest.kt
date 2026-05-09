package com.example.financestreamai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [chunkWatchlistForParallelScan] — the watchlist splitter that
 * fans out scan work across N concurrent backend jobs.
 */
class WatchlistChunkingTest {

    @Test
    fun emptyInput_returnsEmpty() {
        assertTrue(chunkWatchlistForParallelScan(emptyList()).isEmpty())
    }

    @Test
    fun smallList_singleChunk() {
        // <= minChunk + 2 (default 6) keeps everything in one job.
        val tickers = listOf("A", "B", "C", "D")
        val chunks = chunkWatchlistForParallelScan(tickers)
        assertEquals(1, chunks.size)
        assertEquals(tickers, chunks[0])
    }

    @Test
    fun sixTickers_singleChunk() {
        val tickers = (1..6).map { "T$it" }
        val chunks = chunkWatchlistForParallelScan(tickers)
        assertEquals(1, chunks.size)
        assertEquals(6, chunks[0].size)
    }

    @Test
    fun mediumList_splitsRespectingMinChunk() {
        // 12 tickers with default minChunk=4 -> at most 3 chunks (12/4)
        val tickers = (1..12).map { "T$it" }
        val chunks = chunkWatchlistForParallelScan(tickers)
        assertEquals(3, chunks.size)
        assertEquals(12, chunks.sumOf { it.size })
        chunks.forEach { assertTrue("chunk too small: ${it.size}", it.size >= 4) }
    }

    @Test
    fun largeList_capsAtMaxParallel() {
        // 60 tickers, default maxParallel=6 -> exactly 6 chunks of 10
        val tickers = (1..60).map { "T$it" }
        val chunks = chunkWatchlistForParallelScan(tickers)
        assertEquals(6, chunks.size)
        chunks.forEach { assertEquals(10, it.size) }
    }

    @Test
    fun unevenSplit_balancesWithinOne() {
        // 25 tickers across 6 chunks -> sizes should differ by at most 1
        val tickers = (1..25).map { "T$it" }
        val chunks = chunkWatchlistForParallelScan(tickers)
        val sizes = chunks.map { it.size }
        assertEquals(25, sizes.sum())
        assertTrue("imbalance: $sizes", sizes.max() - sizes.min() <= 1)
    }

    @Test
    fun preservesOrderAndPartitions() {
        val tickers = (1..40).map { "T$it" }
        val chunks = chunkWatchlistForParallelScan(tickers)
        val flat = chunks.flatten()
        assertEquals(tickers, flat)
    }

    @Test
    fun customMaxParallel_isHonored() {
        val tickers = (1..50).map { "T$it" }
        val chunks = chunkWatchlistForParallelScan(tickers, maxParallel = 3)
        assertEquals(3, chunks.size)
        assertEquals(50, chunks.sumOf { it.size })
    }

    @Test
    fun customMinChunk_preventsTinyJobs() {
        // 20 tickers, minChunk=10 -> at most 2 chunks
        val tickers = (1..20).map { "T$it" }
        val chunks = chunkWatchlistForParallelScan(tickers, maxParallel = 6, minChunk = 10)
        assertEquals(2, chunks.size)
        chunks.forEach { assertTrue(it.size >= 10) }
    }

    @Test
    fun noEmptyChunks() {
        for (n in 1..100) {
            val tickers = (1..n).map { "T$it" }
            val chunks = chunkWatchlistForParallelScan(tickers)
            chunks.forEach { assertTrue("n=$n produced empty chunk", it.isNotEmpty()) }
            assertEquals(n, chunks.sumOf { it.size })
        }
    }
}
