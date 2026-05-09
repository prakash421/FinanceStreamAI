package com.example.financestreamai

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs
import java.util.Calendar

class DailyRecommendationWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val TAG = "DailyRecommendation"
        const val CHANNEL_ID = "daily_recommendations"
        const val CHANNEL_NAME = "Daily Trade Recommendations"
        const val MAX_PER_STRATEGY = 5
        const val MAX_TRENDING_PICKS = 5
        private const val NOTIFICATION_ID = 9001
        private const val FLIP_PREFS = "PortfolioFlipPrefs"
        private const val FLIP_KEY = "last_recommendations"

        // US market holidays (month-day). Add/update yearly as needed.
        private val US_MARKET_HOLIDAYS_2026 = setOf(
            "01-01", "01-19", "02-16", "04-03", "05-25",
            "07-03", "09-07", "11-26", "12-25"
        )
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val isManual = tags.contains("DailyRecommendation_manual")
            if (!isManual && !isMarketDay()) {
                Log.d(TAG, "Not a market day — skipping scan.")
                return@withContext Result.success()
            }

            val sharedPrefs = applicationContext.getSharedPreferences("FinanceStreamPrefs", Context.MODE_PRIVATE)
            val watchlist = sharedPrefs.getString("watchlist", null)
                ?.split(",")?.filter { it.isNotBlank() }
                ?: MASTER_WATCHLIST_DEFAULT

            // Include portfolio tickers in the scan so we can detect bull↔bear shifts.
            val portfolio = PortfolioCache.loadActivePositions(applicationContext)
            val portfolioTickers = portfolio.map { it.ticker }.distinct()
            val scanUniverse = (watchlist + portfolioTickers).distinct()

            Log.d(TAG, "Starting daily scan for ${scanUniverse.size} symbols (manual=$isManual)...")

            // Pre-warm the Render free-tier backend so the first batch doesn't
            // time out from a cold start. Best-effort, non-fatal.
            try { apiService.getHealth() } catch (e: Exception) { Log.w(TAG, "Pre-warm failed: ${e.message}") }

            // Scan in batches of 3 (matching the app's batch size for timeout
            // safety). Track which tickers actually returned results so we can
            // retry batches that came back empty due to transient failures.
            val allResults = mutableListOf<ScanResultItem>()
            val droppedTickers = mutableSetOf<String>()
            val batches = scanUniverse.chunked(3)

            for ((index, batch) in batches.withIndex()) {
                val batchString = batch.joinToString(",")
                var success = false
                // Up to 2 attempts per batch with brief backoff.
                for (attempt in 1..2) {
                    try {
                        Log.d(TAG, "Batch ${index + 1}/${batches.size} attempt $attempt: $batchString")
                        val results = apiService.getScanResults(tickers = batchString)
                        allResults.addAll(results)
                        // Mark missing tickers from this batch (API can silently drop bad symbols)
                        val returned = results.map { it.ticker.uppercase() }.toSet()
                        batch.filter { it.uppercase() !in returned }.forEach { droppedTickers.add(it) }
                        success = true
                        break
                    } catch (e: Exception) {
                        Log.e(TAG, "Batch ${index + 1} attempt $attempt failed: ${e.message}")
                        if (attempt < 2) delay(2_000L)
                    }
                }
                if (!success) batch.forEach { droppedTickers.add(it) }
            }

            // Final retry pass for any tickers that were dropped (one-by-one
            // so a single bad symbol doesn't poison its neighbours).
            if (droppedTickers.isNotEmpty()) {
                Log.w(TAG, "Retrying ${droppedTickers.size} dropped ticker(s) individually: $droppedTickers")
                val stillDropped = mutableSetOf<String>()
                for (ticker in droppedTickers.toList()) {
                    try {
                        val results = apiService.getScanResults(tickers = ticker)
                        if (results.isNotEmpty()) allResults.addAll(results) else stillDropped.add(ticker)
                    } catch (e: Exception) {
                        Log.e(TAG, "Retry failed for $ticker: ${e.message}")
                        stillDropped.add(ticker)
                    }
                }
                droppedTickers.clear()
                droppedTickers.addAll(stillDropped)
            }

            Log.d(TAG, "Scan coverage: ${allResults.size}/${scanUniverse.size} symbols. Dropped: $droppedTickers")

            // Trending picks (separate endpoint — best-effort, may be empty if backend cold)
            val trending: List<ScanResultItem> = try {
                apiService.scanTrending(limit = 15)
            } catch (e: Exception) {
                Log.w(TAG, "Trending scan failed: ${e.message}")
                emptyList()
            }

            // Sector context (best-effort)
            val sectorContext: String? = try {
                val rot = apiService.getSectorRotation(period = "1mo")
                val top = rot.topSectors?.take(2)?.joinToString(", ")
                val bot = rot.bottomSectors?.take(2)?.joinToString(", ")
                buildString {
                    if (!top.isNullOrBlank()) append("Leading: $top")
                    if (!bot.isNullOrBlank()) {
                        if (isNotEmpty()) append(" | ")
                        append("Lagging: $bot")
                    }
                }.ifBlank { null }
            } catch (e: Exception) {
                Log.w(TAG, "Sector rotation fetch failed: ${e.message}")
                null
            }

            if (allResults.isEmpty() && trending.isEmpty()) {
                sendNotification(
                    title = "Daily Scan Complete",
                    body = "Could not retrieve data for your watchlist. Server may be busy — try a manual scan later."
                )
                return@withContext Result.success()
            }

            // Filter and rank recommendations
            val topCsps = filterTopCsps(allResults)
            val topDiagonals = filterTopDiagonals(allResults)
            val topVerticals = filterTopVerticals(allResults)
            val topLeaps = filterTopLeaps(allResults)

            // Trending picks with reasoning (top 4-5 by upside potential signal strength)
            val trendingPicksRaw = pickTopTrending(trending)

            // ----------------------------------------------------------
            // Gemini Gate — pre-flight sanity check before user delivery
            // ----------------------------------------------------------
            // The user explicitly asked that every recommendation be passed
            // through Gemini first; if Gemini vetoes a ticker we drop ALL
            // strategies for it from this notification. If no Gemini key is
            // configured (UNAVAILABLE) the gate fails open and the original
            // backend recommendation goes through unchanged.
            val gateInputItems: List<ScanResultItem> = run {
                val gatedTickers = (
                    topCsps.map { it.first } + topDiagonals.map { it.first } +
                        topVerticals.map { it.first } + topLeaps.map { it.first } +
                        trendingPicksRaw.map { it.first.ticker }
                ).map { it.uppercase() }.toSet()
                (allResults + trending).distinctBy { it.ticker.uppercase() }
                    .filter { it.ticker.uppercase() in gatedTickers }
            }
            val gateResults: Map<String, GeminiGate.Result> =
                if (GeminiGate.isEnabled(applicationContext) && gateInputItems.isNotEmpty()) {
                    Log.d(TAG, "Running Gemini gate on ${gateInputItems.size} unique tickers...")
                    GeminiGate.gateAll(applicationContext, gateInputItems)
                } else emptyMap()

            fun keep(ticker: String): Boolean {
                val r = gateResults[ticker.uppercase()] ?: return true
                if (r.vetoed) Log.i(TAG, "Gemini VETO $ticker: ${r.reasoning}")
                return r.approved
            }

            val gatedCsps = topCsps.filter { keep(it.first) }
            val gatedDiagonals = topDiagonals.filter { keep(it.first) }
            val gatedVerticals = topVerticals.filter { keep(it.first) }
            val gatedLeaps = topLeaps.filter { keep(it.first) }
            val trendingPicks = trendingPicksRaw.filter { keep(it.first.ticker) }

            val totalPicks = gatedCsps.size + gatedDiagonals.size + gatedVerticals.size + gatedLeaps.size

            val vetoedTickers = gateResults.values.filter { it.vetoed }.map { it.ticker }.distinct()
            val gateAvailable = gateResults.values.any { it.decision != GeminiGate.Decision.UNAVAILABLE }
            if (vetoedTickers.isNotEmpty()) {
                Log.i(TAG, "Gemini gate dropped ${vetoedTickers.size} tickers: $vetoedTickers")
            }

            // Portfolio bull↔bear flips with reasoning
            val portfolioFlips = detectPortfolioFlips(allResults, portfolioTickers)

            val baseBody = buildEnrichedReport(
                symbolCount = allResults.size,
                universeSize = scanUniverse.size,
                droppedTickers = droppedTickers.toList(),
                topCsps = gatedCsps,
                topDiagonals = gatedDiagonals,
                topVerticals = gatedVerticals,
                topLeaps = gatedLeaps,
                trendingPicks = trendingPicks,
                portfolioFlips = portfolioFlips,
                sectorContext = sectorContext
            )

            // ----------------------------------------------------------
            // Gemini Advisor — proactive watchlist ranker
            // ----------------------------------------------------------
            // Independent of the gate: ask Gemini to nominate its own top
            // picks from the entire scanned universe so we can (a) star the
            // backend recommendations that Gemini also liked (high
            // conviction) and (b) surface promising names the backend
            // missed.
            val advisor = if (GeminiAdvisor.isEnabled(applicationContext) && allResults.isNotEmpty()) {
                Log.d(TAG, "Asking Gemini advisor to rank ${allResults.size} tickers...")
                GeminiAdvisor.rankUniverse(applicationContext, allResults + trending, topN = 5)
            } else GeminiAdvisor.Result(emptyList(), available = false)

            val backendTickers: Set<String> = (
                gatedCsps.map { it.first } + gatedDiagonals.map { it.first } +
                    gatedVerticals.map { it.first } + gatedLeaps.map { it.first } +
                    trendingPicks.map { it.first.ticker }
            ).map { it.uppercase() }.toSet()

            val advisorSection: String = if (advisor.available && advisor.picks.isNotEmpty()) {
                val sb = StringBuilder()
                sb.append("\n\n🤖 Gemini's independent picks (next 4–12 weeks):")
                advisor.picks.forEachIndexed { idx, p ->
                    val overlap = if (p.ticker in backendTickers) " ⭐ also in our picks" else " 🆕 not in backend list"
                    sb.append("\n  ${idx + 1}. ${p.ticker} [${p.conviction}]$overlap")
                    if (p.thesis.isNotBlank()) sb.append("\n     ${p.thesis}")
                }
                val overlapCount = advisor.picks.count { it.ticker in backendTickers }
                sb.append("\n  → ${overlapCount}/${advisor.picks.size} agree with backend (high conviction overlap).")
                sb.toString()
            } else ""

            val body = buildString {
                append(baseBody)
                if (gateAvailable) {
                    append("\n\n🔍 Gemini gate: ")
                    if (vetoedTickers.isEmpty()) append("all picks approved.")
                    else append("vetoed ${vetoedTickers.size} ticker${if (vetoedTickers.size > 1) "s" else ""} — ${vetoedTickers.joinToString(", ")}")
                }
                append(advisorSection)
            }

            val title = when {
                portfolioFlips.isNotEmpty() ->
                    "⚠️ ${portfolioFlips.size} Portfolio Shift" + (if (portfolioFlips.size > 1) "s" else "") + " — $totalPicks Picks"
                totalPicks == 0 && trendingPicks.isEmpty() ->
                    "Daily Scan — No Strong Picks"
                totalPicks == 0 ->
                    "Daily Trends — ${trendingPicks.size} Movers"
                else ->
                    "Daily Picks — $totalPicks Recommendations"
            }
            sendNotification(title = title, body = body)

            Log.d(TAG, "Daily scan complete: ${allResults.size} symbols, $totalPicks picks, ${trendingPicks.size} trending, ${portfolioFlips.size} flips.")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Daily scan failed: ${e.message}")
            // Retry once, then give up (don't spam notifications on persistent failure)
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }

    // ==============================
    // Quality Filters per Strategy
    // ==============================
    // Calibrated against real API data (Apr 2026). Typical CSP ROC is 2-3.5%,
    // delta -0.20 to -0.26. Thresholds set to keep ~60-80% of results while
    // filtering out genuinely poor trades.
    //
    // Stock-level pre-filters gate on RSI/IV rank/discount to avoid unhealthy
    // stocks, BUT exceptional trade metrics (high backtest %, high ROC/yield)
    // can bypass the stock gate on a case-by-case basis.

    /**
     * Stock-level pre-filter for put-selling strategies (CSPs).
     * Returns true if stock conditions are normal/favorable:
     *   - RSI > 25 (not in freefall)
     *   - Discount from high < 40% (not in severe drawdown)
     *   - IV rank >= 15% (enough premium to collect)
     */
    private fun isStockFavorableForPutSelling(item: ScanResultItem): Boolean {
        val rsi = item.rsi ?: return true
        val ivr = item.ivRank.parseToDouble()
        val discount = item.discountFromHigh.parseToDouble()
        return rsi > 25 && discount < 40 && ivr >= 15.0
    }

    /**
     * Stock-level pre-filter for bullish strategies (LEAPS, Diagonals, Verticals).
     * Returns true if stock conditions are normal/favorable:
     *   - RSI < 75 (not overbought)
     *   - Price above SMA200, or discount >= 15% (value entry)
     */
    private fun isStockFavorableForBullish(item: ScanResultItem): Boolean {
        val rsi = item.rsi ?: return true
        val discount = item.discountFromHigh.parseToDouble()
        val aboveSma = if (item.sma200 != null) item.price >= item.sma200 else true
        return rsi < 75 && (aboveSma || discount >= 15.0)
    }

    /**
     * CSPs: Balanced quality filter with stock-health gate + bypass.
     * Stock must pass put-selling conditions, UNLESS the trade itself is
     * exceptional: backtest >= 90% OR ROC >= 3%.
     */
    private fun filterTopCsps(results: List<ScanResultItem>): List<Pair<String, CspResult>> {
        return results
            .flatMap { item ->
                (item.csps ?: emptyList())
                    .filter { csp ->
                        val roc = csp.roc.parseToDouble()
                        val bt = parseBtPercent(csp.bt)
                        val passesStockGate = isStockFavorableForPutSelling(item)
                        val exceptionalTrade = bt >= 90.0 || roc >= 3.0
                        (passesStockGate || exceptionalTrade) &&
                        roc >= 2.0 &&
                        csp.delta in -0.35..-0.15 &&
                        bt >= 80.0
                    }
                    .map { item.ticker to it }
            }
            .sortedByDescending { it.second.roc.parseToDouble() }
            .take(MAX_PER_STRATEGY)
    }

    /**
     * Diagonals: Balanced quality filter with stock-health gate + bypass.
     * Stock must pass bullish conditions, UNLESS the trade itself is
     * exceptional: backtest >= 85% OR yield >= 20%.
     */
    private fun filterTopDiagonals(results: List<ScanResultItem>): List<Pair<String, DiagonalResult>> {
        return results
            .flatMap { item ->
                (item.diagonals ?: emptyList())
                    .filter { diag ->
                        val yld = diag.yieldRatio.parseToDouble()
                        val bt = parseBtPercent(diag.bt)
                        val passesStockGate = isStockFavorableForBullish(item)
                        val exceptionalTrade = bt >= 85.0 || yld >= 20.0
                        (passesStockGate || exceptionalTrade) &&
                        yld >= 5.0 &&
                        diag.netDebt > 0 &&
                        bt >= 70.0
                    }
                    .map { item.ticker to it }
            }
            .sortedByDescending { it.second.yieldRatio.parseToDouble() }
            .take(MAX_PER_STRATEGY)
    }

    /**
     * Verticals: Balanced quality filter with stock-health gate + bypass.
     * Stock must pass bullish conditions, UNLESS the trade itself is
     * exceptional: backtest >= 92%.
     *
     * Calibration note (May 2026): backend stats show vertical strategy
     * historical win-rate is only 41.6% over 262 samples — the worst of
     * any strategy. Raised minimum backtest from 80% to 85%, and exceptional
     * bypass from 90% to 92%, to filter out the long tail of losers.
     */
    private fun filterTopVerticals(results: List<ScanResultItem>): List<Pair<String, VerticalResult>> {
        return results
            .flatMap { item ->
                (item.verticals ?: emptyList())
                    .filter { vert ->
                        val bt = parseBtPercent(vert.bt)
                        val passesStockGate = isStockFavorableForBullish(item)
                        val exceptionalTrade = bt >= 92.0
                        (passesStockGate || exceptionalTrade) &&
                        vert.netDebit > 0 &&
                        bt >= 85.0
                    }
                    .map { item.ticker to it }
            }
            .sortedWith(compareByDescending<Pair<String, VerticalResult>> { parseBtPercent(it.second.bt) }
                .thenBy { it.second.netDebit })
            .take(MAX_PER_STRATEGY)
    }

    /**
     * LEAPS: Balanced quality filter with stock-health gate + bypass.
     * Stock must pass bullish conditions, UNLESS the trade itself is
     * exceptional: backtest >= 95% AND buffer >= 50%.
     *
     * Calibration note (May 2026): backend stats show long_leap historical
     * win-rate is 53.5% over 101 samples — mediocre. Raised minimum backtest
     * from 80% to 85% to push toward higher-conviction setups.
     */
    private fun filterTopLeaps(results: List<ScanResultItem>): List<Pair<String, LongLeapsResult>> {
        return results
            .flatMap { item ->
                (item.longLeaps ?: emptyList())
                    .filter { leaps ->
                        val bt = parseBtPercent(leaps.bt)
                        val buffer = leaps.intrinsicBuffer.parseToDouble()
                        val passesStockGate = isStockFavorableForBullish(item)
                        val exceptionalTrade = bt >= 95.0 && buffer >= 50.0
                        (passesStockGate || exceptionalTrade) &&
                        leaps.delta >= 0.70 &&
                        leaps.leverage.parseToDouble() >= 1.5 &&
                        buffer >= 10.0 &&
                        bt >= 85.0
                    }
                    .map { item.ticker to it }
            }
            .sortedByDescending { it.second.intrinsicBuffer.parseToDouble() }
            .take(MAX_PER_STRATEGY)
    }

    /** Parse backtest string like "90.6%" or "100.0%" to a Double. Returns 0 if null/unparseable. */
    private fun parseBtPercent(bt: String?): Double {
        if (bt == null) return 0.0
        return bt.replace("%", "").trim().toDoubleOrNull() ?: 0.0
    }

    // ==============================
    // Trending picks + reasoning
    // ==============================

    /**
     * Pick the 4-5 strongest trending names with positive 1-2 week upside.
     * Ranked by a composite of (a) momentum quality, (b) bullish-signal count,
     * and (c) analyst upside. We deliberately drop names that look exhausted
     * (RSI > 75 with no pullback signal) or that the API itself flagged as
     * Sell/Avoid.
     */
    private fun pickTopTrending(trending: List<ScanResultItem>): List<Pair<ScanResultItem, String>> {
        if (trending.isEmpty()) return emptyList()
        return trending
            .asSequence()
            .filter { item ->
                val rec = (item.stockRecommendation ?: item.overall ?: "").uppercase()
                if (rec.contains("SELL") || rec.contains("AVOID")) return@filter false
                val rsi = item.rsi ?: 50.0
                rsi in 30.0..78.0  // exclude oversold-collapse and blow-off-top
            }
            .sortedByDescending { item -> trendingScore(item) }
            .take(MAX_TRENDING_PICKS)
            .map { it to buildReasoning(it, bullishContext = true) }
            .toList()
    }

    /**
     * Composite score that prefers names with strong technical posture and
     * room to run over the next 1-2 weeks. Higher = more upside potential.
     */
    private fun trendingScore(item: ScanResultItem): Double {
        val rsi = item.rsi ?: 50.0
        val sma50 = item.sma50
        val sma200 = item.sma200
        val price = item.price
        val analystUpside = item.analystTarget?.upsidePct ?: 0.0
        val bullCount = item.bullishSignals?.size ?: 0
        val bearCount = item.bearishSignals?.size ?: 0

        // Sweet-spot RSI band (45-65): strong-but-not-overbought
        val rsiScore = when {
            rsi in 45.0..65.0 -> 20.0
            rsi in 40.0..70.0 -> 10.0
            else -> 0.0
        }
        // Golden cross / above both MAs
        val maScore = when {
            sma50 != null && sma200 != null && price >= sma50 && sma50 >= sma200 -> 25.0
            sma50 != null && price >= sma50 -> 12.0
            else -> 0.0
        }
        // Capped analyst upside contribution (10pts per 5%, max 25)
        val upsideScore = (analystUpside / 5.0 * 10.0).coerceIn(0.0, 25.0)
        // Net signals
        val signalScore = (bullCount - bearCount).toDouble().coerceIn(-10.0, 15.0)

        return rsiScore + maScore + upsideScore + signalScore
    }

    // ==============================
    // Portfolio bull↔bear shift detection
    // ==============================

    /**
     * Compare today's stock recommendation for each portfolio ticker against
     * the last cached recommendation. Returns the list of meaningful shifts
     * (BUY ↔ SELL/AVOID, or HOLD → BUY/SELL) with technical reasoning.
     */
    private fun detectPortfolioFlips(
        results: List<ScanResultItem>,
        portfolioTickers: List<String>
    ): List<Pair<ScanResultItem, String>> {
        if (portfolioTickers.isEmpty()) return emptyList()

        val portfolioSet = portfolioTickers.toSet()
        val portfolioResults = results.filter { it.ticker in portfolioSet }
        if (portfolioResults.isEmpty()) return emptyList()

        val prefs = applicationContext.getSharedPreferences(FLIP_PREFS, Context.MODE_PRIVATE)
        val previousJson = prefs.getString(FLIP_KEY, null)
        val previousMap: Map<String, String> = if (previousJson != null) {
            try {
                gson.fromJson(
                    previousJson,
                    object : com.google.gson.reflect.TypeToken<Map<String, String>>() {}.type
                )
            } catch (_: Exception) { emptyMap() }
        } else emptyMap()

        val currentMap = mutableMapOf<String, String>()
        val flips = mutableListOf<Pair<ScanResultItem, String>>()

        for (item in portfolioResults) {
            val current = item.stockRecommendation ?: item.overall ?: continue
            currentMap[item.ticker] = current
            val previous = previousMap[item.ticker] ?: continue
            if (isBullBearShift(previous, current)) {
                val direction = if (isBearish(current)) "🔻 BULL→BEAR" else "🔺 BEAR→BULL"
                val reasoning = buildReasoning(item, bullishContext = !isBearish(current))
                val line = "$direction  ($previous → $current)\n    $reasoning"
                flips.add(item to line)
            }
        }

        // Persist current map for next run.
        prefs.edit().putString(FLIP_KEY, gson.toJson(currentMap)).apply()
        return flips
    }

    private fun isBullish(rec: String): Boolean {
        val u = rec.uppercase()
        return u.contains("BUY") && !u.contains("DON'T") && !u.contains("DO NOT")
    }

    private fun isBearish(rec: String): Boolean {
        val u = rec.uppercase()
        return u.contains("SELL") || u.contains("AVOID") || u.contains("BEARISH")
    }

    private fun isBullBearShift(prev: String, curr: String): Boolean {
        return (isBullish(prev) && isBearish(curr)) || (isBearish(prev) && isBullish(curr))
    }

    /**
     * Build a 1-line technical-reasoning string explaining why the stock
     * is moving. Pulls from RSI bands, SMA50/200 posture (golden/death cross,
     * pullback to MA), Bollinger-implied stretch (distance to swing/52w),
     * IV rank (vol expansion = options-friendly), volume hints from the
     * backend signals, sector context, and analyst upside.
     */
    private fun buildReasoning(item: ScanResultItem, bullishContext: Boolean): String {
        val parts = mutableListOf<String>()
        val rsi = item.rsi
        val sma50 = item.sma50
        val sma200 = item.sma200
        val price = item.price

        // RSI band
        if (rsi != null) {
            val r = "%.0f".format(rsi)
            parts += when {
                rsi >= 70 -> "RSI $r (overbought)"
                rsi >= 60 -> "RSI $r (strong momentum)"
                rsi in 45.0..60.0 -> "RSI $r (healthy uptrend)"
                rsi in 35.0..45.0 -> "RSI $r (cooling, potential dip-buy)"
                rsi < 30 -> "RSI $r (oversold)"
                else -> "RSI $r"
            }
        }

        // SMA posture
        if (sma50 != null && sma200 != null) {
            val above50 = price >= sma50
            val above200 = price >= sma200
            val golden = sma50 >= sma200
            parts += when {
                golden && above50 && above200 -> "price > SMA50 > SMA200 (golden-cross trend intact)"
                !golden && !above50 && !above200 -> "price < SMA50 < SMA200 (death-cross, downtrend)"
                golden && !above50 -> "pullback to SMA50 in uptrend"
                !golden && above50 -> "reclaim of SMA50 in downtrend (early reversal)"
                else -> "mixed MA posture"
            }
        } else if (sma50 != null) {
            parts += if (price >= sma50) "above SMA50" else "below SMA50"
        }

        // Bollinger / range stretch via 52w & swing levels
        item.levels?.let { lv ->
            val high52 = lv.high52w
            val swingLow = lv.swingLow60d
            if (high52 != null && high52 > 0) {
                val distPct = (high52 - price) / high52 * 100.0
                when {
                    distPct < 3 -> parts += "within 3% of 52w high (BB upper stretch)"
                    distPct in 3.0..10.0 -> parts += "%.0f%% off 52w high (room to run)".format(distPct)
                    distPct > 30 -> parts += "%.0f%% off 52w high (deep value or weakness)".format(distPct)
                    else -> {}
                }
            }
            if (swingLow != null && swingLow > 0 && abs(price - swingLow) / swingLow < 0.03) {
                parts += "holding 60-day swing-low support"
            }
        }

        // IV rank — options-relevant
        val ivr = item.ivRank.parseToDouble()
        if (ivr >= 60) parts += "IV rank ${ivr.toInt()}% (premium-rich, vol expansion)"
        else if (ivr in 25.0..40.0) parts += "IV rank ${ivr.toInt()}% (moderate)"

        // Volume / signal hints surfaced by backend
        val signalsSrc = if (bullishContext) item.bullishSignals else item.bearishSignals
        signalsSrc?.firstOrNull { it.contains("volume", true) || it.contains("vol", true) }
            ?.let { parts += it }

        // Sector
        item.sector?.takeIf { it.isNotBlank() }?.let { parts += "sector: $it" }

        // Analyst upside
        val up = item.analystTarget?.upsidePct
        if (up != null && up > 0) parts += "analyst upside %.0f%%".format(up)

        return parts.take(5).joinToString(" • ")
    }

    // ==============================
    // Notification Builder
    // ==============================

    private fun buildEnrichedReport(
        symbolCount: Int,
        universeSize: Int,
        droppedTickers: List<String>,
        topCsps: List<Pair<String, CspResult>>,
        topDiagonals: List<Pair<String, DiagonalResult>>,
        topVerticals: List<Pair<String, VerticalResult>>,
        topLeaps: List<Pair<String, LongLeapsResult>>,
        trendingPicks: List<Pair<ScanResultItem, String>>,
        portfolioFlips: List<Pair<ScanResultItem, String>>,
        sectorContext: String?
    ): String {
        val sb = StringBuilder()
        sb.appendLine("Coverage: $symbolCount / $universeSize symbols.")
        if (droppedTickers.isNotEmpty()) {
            val list = droppedTickers.take(10).joinToString(", ")
            val more = if (droppedTickers.size > 10) " (+${droppedTickers.size - 10} more)" else ""
            sb.appendLine("⚠️ Skipped: $list$more")
        }
        if (sectorContext != null) sb.appendLine("🔄 Sectors — $sectorContext")
        sb.appendLine()

        if (portfolioFlips.isNotEmpty()) {
            sb.appendLine("📢 Portfolio Shifts (${portfolioFlips.size}):")
            portfolioFlips.forEach { (item, line) ->
                sb.appendLine("  ${item.ticker} — $line")
            }
            sb.appendLine()
        }

        if (trendingPicks.isNotEmpty()) {
            sb.appendLine("🚀 Top Trending (next 1-2 weeks upside):")
            trendingPicks.forEach { (item, reasoning) ->
                val change = item.changePercent?.let { " %+.1f%%".format(it) } ?: ""
                sb.appendLine("  ${item.ticker} \$${"%.2f".format(item.price)}$change")
                sb.appendLine("    $reasoning")
            }
            sb.appendLine()
        }

        sb.append(buildRecommendationText(symbolCount, topCsps, topDiagonals, topVerticals, topLeaps, headerOnly = true))
        return sb.toString().trim()
    }

    private fun buildRecommendationText(
        symbolCount: Int,
        csps: List<Pair<String, CspResult>>,
        diagonals: List<Pair<String, DiagonalResult>>,
        verticals: List<Pair<String, VerticalResult>>,
        leaps: List<Pair<String, LongLeapsResult>>,
        headerOnly: Boolean = false
    ): String {
        val sb = StringBuilder()
        if (!headerOnly) sb.appendLine("Scanned $symbolCount symbols.\n")

        if (csps.isNotEmpty()) {
            sb.appendLine("📊 CSPs (${csps.size}):")
            csps.forEach { (ticker, csp) ->
                val exp = if (csp.expiry != null) " ${csp.expiry}" else ""
                sb.appendLine("  $ticker $${csp.strike}$exp — ROC: ${csp.roc}, Δ: ${csp.delta}")
            }
            sb.appendLine()
        }

        if (diagonals.isNotEmpty()) {
            sb.appendLine("📐 Diagonals (${diagonals.size}):")
            diagonals.forEach { (ticker, diag) ->
                val exp = if (diag.expiry != null) " ${diag.expiry}" else ""
                sb.appendLine("  $ticker ${diag.longLeg ?: "?"}/${diag.shortLeg ?: "?"}$exp — Yield: ${diag.yieldRatio}")
            }
            sb.appendLine()
        }

        if (verticals.isNotEmpty()) {
            sb.appendLine("📈 Verticals (${verticals.size}):")
            verticals.forEach { (ticker, vert) ->
                val exp = if (vert.expiry != null) " ${vert.expiry}" else ""
                sb.appendLine("  $ticker ${vert.strikes ?: "N/A"}$exp — Debit: $${vert.netDebit}")
            }
            sb.appendLine()
        }

        if (leaps.isNotEmpty()) {
            sb.appendLine("🔭 LEAPS (${leaps.size}):")
            leaps.forEach { (ticker, l) ->
                sb.appendLine("  $ticker $${l.strike}C ${l.expiry} — Lev: ${l.leverage}, Buffer: ${l.intrinsicBuffer}")
            }
        }

        return sb.toString().trim()
    }

    private fun sendNotification(title: String, body: String) {
        // Save to notification history so user can review past alerts
        NotificationCache.save(applicationContext, title, body)

        // Permission / channel check (Android 13+ requires runtime POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                Log.w(TAG, "POST_NOTIFICATIONS not granted — system will drop notify(). Saved to in-app history only.")
                return
            }
        }
        if (!NotificationManagerCompat.from(applicationContext).areNotificationsEnabled()) {
            Log.w(TAG, "Notifications disabled at app level. Saved to in-app history only.")
            return
        }

        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Daily high-confidence trade recommendations"
            }
            notificationManager.createNotificationChannel(channel)
        }

        // Tap notification to open the app on the Alerts tab
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("navigate_to", "notifications")
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body.lines().first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    // ==============================
    // Market Day Check
    // ==============================

    private fun isMarketDay(): Boolean {
        val cal = Calendar.getInstance()
        val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)

        // Weekend check
        if (dayOfWeek == Calendar.SATURDAY || dayOfWeek == Calendar.SUNDAY) {
            return false
        }

        // Holiday check (simple month-day format)
        val monthDay = "%02d-%02d".format(cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
        if (US_MARKET_HOLIDAYS_2026.contains(monthDay)) {
            return false
        }

        return true
    }
}
