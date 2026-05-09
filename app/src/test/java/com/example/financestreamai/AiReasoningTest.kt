package com.example.financestreamai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the reasoning sanitizer + highlight picker that drive the
 * polished AI cross-validation badge UI.
 */
class AiReasoningTest {

    // --------- sanitizeReasoning ---------

    @Test fun sanitize_emptyStringStaysEmpty() {
        assertEquals("", AiCrossValidator.sanitizeReasoning(""))
        assertEquals("", AiCrossValidator.sanitizeReasoning("   "))
    }

    @Test fun sanitize_stripsCodeFences() {
        val raw = "```json\nStrong upside on guidance beat.\n```"
        assertEquals("Strong upside on guidance beat.", AiCrossValidator.sanitizeReasoning(raw))
    }

    @Test fun sanitize_extractsReasoningFromJsonBlob() {
        val raw = """{"verdict":"BUY","confidence":"High","reasoning":"Solid earnings beat with raised guidance."}"""
        assertEquals(
            "Solid earnings beat with raised guidance.",
            AiCrossValidator.sanitizeReasoning(raw)
        )
    }

    @Test fun sanitize_handlesEscapedQuotesInsideJson() {
        val raw = """{"verdict":"BUY","reasoning":"They said \"beat the street\" three times."}"""
        val out = AiCrossValidator.sanitizeReasoning(raw)
        assertTrue("missing inner quote: $out", "\"beat the street\"" in out)
    }

    @Test fun sanitize_collapsesWhitespace() {
        val raw = "Strong\n\nupside\t   on    guidance"
        assertEquals("Strong upside on guidance", AiCrossValidator.sanitizeReasoning(raw))
    }

    @Test fun sanitize_capsExcessiveLength() {
        val raw = "x".repeat(1000)
        val out = AiCrossValidator.sanitizeReasoning(raw)
        assertTrue("not capped: ${out.length}", out.length <= 400)
        assertTrue(out.endsWith("…"))
    }

    // --------- pickHighlightReasoning ---------

    private fun engine(name: String, verdict: String, confidence: String, reasoning: String, error: String? = null) =
        AiEngineResult(name, verdict, confidence, reasoning, error)

    @Test fun highlight_returnsNullWhenNoReasoning() {
        val v = AiCrossValidation(
            ticker = "TSLA",
            engines = listOf(
                engine("Claude", "BUY", "High", ""),
                engine("Gemini", "BUY", "Medium", "")
            ),
            consensus = "BUY", agreementPct = 100, summary = ""
        )
        assertNull(pickHighlightReasoning(v))
    }

    @Test fun highlight_prefersConsensusMatchingEngine() {
        val v = AiCrossValidation(
            ticker = "TSLA",
            engines = listOf(
                engine("Claude", "HOLD", "High", "Mixed signals."),
                engine("Gemini", "BUY", "Medium", "Earnings beat."),
                engine("Grok", "BUY", "Low", "Momentum.")
            ),
            consensus = "BUY", agreementPct = 66, summary = ""
        )
        val pick = pickHighlightReasoning(v)
        assertNotNull(pick)
        assertEquals("Gemini", pick!!.engine) // BUY + highest confidence among matching
    }

    @Test fun highlight_strongBuyMatchesPlainBuyEngines() {
        val v = AiCrossValidation(
            ticker = "NVDA",
            engines = listOf(
                engine("ChatGPT", "BUY", "High", "Earnings beat & guide raise.")
            ),
            consensus = "STRONG BUY", agreementPct = 100, summary = ""
        )
        val pick = pickHighlightReasoning(v)
        assertNotNull(pick)
        assertEquals("ChatGPT", pick!!.engine)
    }

    @Test fun highlight_fallsBackToAnyReasoningWhenNoMatch() {
        val v = AiCrossValidation(
            ticker = "AMC",
            engines = listOf(
                engine("Claude", "HOLD", "High", "Range-bound."),
                engine("Gemini", "SELL", "Medium", "Trend break.")
            ),
            consensus = "BUY", agreementPct = 0, summary = ""
        )
        val pick = pickHighlightReasoning(v)
        assertNotNull(pick)
        // Highest confidence non-blank reasoning wins
        assertEquals("Claude", pick!!.engine)
    }

    @Test fun highlight_skipsErroredEngines() {
        val v = AiCrossValidation(
            ticker = "TSLA",
            engines = listOf(
                engine("Claude", "N/A", "N/A", "leaked partial response", error = "HTTP 500"),
                engine("Gemini", "BUY", "Medium", "Solid setup.")
            ),
            consensus = "BUY", agreementPct = 100, summary = ""
        )
        val pick = pickHighlightReasoning(v)
        assertNotNull(pick)
        assertEquals("Gemini", pick!!.engine)
    }
}
