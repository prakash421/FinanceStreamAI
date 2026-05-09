package com.example.financestreamai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the recommendation-bucketing + AI auto-validate gating logic
 * used by the Scan Watchlist results filter chips and AiCrossValidator
 * fan-out.
 */
class RecommendationFilterTest {

    @Test
    fun strongBuy_isBucketedFirst() {
        assertEquals("STRONG BUY", recommendationBucket("STRONG BUY", null))
        assertEquals("STRONG BUY", recommendationBucket("Strong Buy (Confirmed)", null))
        // STRONG BUY must beat plain BUY since both contain "BUY"
        assertEquals("STRONG BUY", recommendationBucket("BUY", "STRONG OPPORTUNITY"))
    }

    @Test
    fun buy_variantsBucketed() {
        assertEquals("BUY", recommendationBucket("BUY", null))
        assertEquals("BUY", recommendationBucket(null, "OPPORTUNITY"))
        assertEquals("BUY", recommendationBucket("Buy — Trending", null))
    }

    @Test
    fun holdAndCaution() {
        assertEquals("HOLD", recommendationBucket("HOLD", null))
        assertEquals("HOLD", recommendationBucket("Neutral", null))
        assertEquals("HOLD", recommendationBucket(null, "CAUTION"))
    }

    @Test
    fun sellAndAvoid() {
        assertEquals("SELL", recommendationBucket("SELL", null))
        assertEquals("SELL", recommendationBucket("Strong Sell", null))
        assertEquals("AVOID", recommendationBucket("AVOID", null))
    }

    @Test
    fun blank_isOther() {
        assertEquals("OTHER", recommendationBucket(null, null))
        assertEquals("OTHER", recommendationBucket("", ""))
        assertEquals("OTHER", recommendationBucket("xyz", null))
    }

    @Test
    fun isBuyRated_acceptsStrongBuyAndBuy() {
        assertTrue(isBuyRated("STRONG BUY", null))
        assertTrue(isBuyRated("BUY", null))
        assertTrue(isBuyRated(null, "OPPORTUNITY"))
    }

    @Test
    fun isBuyRated_rejectsHoldSellAvoid() {
        assertFalse(isBuyRated("HOLD", null))
        assertFalse(isBuyRated("SELL", null))
        assertFalse(isBuyRated("AVOID", null))
        assertFalse(isBuyRated(null, null))
    }
}
