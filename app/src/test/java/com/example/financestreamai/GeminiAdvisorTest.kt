package com.example.financestreamai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for GeminiAdvisor — independent watchlist ranker.
 * Focus on response parsing and prompt construction.
 */
class GeminiAdvisorTest {

    private fun jsonString(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    private fun geminiBody(text: String): String =
        """{"candidates":[{"content":{"parts":[{"text":${jsonString(text)}}]}}]}"""

    private fun item(
        ticker: String,
        price: Double = 100.0,
        rsi: Double? = 55.0,
        sector: String? = "Tech",
        rec: String? = "BUY",
        bull: List<String>? = listOf("Above SMA200"),
        bear: List<String>? = emptyList(),
    ) = ScanResultItem(
        ticker = ticker, price = price, rsi = rsi, beta = 1.0,
        csps = null, diagonals = null, verticals = null, longLeaps = null,
        sector = sector, overall = rec, stockRecommendation = rec,
        bullishSignals = bull, bearishSignals = bear,
    )

    // -------------------- parsePicks --------------------

    @Test
    fun `parsePicks extracts a clean picks array with conviction and thesis`() {
        val body = geminiBody(
            """{"picks":[
                {"ticker":"NVDA","rank":1,"conviction":"HIGH","thesis":"AI demand still accelerating"},
                {"ticker":"msft","rank":2,"conviction":"medium","thesis":"Steady cloud growth"}
            ]}""".trimIndent()
        )
        val picks = GeminiAdvisor.parsePicks(body)
        assertNotNull(picks)
        assertEquals(2, picks!!.size)
        assertEquals("NVDA", picks[0].ticker)
        assertEquals("HIGH", picks[0].conviction)
        assertEquals(1, picks[0].rank)
        // ticker is uppercased; conviction normalized
        assertEquals("MSFT", picks[1].ticker)
        assertEquals("MEDIUM", picks[1].conviction)
    }

    @Test
    fun `parsePicks accepts a bare picks array (no wrapper object)`() {
        val body = geminiBody(
            """[
                {"ticker":"AAPL","rank":1,"conviction":"HIGH","thesis":"x"},
                {"ticker":"GOOG","rank":2,"conviction":"LOW","thesis":"y"}
            ]"""
        )
        val picks = GeminiAdvisor.parsePicks(body)
        assertNotNull(picks)
        assertEquals(2, picks!!.size)
        assertEquals("AAPL", picks[0].ticker)
        assertEquals("LOW", picks[1].conviction)
    }

    @Test
    fun `parsePicks tolerates markdown fenced JSON wrapper`() {
        val body = geminiBody(
            "Here are my picks:\n```json\n{\"picks\":[{\"ticker\":\"TSLA\",\"rank\":1,\"conviction\":\"HIGH\",\"thesis\":\"reaccel\"}]}\n```"
        )
        val picks = GeminiAdvisor.parsePicks(body)
        assertNotNull(picks)
        assertEquals(1, picks!!.size)
        assertEquals("TSLA", picks[0].ticker)
    }

    @Test
    fun `parsePicks returns null when text contains no JSON`() {
        val body = geminiBody("I have no opinion at this time.")
        assertNull(GeminiAdvisor.parsePicks(body))
    }

    @Test
    fun `parsePicks defaults missing conviction to MEDIUM and skips missing tickers`() {
        val body = geminiBody(
            """{"picks":[
                {"rank":1,"conviction":"HIGH","thesis":"no ticker"},
                {"ticker":"AMZN","rank":2,"thesis":"no conviction"}
            ]}""".trimIndent()
        )
        val picks = GeminiAdvisor.parsePicks(body)
        assertNotNull(picks)
        assertEquals(1, picks!!.size)
        assertEquals("AMZN", picks[0].ticker)
        assertEquals("MEDIUM", picks[0].conviction)
    }

    @Test
    fun `parsePicks deduplicates and sorts by rank`() {
        val body = geminiBody(
            """{"picks":[
                {"ticker":"AAPL","rank":3,"conviction":"LOW","thesis":"a"},
                {"ticker":"NVDA","rank":1,"conviction":"HIGH","thesis":"b"},
                {"ticker":"aapl","rank":5,"conviction":"HIGH","thesis":"dup"}
            ]}""".trimIndent()
        )
        val picks = GeminiAdvisor.parsePicks(body)
        assertNotNull(picks)
        assertEquals(2, picks!!.size)
        assertEquals("NVDA", picks[0].ticker)
        assertEquals("AAPL", picks[1].ticker)
    }

    // -------------------- buildAdvisorPrompt --------------------

    @Test
    fun `buildAdvisorPrompt includes every ticker with compact features`() {
        val items = listOf(
            item("NVDA", price = 800.0, rsi = 65.0, sector = "Tech"),
            item("XOM", price = 110.0, rsi = 48.0, sector = "Energy", rec = "HOLD"),
        )
        val prompt = GeminiAdvisor.buildAdvisorPrompt(items, topN = 3)
        assertTrue(prompt.contains("TOP 3 tickers"))
        assertTrue(prompt.contains("NVDA|\$800.00|RSI=65"))
        assertTrue(prompt.contains("XOM|\$110.00|RSI=48"))
        assertTrue(prompt.contains("sec=Tech"))
        assertTrue(prompt.contains("sec=Energy"))
        // Strict JSON-only output contract
        assertTrue(prompt.contains("\"picks\""))
        assertTrue(prompt.contains("conviction"))
        assertTrue(prompt.contains("thesis"))
    }

    @Test
    fun `buildAdvisorPrompt instructs Gemini not to copy the backend rec`() {
        val prompt = GeminiAdvisor.buildAdvisorPrompt(listOf(item("AAPL")), topN = 1)
        // We want Gemini to be willing to disagree.
        assertTrue(prompt.contains("Do NOT just copy"))
    }

    @Test
    fun `buildAdvisorPrompt forbids inventing data and includes options chain extras`() {
        val full = item("NVDA", price = 800.0, rsi = 65.0, sector = "Tech").copy(
            ivRank = "70%",
            discountFromHigh = "-5%",
            sma50 = 780.0,
            sma200 = 700.0,
            nextEarningsDate = "2026-08-21",
            csps = listOf(CspResult(strike = 770.0, premium = 12.5, delta = -0.30, bt = "85%", roc = "1.6%", expiry = "2026-06-19")),
            longLeaps = listOf(LongLeapsResult(strike = 700.0, expiry = "2027-06-18", premium = 160.0, delta = 0.75, intrinsicBuffer = "10%", leverage = "5x", bt = "80%")),
            levels = StockLevels(atr = 18.0, support = 760.0, resistance = 830.0, high52w = 845.0)
        )
        val prompt = GeminiAdvisor.buildAdvisorPrompt(listOf(full), topN = 3)
        // Anti-hallucination guard.
        assertTrue(prompt.contains("do NOT invent"))
        assertTrue(prompt.contains("backend's live snapshot"))
        // Compact extras line.
        assertTrue("csp extras", prompt.contains("csp:\$770@\$12.50"))
        assertTrue("leap extras", prompt.contains("leap:\$700@\$160.00"))
        assertTrue("levels", prompt.contains("sup=\$760"))
        assertTrue(prompt.contains("res=\$830"))
        assertTrue(prompt.contains("52wH=\$845"))
        // Per-row extra fields.
        assertTrue(prompt.contains("ivr=70%"))
        assertTrue(prompt.contains("off=-5%"))
        assertTrue(prompt.contains("sma50=\$780"))
        assertTrue(prompt.contains("earn=2026-08-21"))
        // Earnings rule present.
        assertTrue(prompt.contains("earnings inside the option expiry window"))
    }
}
