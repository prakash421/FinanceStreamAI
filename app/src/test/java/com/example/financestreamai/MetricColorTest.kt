package com.example.financestreamai

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for [metricBucket] — the value-to-band classifier behind the
 * colored RSI/β/IV chips on each scan-result row. The colors themselves
 * are pure consequences of the bucket name, so verifying buckets is
 * sufficient to lock in the legend semantics.
 */
class MetricColorTest {

    // ------------------- RSI -------------------

    @Test fun rsi_oversold() {
        assertEquals("Oversold", metricBucket(MetricKind.RSI, 10.0))
        assertEquals("Oversold", metricBucket(MetricKind.RSI, 29.9))
    }

    @Test fun rsi_cooling() {
        assertEquals("Cooling", metricBucket(MetricKind.RSI, 30.0))
        assertEquals("Cooling", metricBucket(MetricKind.RSI, 39.9))
    }

    @Test fun rsi_healthy_isSweetSpot() {
        assertEquals("Healthy", metricBucket(MetricKind.RSI, 40.0))
        assertEquals("Healthy", metricBucket(MetricKind.RSI, 50.0))
        assertEquals("Healthy", metricBucket(MetricKind.RSI, 60.0))
    }

    @Test fun rsi_climbing() {
        assertEquals("Climbing", metricBucket(MetricKind.RSI, 60.5))
        assertEquals("Climbing", metricBucket(MetricKind.RSI, 70.0))
    }

    @Test fun rsi_overbought() {
        assertEquals("Overbought", metricBucket(MetricKind.RSI, 70.5))
        assertEquals("Overbought", metricBucket(MetricKind.RSI, 95.0))
    }

    // ------------------- BETA -------------------

    @Test fun beta_defensive() {
        assertEquals("Defensive", metricBucket(MetricKind.BETA, 0.3))
        assertEquals("Defensive", metricBucket(MetricKind.BETA, 0.69))
    }

    @Test fun beta_balanced_isSweetSpot() {
        assertEquals("Balanced", metricBucket(MetricKind.BETA, 0.7))
        assertEquals("Balanced", metricBucket(MetricKind.BETA, 1.0))
        assertEquals("Balanced", metricBucket(MetricKind.BETA, 1.3))
    }

    @Test fun beta_high() {
        assertEquals("High", metricBucket(MetricKind.BETA, 1.31))
        assertEquals("High", metricBucket(MetricKind.BETA, 2.0))
    }

    @Test fun beta_veryHigh() {
        assertEquals("Very High", metricBucket(MetricKind.BETA, 2.5))
    }

    // ------------------- IV -------------------

    @Test fun iv_thin_badForSellers() {
        assertEquals("Thin", metricBucket(MetricKind.IV, 5.0))
        assertEquals("Thin", metricBucket(MetricKind.IV, 24.9))
    }

    @Test fun iv_modest() {
        assertEquals("Modest", metricBucket(MetricKind.IV, 25.0))
        assertEquals("Modest", metricBucket(MetricKind.IV, 49.9))
    }

    @Test fun iv_juicy_goodForSellers() {
        assertEquals("Juicy", metricBucket(MetricKind.IV, 50.0))
        assertEquals("Juicy", metricBucket(MetricKind.IV, 75.0))
    }

    @Test fun iv_rich_excellentForSellers() {
        assertEquals("Rich", metricBucket(MetricKind.IV, 76.0))
        assertEquals("Rich", metricBucket(MetricKind.IV, 99.0))
    }

    // ------------------- color helper sanity -------------------

    @Test fun metricColor_returnsBucketAsHint() {
        // The hint string in the chip text equals the bucket name.
        val (_, hint) = metricColor(MetricKind.IV, 80.0)
        assertEquals("Rich", hint)
    }
}
