package com.example.financestreamai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the CSP-Funded Call response merger.
 *
 * The merger combines two independent backtest responses (long-call BUY +
 * CSP SELL) into a single synthetic verdict with combo economics in the
 * summary. These tests pin the verdict/confidence rules and the net-debit
 * arithmetic.
 */
class CfComboMergeTest {

    private fun bt(
        verdict: String,
        confidence: String = "Medium",
        summary: String = "",
        score: String? = null,
        signals: List<String>? = null,
        warnings: List<String>? = null,
    ) = BacktestResponse(
        verdict = verdict,
        confidence = confidence,
        summary = summary,
        backtestScore = score,
        signals = signals,
        warnings = warnings,
    )

    // -------------------- verdict matrix --------------------

    @Test
    fun `both legs strong yields STRONG BUY`() {
        val merged = mergeCfComboResponses(
            callRes = bt("STRONG BUY", "High"),
            putRes = bt("STRONG SELL", "High"),
            callStrike = 220.0, putStrike = 200.0,
            callPrem = 5.00, putPrem = 4.00, expiry = "2026-06-19",
        )
        assertEquals("STRONG BUY", merged.verdict)
        assertEquals("High", merged.confidence)
    }

    @Test
    fun `both legs positive yields BUY`() {
        val merged = mergeCfComboResponses(
            callRes = bt("BUY", "High"),
            putRes = bt("SELL", "Medium"),
            callStrike = 220.0, putStrike = 200.0,
            callPrem = 5.00, putPrem = 4.00, expiry = "2026-06-19",
        )
        assertEquals("BUY", merged.verdict)
        // Confidence is the lower of the two leg confidences.
        assertEquals("Medium", merged.confidence)
    }

    @Test
    fun `weak call leg yields HOLD`() {
        val merged = mergeCfComboResponses(
            callRes = bt("HOLD", "Low"),
            putRes = bt("STRONG SELL", "High"),
            callStrike = 220.0, putStrike = 200.0,
            callPrem = 5.00, putPrem = 4.00, expiry = "2026-06-19",
        )
        assertEquals("HOLD", merged.verdict)
        assertEquals("Low", merged.confidence)
    }

    @Test
    fun `both legs negative yields AVOID`() {
        val merged = mergeCfComboResponses(
            callRes = bt("AVOID", "Low"),
            putRes = bt("AVOID", "Low"),
            callStrike = 220.0, putStrike = 200.0,
            callPrem = 5.00, putPrem = 4.00, expiry = "2026-06-19",
        )
        assertEquals("AVOID", merged.verdict)
    }

    @Test
    fun `missing put leg still produces a response`() {
        val merged = mergeCfComboResponses(
            callRes = bt("STRONG BUY", "High"),
            putRes = null,
            callStrike = 220.0, putStrike = 200.0,
            callPrem = 5.00, putPrem = 4.00, expiry = "2026-06-19",
        )
        // Strong call + missing put -> not strong-strong, not both-positive,
        // not both-negative, but put tier <= 0 -> HOLD per merger rules.
        assertEquals("HOLD", merged.verdict)
        // Warnings should call out the weak/missing put leg.
        assertTrue(
            "expected put-leg warning, got: ${merged.warnings}",
            merged.warnings?.any { it.contains("CSP leg") } == true
        )
    }

    // -------------------- combo economics in summary --------------------

    @Test
    fun `summary contains net debit, coverage and max loss`() {
        val merged = mergeCfComboResponses(
            callRes = bt("STRONG BUY", "High", summary = "call-leg-summary"),
            putRes = bt("STRONG SELL", "High", summary = "put-leg-summary"),
            callStrike = 220.0, putStrike = 200.0,
            callPrem = 5.00, putPrem = 4.00, expiry = "2026-06-19",
        )
        val s = merged.summary
        // Net debit per share = 5.00 - 4.00 = 1.00 -> "$1.00/share"
        assertTrue("net debit text missing in: $s", s.contains("Net debit"))
        assertTrue("expected \$1.00/share in: $s", s.contains("\$1.00/share"))
        // Per-contract = 100 -> "$100/contract"
        assertTrue("expected \$100/contract in: $s", s.contains("\$100/contract"))
        // Coverage = 4.00 / 5.00 = 80%
        assertTrue("expected 80% coverage in: $s", s.contains("80%"))
        // Max loss at put strike = 200 - 4 + max(0, 1) = 197
        assertTrue("expected max-loss \$197.00 in: $s", s.contains("\$197.00"))
        // Per-leg summaries appended
        assertTrue(s.contains("call-leg-summary"))
        assertTrue(s.contains("put-leg-summary"))
        // Headline carries the inputs
        assertTrue(s.contains("BUY \$220.00 call"))
        assertTrue(s.contains("SELL \$200.00 put"))
        assertTrue(s.contains("2026-06-19"))
    }

    @Test
    fun `self-funded combo flagged in signals when put covers call`() {
        val merged = mergeCfComboResponses(
            callRes = bt("BUY", "High"),
            putRes = bt("SELL", "High"),
            callStrike = 220.0, putStrike = 200.0,
            callPrem = 4.00, putPrem = 5.00, expiry = "2026-06-19",
        )
        assertNotNull(merged.signals)
        assertTrue(
            "expected 'Self-funded combo' signal, got: ${merged.signals}",
            merged.signals!!.any { it.contains("Self-funded", ignoreCase = true) }
        )
    }

    @Test
    fun `signals from each leg are prefixed`() {
        val merged = mergeCfComboResponses(
            callRes = bt("BUY", "High", signals = listOf("RSI healthy")),
            putRes = bt("SELL", "High", signals = listOf("IV rank elevated")),
            callStrike = 220.0, putStrike = 200.0,
            callPrem = 5.00, putPrem = 4.00, expiry = "2026-06-19",
        )
        val sigs = merged.signals!!
        assertTrue(sigs.any { it == "Call: RSI healthy" })
        assertTrue(sigs.any { it == "Put: IV rank elevated" })
    }

    @Test
    fun `inverted strikes produce a warning`() {
        val merged = mergeCfComboResponses(
            callRes = bt("BUY", "High"),
            putRes = bt("SELL", "High"),
            // Put strike >= call strike -> invalid combo, surface a warning.
            callStrike = 200.0, putStrike = 220.0,
            callPrem = 5.00, putPrem = 4.00, expiry = "2026-06-19",
        )
        assertTrue(
            "expected strike-order warning, got: ${merged.warnings}",
            merged.warnings!!.any { it.contains("below call strike", ignoreCase = true) }
        )
    }

    @Test
    fun `backtest score concatenates both legs`() {
        val merged = mergeCfComboResponses(
            callRes = bt("BUY", "High", score = "82%"),
            putRes = bt("SELL", "High", score = "91%"),
            callStrike = 220.0, putStrike = 200.0,
            callPrem = 5.00, putPrem = 4.00, expiry = "2026-06-19",
        )
        val s = merged.backtestScore!!
        assertTrue(s.contains("Call BT 82%"))
        assertTrue(s.contains("Put BT 91%"))
    }

    @Test
    fun `learning is null on merged response (no per-leg adjustments combined)`() {
        val merged = mergeCfComboResponses(
            callRes = bt("BUY", "High"),
            putRes = bt("SELL", "High"),
            callStrike = 220.0, putStrike = 200.0,
            callPrem = 5.00, putPrem = 4.00, expiry = "2026-06-19",
        )
        assertNull(merged.learning)
    }
}
