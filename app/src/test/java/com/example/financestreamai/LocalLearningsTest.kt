package com.example.financestreamai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the client-side learnings derivation that runs when the
 * backend's /recommendations/learnings endpoint is unavailable (404).
 */
class LocalLearningsTest {

    private fun rec(
        ticker: String,
        strategy: String,
        summary: String,
        bt: String? = null,
        outcomes: List<OutcomeEntry>,
    ) = RecommendationItem(
        recId = "test-$ticker-$strategy",
        ticker = ticker,
        strategy = strategy,
        stockSummary = summary,
        outcomeHistory = outcomes,
        matchDetail = bt?.let { mapOf("bt" to it) },
    )

    private fun outcome(week: Int, status: String) = OutcomeEntry(
        week = week, status = status, priceChangePct = 0.0, evalAt = null
    )

    private fun winsAndLosses(wins: Int, losses: Int): List<OutcomeEntry> {
        val out = mutableListOf<OutcomeEntry>()
        repeat(wins) { out += outcome(it + 1, "winning") }
        repeat(losses) { out += outcome(wins + it + 1, "losing") }
        return out
    }

    @Test
    fun `signal extraction parses RSI, trend, drawdown, breadth and backtest tier`() {
        // Build 6 recs sharing the exact same signal so total >= 6 (significance threshold).
        val recs = (1..6).map {
            rec(
                ticker = "T$it",
                strategy = "csp",
                summary = "RSI 65, uptrend, 10% off high. 8 bullish vs 3 bearish.",
                bt = "92%",
                outcomes = listOf(outcome(1, "winning"))
            )
        }
        val learnings = LocalLearnings.derive(recs, stats = null)
        // Each rec contributes one outcome per signal -> total=6 per signal.
        // All outcomes are "winning" -> 100% win rate -> appears in topWinningSignals.
        val sigs = learnings.topWinningSignals!!.map { it.signal }.toSet()
        assertTrue("RSI band missing: $sigs", sigs.contains("RSI 60-70"))
        assertTrue("Trend missing: $sigs", sigs.contains("Trend: uptrend"))
        assertTrue("Drawdown band missing: $sigs", sigs.contains("5-15% off high"))
        assertTrue("Breadth band missing: $sigs", sigs.contains("Breadth >=+5"))
        assertTrue("Backtest tier missing: $sigs", sigs.contains("BT 90-95%"))
        // All should be 100% win rate.
        assertTrue(learnings.topWinningSignals.all { it.winRate >= 99.99 })
    }

    @Test
    fun `signals below significance threshold are dropped`() {
        // Only 5 recs sharing a signal -> 5 outcomes total (< 6) -> filtered out.
        val recs = (1..5).map {
            rec(
                ticker = "T$it",
                strategy = "csp",
                summary = "RSI 65, uptrend.",
                outcomes = listOf(outcome(1, "winning"))
            )
        }
        val learnings = LocalLearnings.derive(recs, stats = null)
        // Nothing crosses the >= 6 observation threshold, so winning list is empty.
        assertTrue(
            "expected no significant signals, got: ${learnings.topWinningSignals}",
            learnings.topWinningSignals!!.isEmpty()
        )
    }

    @Test
    fun `losing signals are sorted by lowest win rate`() {
        val winningRecs = (1..6).map {
            rec(
                ticker = "W$it",
                strategy = "vertical",
                summary = "RSI 80, uptrend.",  // -> "RSI >=70 (overbought)"
                outcomes = listOf(outcome(1, "losing"))
            )
        }
        val mixedRecs = (1..6).map {
            rec(
                ticker = "M$it",
                strategy = "vertical",
                summary = "RSI 45, sideways.",  // -> "RSI 40-50"
                // 3 wins, 3 losses -> 50% (just above the 50% loser threshold? actual
                // filter is < 50% so this should NOT be a "loser"; verify).
                outcomes = winsAndLosses(wins = 3, losses = 3)
            )
        }
        val learnings = LocalLearnings.derive(winningRecs + mixedRecs, stats = null)
        val losers = learnings.topLosingSignals!!
        // The fully-losing signal should be in the loser list.
        assertTrue(losers.any { it.signal == "RSI >=70 (overbought)" && it.winRate <= 0.01 })
        // The 50% signal should NOT be in the loser list (filter is winRate < 50.0).
        assertTrue(
            "50% signal should not be flagged as loser: $losers",
            losers.none { it.signal == "RSI 40-50" }
        )
    }

    @Test
    fun `suggestions flag low-winrate strategies from stats`() {
        val stats = RecommendationStats(
            enabled = true, horizonDays = 90,
            byStrategy = mapOf(
                "vertical" to StrategyStats(winning = 100, losing = 150, total = 262, winRate = 41.6),
                "csp" to StrategyStats(winning = 209, losing = 23, total = 239, winRate = 87.4),
            ),
            byVerdict = mapOf(
                "BUY" to StrategyStats(winning = 117, losing = 53, total = 226, winRate = 51.8),
                "STRONG BUY" to StrategyStats(winning = 244, losing = 56, total = 372, winRate = 65.6),
            ),
        )
        val learnings = LocalLearnings.derive(history = emptyList(), stats = stats)
        val suggestions = learnings.suggestedAdjustments!!
        // Vertical is below 50% with >= 30 samples -> flagged.
        assertTrue(
            "expected vertical flag, got: $suggestions",
            suggestions.any { it.contains("VERTICAL") && it.contains("41.6%") }
        )
        // STRONG BUY beats BUY by ~14 pts -> tier suggestion.
        assertTrue(
            "expected STRONG BUY suggestion, got: $suggestions",
            suggestions.any { it.contains("STRONG BUY") && it.contains("BUY") }
        )
        // CSP win rate 87.4% should NOT be flagged as low.
        assertTrue(
            "csp should not be flagged: $suggestions",
            suggestions.none { it.contains("CSP") && it.contains("raise") }
        )
    }

    @Test
    fun `mixed status tally aggregates wins, losses and neutrals correctly`() {
        // 2 recs sharing a signal, with 3 wins / 1 loss / 2 neutrals each.
        // Total observations per signal = 2 * 6 = 12, wins = 6, win rate = 50%.
        val outcomes = listOf(
            outcome(1, "winning"), outcome(2, "winning"), outcome(3, "winning"),
            outcome(4, "losing"),
            outcome(5, "neutral"), outcome(6, "neutral"),
        )
        val recs = (1..2).map {
            rec(
                ticker = "T$it",
                strategy = "csp",
                summary = "RSI 55, uptrend.",
                outcomes = outcomes
            )
        }
        val learnings = LocalLearnings.derive(recs, stats = null)
        // 50% win rate is the boundary: filter for losers is < 50.0 (strict) so it
        // should NOT be in losers; filter for winners is >= 60.0 so also not in
        // winners. Confirm both lists exclude it.
        assertTrue(learnings.topWinningSignals!!.none { it.signal.startsWith("RSI 50-60") })
        assertTrue(learnings.topLosingSignals!!.none { it.signal.startsWith("RSI 50-60") })
    }

    @Test
    fun `recs without strategy or outcomes are skipped`() {
        val noStrategy = RecommendationItem(
            recId = "x", ticker = "AAA", strategy = null,
            stockSummary = "RSI 50, uptrend.", outcomeHistory = listOf(outcome(1, "winning"))
        )
        val noOutcomes = RecommendationItem(
            recId = "y", ticker = "BBB", strategy = "csp",
            stockSummary = "RSI 50, uptrend.", outcomeHistory = null
        )
        val learnings = LocalLearnings.derive(listOf(noStrategy, noOutcomes), stats = null)
        // Both recs are skipped -> no signals at all.
        assertTrue(learnings.topWinningSignals!!.isEmpty())
        assertTrue(learnings.topLosingSignals!!.isEmpty())
    }

    @Test
    fun `derived learnings is marked enabled`() {
        val learnings = LocalLearnings.derive(history = emptyList(), stats = null)
        assertTrue("derived learnings should report enabled", learnings.enabled)
        assertNotNull(learnings.asOf)
        assertEquals("Derived locally from history", learnings.asOf)
    }
}
