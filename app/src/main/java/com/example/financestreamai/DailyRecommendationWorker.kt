package com.example.financestreamai

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
        private const val NOTIFICATION_ID = 9001

        // US market holidays (month-day). Add/update yearly as needed.
        private val US_MARKET_HOLIDAYS_2026 = setOf(
            "01-01", "01-19", "02-16", "04-03", "05-25",
            "07-03", "09-07", "11-26", "12-25"
        )
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            if (!isMarketDay()) {
                Log.d(TAG, "Not a market day — skipping scan.")
                return@withContext Result.success()
            }

            val sharedPrefs = applicationContext.getSharedPreferences("FinanceStreamPrefs", Context.MODE_PRIVATE)
            val watchlist = sharedPrefs.getString("watchlist", null)
                ?.split(",")?.filter { it.isNotBlank() }
                ?: MASTER_WATCHLIST_DEFAULT

            Log.d(TAG, "Starting daily scan for ${watchlist.size} symbols...")

            // Scan in batches of 3 (matching the app's batch size for timeout safety)
            val allResults = mutableListOf<ScanResultItem>()
            val batches = watchlist.chunked(3)

            for ((index, batch) in batches.withIndex()) {
                try {
                    val batchString = batch.joinToString(",")
                    Log.d(TAG, "Batch ${index + 1}/${batches.size}: $batchString")
                    val results = apiService.getScanResults(tickers = batchString)
                    allResults.addAll(results)
                } catch (e: Exception) {
                    Log.e(TAG, "Batch ${index + 1} failed: ${e.message}")
                }
            }

            if (allResults.isEmpty()) {
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

            val totalPicks = topCsps.size + topDiagonals.size + topVerticals.size + topLeaps.size

            if (totalPicks == 0) {
                sendNotification(
                    title = "Daily Scan — No Strong Picks",
                    body = "Scanned ${allResults.size} symbols. No high-confidence opportunities found today. Market conditions may improve — check back tomorrow."
                )
            } else {
                val body = buildRecommendationText(allResults.size, topCsps, topDiagonals, topVerticals, topLeaps)
                sendNotification(
                    title = "Daily Picks — $totalPicks Recommendations",
                    body = body
                )
            }

            Log.d(TAG, "Daily scan complete: ${allResults.size} symbols, $totalPicks picks.")
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
     * exceptional: backtest >= 90%.
     */
    private fun filterTopVerticals(results: List<ScanResultItem>): List<Pair<String, VerticalResult>> {
        return results
            .flatMap { item ->
                (item.verticals ?: emptyList())
                    .filter { vert ->
                        val bt = parseBtPercent(vert.bt)
                        val passesStockGate = isStockFavorableForBullish(item)
                        val exceptionalTrade = bt >= 90.0
                        (passesStockGate || exceptionalTrade) &&
                        vert.netDebit > 0 &&
                        bt >= 80.0
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
                        bt >= 80.0
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
    // Notification Builder
    // ==============================

    private fun buildRecommendationText(
        symbolCount: Int,
        csps: List<Pair<String, CspResult>>,
        diagonals: List<Pair<String, DiagonalResult>>,
        verticals: List<Pair<String, VerticalResult>>,
        leaps: List<Pair<String, LongLeapsResult>>
    ): String {
        val sb = StringBuilder()
        sb.appendLine("Scanned $symbolCount symbols.\n")

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
        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Daily high-confidence trade recommendations"
            }
            notificationManager.createNotificationChannel(channel)
        }

        // Tap notification to open the app
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
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
