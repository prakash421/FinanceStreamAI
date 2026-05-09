package com.example.financestreamai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the Gemini gate that runs in front of every recommendation.
 * We test the response parser (decision extraction) and prompt builder using
 * realistic Gemini API response shapes.
 */
class GeminiGateTest {

    private fun geminiBody(text: String): String {
        // Mimics the real /v1beta/models/gemini-2.0-flash:generateContent shape.
        return """{"candidates":[{"content":{"parts":[{"text":${jsonString(text)}}]}}]}"""
    }

    private fun jsonString(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    private fun item(
        ticker: String = "TSLA",
        rsi: Double? = 55.0,
        rec: String? = "STRONG BUY",
        bull: List<String>? = listOf("Above SMA200", "Earnings growth +30%"),
        bear: List<String>? = listOf("Near 52w high"),
    ) = ScanResultItem(
        ticker = ticker,
        price = 250.0,
        rsi = rsi,
        beta = 1.0,
        csps = null, diagonals = null, verticals = null, longLeaps = null,
        sma200 = 200.0,
        overall = rec,
        stockRecommendation = rec,
        bullishSignals = bull,
        bearishSignals = bear,
    )

    // -------------------- parseDecision --------------------

    @Test
    fun `parseDecision extracts APPROVE from clean JSON response`() {
        val body = geminiBody("""{"decision":"APPROVE","reasoning":"Strong fundamentals and trend alignment"}""")
        val (decision, reasoning) = GeminiGate.parseDecision(body)
        assertEquals(GeminiGate.Decision.APPROVE, decision)
        assertEquals("Strong fundamentals and trend alignment", reasoning)
    }

    @Test
    fun `parseDecision extracts VETO from clean JSON response`() {
        val body = geminiBody("""{"decision":"VETO","reasoning":"RSI overbought and momentum stalling"}""")
        val (decision, reasoning) = GeminiGate.parseDecision(body)
        assertEquals(GeminiGate.Decision.VETO, decision)
        assertTrue(reasoning.contains("overbought"))
    }

    @Test
    fun `parseDecision tolerates markdown code fences around JSON`() {
        // Gemini sometimes wraps JSON in ```json ... ``` despite the prompt.
        val body = geminiBody("```json\n{\"decision\":\"VETO\",\"reasoning\":\"weak\"}\n```")
        val (decision, _) = GeminiGate.parseDecision(body)
        assertEquals(GeminiGate.Decision.VETO, decision)
    }

    @Test
    fun `parseDecision falls back to keyword scan when no JSON is present`() {
        val body = geminiBody("I would VETO this trade given the bearish signals.")
        val (decision, _) = GeminiGate.parseDecision(body)
        assertEquals(GeminiGate.Decision.VETO, decision)
    }

    @Test
    fun `parseDecision returns UNAVAILABLE on empty body`() {
        val body = geminiBody("")
        val (decision, _) = GeminiGate.parseDecision(body)
        assertEquals(GeminiGate.Decision.UNAVAILABLE, decision)
    }

    @Test
    fun `parseDecision returns UNAVAILABLE on malformed wrapper`() {
        val (decision, _) = GeminiGate.parseDecision("not even json")
        assertEquals(GeminiGate.Decision.UNAVAILABLE, decision)
    }

    @Test
    fun `parseDecision treats unknown decision strings as UNAVAILABLE`() {
        val body = geminiBody("""{"decision":"MAYBE","reasoning":"unsure"}""")
        val (decision, _) = GeminiGate.parseDecision(body)
        assertEquals(GeminiGate.Decision.UNAVAILABLE, decision)
    }

    // -------------------- Result helpers --------------------

    @Test
    fun `Result approved is true for APPROVE and UNAVAILABLE (fail-open)`() {
        // Fail-open behaviour is critical: when Gemini is offline the user
        // must still see backend recommendations.
        val approve = GeminiGate.Result("X", GeminiGate.Decision.APPROVE, "ok")
        val unavail = GeminiGate.Result("X", GeminiGate.Decision.UNAVAILABLE, "no key")
        val veto = GeminiGate.Result("X", GeminiGate.Decision.VETO, "bad")
        assertTrue(approve.approved)
        assertTrue(unavail.approved)
        assertFalse(veto.approved)
        assertTrue(veto.vetoed)
        assertFalse(approve.vetoed)
        assertFalse(unavail.vetoed)
    }

    // -------------------- buildGatePrompt --------------------

    @Test
    fun `buildGatePrompt includes ticker, price, recommendation and decision rules`() {
        val prompt = GeminiGate.buildGatePrompt(item(), strategy = "CSP-Funded Call")
        assertTrue(prompt.contains("TSLA"))
        assertTrue(prompt.contains("\$250.00"))
        assertTrue(prompt.contains("STRONG BUY"))
        assertTrue(prompt.contains("CSP-Funded Call"))
        // Output contract — JSON only, single line, decision keyword set.
        assertTrue(prompt.contains("\"decision\":\"APPROVE or VETO\""))
        assertTrue(prompt.contains("VETO"))
        assertTrue(prompt.contains("APPROVE"))
    }

    @Test
    fun `buildGatePrompt omits strategy line when none supplied`() {
        val prompt = GeminiGate.buildGatePrompt(item(), strategy = null)
        assertFalse(prompt.contains("Strategy on offer:"))
    }

    @Test
    fun `buildGatePrompt copes with missing signal lists`() {
        val prompt = GeminiGate.buildGatePrompt(item(bull = null, bear = null), strategy = null)
        // Default placeholder text should be used so Gemini still gets a coherent prompt.
        assertTrue(prompt.contains("None listed"))
    }

    @Test
    fun `buildGatePrompt forbids inventing data and includes backend chain`() {
        val csp = CspResult(strike = 240.0, premium = 4.50, delta = -0.30, bt = "82%", roc = "2.1%", expiry = "2026-06-19")
        val leap = LongLeapsResult(
            strike = 220.0, expiry = "2027-06-18", premium = 48.0, delta = 0.72,
            intrinsicBuffer = "12%", leverage = "5.2x", bt = "78%"
        )
        val it = item().copy(
            csps = listOf(csp),
            longLeaps = listOf(leap),
            ivRank = "65%",
            discountFromHigh = "-8.4%",
            sma50 = 245.0,
            nextEarningsDate = "2026-07-22",
            levels = StockLevels(
                atr = 6.10, support = 232.0, resistance = 268.0,
                high52w = 280.0, swingLow60d = 225.0, swingHigh60d = 270.0,
                stopLoss = 228.0, target = 285.0, riskReward = 2.30
            ),
        )
        val prompt = GeminiGate.buildGatePrompt(it, strategy = "Cash-Secured Put")
        // Anti-hallucination guard.
        assertTrue(prompt.contains("do NOT invent prices"))
        // CSP chain echoed back.
        assertTrue("CSP shown", prompt.contains("CSP#1: \$240.00 strike"))
        assertTrue("premium shown", prompt.contains("\$4.50 prem"))
        assertTrue("expiry shown", prompt.contains("exp 2026-06-19"))
        assertTrue("BT shown", prompt.contains("BT 82%"))
        // LEAP chain echoed back.
        assertTrue(prompt.contains("LEAP#1: \$220.00 strike"))
        // Levels echoed back.
        assertTrue(prompt.contains("support \$232.00"))
        assertTrue(prompt.contains("resistance \$268.00"))
        assertTrue(prompt.contains("ATR \$6.10"))
        assertTrue(prompt.contains("52w-high \$280.00"))
        // IV rank, off-high, SMA50.
        assertTrue(prompt.contains("IVR 65%"))
        assertTrue(prompt.contains("off-high -8.4%"))
        assertTrue(prompt.contains("SMA50 \$245.00"))
        // Earnings.
        assertTrue(prompt.contains("Next earnings: 2026-07-22"))
        // Earnings veto rule present.
        assertTrue(prompt.contains("earnings is within 7 days"))
    }

    @Test
    fun `buildGatePrompt omits chain section when no contracts present`() {
        val prompt = GeminiGate.buildGatePrompt(item(), strategy = null)
        assertFalse(prompt.contains("Backend option chain"))
    }
}
