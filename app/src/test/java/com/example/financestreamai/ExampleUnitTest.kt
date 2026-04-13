package com.example.financestreamai

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Test
import org.junit.Assert.*

class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun parseScanResponse_withBasicInfoOnly() {
        // Real API response for NVDA (no strategy matches)
        val json = """
        [
          {
            "ticker": "NVDA",
            "price": 188.63,
            "beta": 1.78,
            "csps": [],
            "diagonals": [],
            "verticals": [],
            "long_leaps": [],
            "iv_rank": "18.4%",
            "rsi": 61.4,
            "discount_from_high": "11.1%",
            "sma200": 180.72
          }
        ]
        """.trimIndent()

        val type = object : TypeToken<List<ScanResultItem>>() {}.type
        val results: List<ScanResultItem> = Gson().fromJson(json, type)

        assertEquals(1, results.size)
        val item = results[0]
        assertEquals("NVDA", item.ticker)
        assertEquals(188.63, item.price, 0.01)
        assertEquals(1.78, item.beta!!, 0.01)
        assertEquals(61.4, item.rsi!!, 0.1)
        assertEquals("18.4%", item.ivRank)
        assertEquals("11.1%", item.discountFromHigh)
        assertEquals(180.72, item.sma200!!, 0.01)
        assertTrue(item.csps!!.isEmpty())
        assertTrue(item.diagonals!!.isEmpty())
        assertTrue(item.verticals!!.isEmpty())
        assertTrue(item.longLeaps!!.isEmpty())
    }

    @Test
    fun parseScanResponse_multipleSymbols() {
        val json = """
        [
          {
            "ticker": "NVDA",
            "price": 188.63,
            "beta": 1.0,
            "csps": [],
            "diagonals": [],
            "verticals": [],
            "long_leaps": [],
            "iv_rank": "18.4%",
            "rsi": 61.4,
            "discount_from_high": "11.1%",
            "sma200": 180.72
          },
          {
            "ticker": "PLTR",
            "price": 128.06,
            "beta": 1.92,
            "csps": [],
            "diagonals": [],
            "verticals": [],
            "long_leaps": [],
            "iv_rank": "32.5%",
            "rsi": 34.1,
            "discount_from_high": "38.3%",
            "sma200": 164.16
          }
        ]
        """.trimIndent()

        val type = object : TypeToken<List<ScanResultItem>>() {}.type
        val results: List<ScanResultItem> = Gson().fromJson(json, type)

        assertEquals(2, results.size)
        assertEquals("NVDA", results[0].ticker)
        assertEquals("PLTR", results[1].ticker)
        assertEquals("32.5%", results[1].ivRank)
        assertEquals("38.3%", results[1].discountFromHigh)
        assertEquals(164.16, results[1].sma200!!, 0.01)
    }

    @Test
    fun parseScanResponse_withStrategyResults() {
        // Simulated response with CSP and LEAPS matches including expiry
        val json = """
        [
          {
            "ticker": "HOOD",
            "price": 66.02,
            "beta": 2.47,
            "csps": [
              {"strike": 55.0, "premium": 2.10, "delta": -0.25, "bt": "Success", "roc": "4.8%", "expiry": "2026-05-15"}
            ],
            "diagonals": [
              {"long": "70C Jan2027", "short": "75C May2026", "net_debt": 8.50, "yield": "12.3%", "bt": "OK", "expiry": "2027-01-15"}
            ],
            "verticals": [
              {"strikes": "60/55P", "net_debit": 1.80, "bt": "Success", "expiry": "2026-05-15"}
            ],
            "long_leaps": [
              {"strike": 50.0, "expiry": "2027-01-15", "premium": 22.50, "delta": 0.75, "intrinsic_buffer": "24.3%", "leverage": "2.9x", "bt": "Success"}
            ],
            "iv_rank": "30.7%",
            "rsi": 35.3,
            "discount_from_high": "57.1%",
            "sma200": 107.77
          }
        ]
        """.trimIndent()

        val type = object : TypeToken<List<ScanResultItem>>() {}.type
        val results: List<ScanResultItem> = Gson().fromJson(json, type)

        assertEquals(1, results.size)
        val item = results[0]
        assertEquals("HOOD", item.ticker)

        // CSP
        assertEquals(1, item.csps!!.size)
        val csp = item.csps!![0]
        assertEquals(55.0, csp.strike, 0.01)
        assertEquals(2.10, csp.premium, 0.01)
        assertEquals("4.8%", csp.roc)
        assertEquals("2026-05-15", csp.expiry)
        assertEquals("Success", csp.bt)

        // Diagonal
        assertEquals(1, item.diagonals!!.size)
        val diag = item.diagonals!![0]
        assertEquals("70C Jan2027", diag.longLeg)
        assertEquals("75C May2026", diag.shortLeg)
        assertEquals("12.3%", diag.yieldRatio)
        assertEquals("2027-01-15", diag.expiry)

        // Vertical
        assertEquals(1, item.verticals!!.size)
        val vert = item.verticals!![0]
        assertEquals("60/55P", vert.strikes)
        assertEquals(1.80, vert.netDebit, 0.01)
        assertEquals("2026-05-15", vert.expiry)

        // LEAPS
        assertEquals(1, item.longLeaps!!.size)
        val leaps = item.longLeaps!![0]
        assertEquals(50.0, leaps.strike, 0.01)
        assertEquals("2027-01-15", leaps.expiry)
        assertEquals("24.3%", leaps.intrinsicBuffer)
        assertEquals("2.9x", leaps.leverage)
    }

    @Test
    fun parseScanResponse_nullOptionalFields() {
        // Response with minimal fields (no iv_rank, no sma200, etc.)
        val json = """
        [
          {
            "ticker": "XYZ",
            "price": 50.0,
            "csps": [],
            "diagonals": [],
            "verticals": [],
            "long_leaps": []
          }
        ]
        """.trimIndent()

        val type = object : TypeToken<List<ScanResultItem>>() {}.type
        val results: List<ScanResultItem> = Gson().fromJson(json, type)

        assertEquals(1, results.size)
        val item = results[0]
        assertEquals("XYZ", item.ticker)
        assertNull(item.rsi)
        assertNull(item.beta)
        assertNull(item.ivRank)
        assertNull(item.discountFromHigh)
        assertNull(item.sma200)
    }
}