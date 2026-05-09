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
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar
import kotlin.math.abs

/**
 * Hourly market-hours scan that watches:
 *   1. The user's active portfolio tickers, and
 *   2. The trending / most-active universe
 *
 * Emits an immediate notification ONLY when the thesis on a stock has
 * materially changed since the last scan (recommendation tier shift, large
 * intraday price move, or RSI threshold cross). Each alert includes a
 * one-line reasoning summary explaining why the thesis flipped.
 */
class PortfolioFlipWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val TAG = "PortfolioFlipScan"
        const val CHANNEL_ID = "portfolio_flip_alerts"
        const val CHANNEL_NAME = "Portfolio & Trending Alerts"
        private const val NOTIFICATION_ID = 9002
        private const val PREFS_NAME = "PortfolioFlipPrefs"
        private const val KEY_RECOMMENDATIONS = "last_recommendations"   // legacy: rec-only map
        private const val KEY_SNAPSHOTS = "last_snapshots"               // new: full per-ticker snapshot
        private const val TRENDING_LIMIT = 12

        // Material-change thresholds
        private const val MATERIAL_PRICE_MOVE_PCT = 5.0   // ±5% since last snapshot
        private const val MATERIAL_RSI_DELTA = 15.0       // ±15 RSI points since last snapshot

        private val US_MARKET_HOLIDAYS_2026 = setOf(
            "01-01", "01-19", "02-16", "04-03", "05-25",
            "07-03", "09-07", "11-26", "12-25"
        )
    }

    /** Lightweight per-ticker snapshot persisted between hourly runs. */
    data class Snapshot(
        val recommendation: String,
        val price: Double,
        val rsi: Double?,
        val bullCount: Int,
        val bearCount: Int,
        val timestamp: Long
    )

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            if (!isMarketOpen()) {
                Log.d(TAG, "Market not open — skipping hourly scan.")
                return@withContext Result.success()
            }

            val portfolio = PortfolioCache.loadActivePositions(applicationContext)
            val portfolioTickers = portfolio.map { it.ticker }.distinct()

            // Trending universe (best-effort — empty list if backend unavailable).
            val trending: List<ScanResultItem> = try {
                apiService.scanTrending(limit = TRENDING_LIMIT)
            } catch (e: Exception) {
                Log.w(TAG, "Trending fetch failed: ${e.message}")
                emptyList()
            }
            val trendingTickers = trending.map { it.ticker }

            val universe = (portfolioTickers + trendingTickers).distinct()
            if (universe.isEmpty()) {
                Log.d(TAG, "No portfolio or trending tickers — nothing to scan.")
                return@withContext Result.success()
            }

            Log.d(TAG, "Hourly scan: ${portfolioTickers.size} portfolio + ${trendingTickers.size} trending = ${universe.size} unique tickers")

            // Re-use the trending payload where possible to avoid extra API hits.
            val trendingByTicker = trending.associateBy { it.ticker }
            val needsScan = universe.filter { it !in trendingByTicker }
            val freshlyScanned = mutableListOf<ScanResultItem>()
            for (batch in needsScan.chunked(5)) {
                try {
                    freshlyScanned.addAll(apiService.getScanResults(tickers = batch.joinToString(",")))
                } catch (e: Exception) {
                    Log.e(TAG, "Batch ${batch.joinToString(",")} failed: ${e.message}")
                }
            }
            val current: List<ScanResultItem> = (trending + freshlyScanned).distinctBy { it.ticker }
            if (current.isEmpty()) return@withContext Result.success()

            // Load previous snapshots
            val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val previousJson = prefs.getString(KEY_SNAPSHOTS, null)
            val previous: Map<String, Snapshot> = if (previousJson != null) {
                try {
                    gson.fromJson(previousJson, object : TypeToken<Map<String, Snapshot>>() {}.type)
                } catch (_: Exception) { emptyMap() }
            } else emptyMap()

            val newSnapshots = mutableMapOf<String, Snapshot>()
            val portfolioSet = portfolioTickers.toSet()
            val portfolioAlerts = mutableListOf<String>()
            val trendingAlerts = mutableListOf<String>()
            // Legacy rec-only map still maintained so DailyRecommendationWorker keeps working.
            val recOnly = mutableMapOf<String, String>()

            for (item in current) {
                val rec = item.stockRecommendation ?: item.overall ?: continue
                val snap = Snapshot(
                    recommendation = rec,
                    price = item.price,
                    rsi = item.rsi,
                    bullCount = item.bullishSignals?.size ?: 0,
                    bearCount = item.bearishSignals?.size ?: 0,
                    timestamp = System.currentTimeMillis()
                )
                newSnapshots[item.ticker] = snap
                recOnly[item.ticker] = rec

                val prev = previous[item.ticker] ?: continue
                val change = detectMaterialChange(prev, snap, item) ?: continue

                val arrow = if (change.bullish) "🔺" else "🔻"
                val reasoning = buildReasoning(item, change.bullish)
                val line = buildString {
                    append("$arrow ${item.ticker} — ${change.headline}")
                    appendLine()
                    append("    $reasoning")
                }
                if (item.ticker in portfolioSet) portfolioAlerts.add(line) else trendingAlerts.add(line)
            }

            // Persist for next run.
            prefs.edit()
                .putString(KEY_SNAPSHOTS, gson.toJson(newSnapshots))
                .putString(KEY_RECOMMENDATIONS, gson.toJson(recOnly))
                .apply()

            val totalAlerts = portfolioAlerts.size + trendingAlerts.size
            if (totalAlerts == 0) {
                Log.d(TAG, "No material thesis changes this hour.")
                return@withContext Result.success()
            }

            val sb = StringBuilder()
            if (portfolioAlerts.isNotEmpty()) {
                sb.appendLine("📂 Portfolio (${portfolioAlerts.size}):")
                portfolioAlerts.forEach { sb.appendLine(it) }
                if (trendingAlerts.isNotEmpty()) sb.appendLine()
            }
            if (trendingAlerts.isNotEmpty()) {
                sb.appendLine("🔥 Trending (${trendingAlerts.size}):")
                trendingAlerts.forEach { sb.appendLine(it) }
            }

            val title = when {
                portfolioAlerts.isNotEmpty() && trendingAlerts.isEmpty() ->
                    "⚠️ Portfolio Alert — ${portfolioAlerts.size} thesis change${pluralS(portfolioAlerts.size)}"
                portfolioAlerts.isEmpty() ->
                    "🔥 Market Alert — ${trendingAlerts.size} trending mover${pluralS(trendingAlerts.size)}"
                else ->
                    "⚠️ ${portfolioAlerts.size} portfolio + ${trendingAlerts.size} trending alerts"
            }

            sendNotification(title = title, body = sb.toString().trim())
            Log.d(TAG, "Sent notification with $totalAlerts material change(s).")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Hourly scan failed: ${e.message}")
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }

    private fun pluralS(n: Int) = if (n == 1) "" else "s"

    // ==============================
    // Material change detection
    // ==============================

    private data class Change(val bullish: Boolean, val headline: String)

    /**
     * Returns a Change description if any of these criteria fire vs the
     * previous snapshot:
     *   - Recommendation tier shift (mapped to numeric scale)
     *   - Price moved >= ±5% since last snapshot
     *   - RSI shifted by >= 15 points OR crossed 30/70 line
     * Returns null if nothing material changed.
     */
    private fun detectMaterialChange(prev: Snapshot, now: Snapshot, item: ScanResultItem): Change? {
        val prevTier = recTier(prev.recommendation)
        val nowTier = recTier(now.recommendation)
        val tierDelta = nowTier - prevTier

        // 1) Recommendation tier shift (>=2 = e.g. HOLD→STRONG BUY, or BUY→AVOID)
        if (abs(tierDelta) >= 2) {
            return Change(
                bullish = tierDelta > 0,
                headline = "Thesis shift: ${prev.recommendation} → ${now.recommendation}"
            )
        }
        // 1b) Any cross of the bull/bear midline (HOLD or above ↔ SELL/AVOID)
        if ((prevTier >= 0) != (nowTier >= 0) && abs(tierDelta) >= 1) {
            return Change(
                bullish = tierDelta > 0,
                headline = "Direction flipped: ${prev.recommendation} → ${now.recommendation}"
            )
        }

        // 2) Material price move
        if (prev.price > 0) {
            val pct = (now.price - prev.price) / prev.price * 100.0
            if (abs(pct) >= MATERIAL_PRICE_MOVE_PCT) {
                val daily = item.changePercent
                val dailyTxt = if (daily != null) " (today %+.1f%%)".format(daily) else ""
                return Change(
                    bullish = pct > 0,
                    headline = "Price %+.1f%% since last scan$dailyTxt".format(pct)
                )
            }
        }

        // 3) RSI threshold cross or large jump
        if (prev.rsi != null && now.rsi != null) {
            val crossedOversold = (prev.rsi < 30 && now.rsi >= 30) || (prev.rsi >= 30 && now.rsi < 30)
            val crossedOverbought = (prev.rsi > 70 && now.rsi <= 70) || (prev.rsi <= 70 && now.rsi > 70)
            val rsiDelta = now.rsi - prev.rsi
            when {
                crossedOversold && rsiDelta > 0 ->
                    return Change(true, "RSI escaped oversold (%.0f → %.0f)".format(prev.rsi, now.rsi))
                crossedOversold && rsiDelta < 0 ->
                    return Change(false, "RSI fell into oversold (%.0f → %.0f)".format(prev.rsi, now.rsi))
                crossedOverbought && rsiDelta < 0 ->
                    return Change(false, "RSI cooled from overbought (%.0f → %.0f)".format(prev.rsi, now.rsi))
                crossedOverbought && rsiDelta > 0 ->
                    return Change(true, "RSI broke into overbought (%.0f → %.0f)".format(prev.rsi, now.rsi))
                abs(rsiDelta) >= MATERIAL_RSI_DELTA ->
                    return Change(rsiDelta > 0, "RSI %+.0f (%.0f → %.0f)".format(rsiDelta, prev.rsi, now.rsi))
            }
        }

        return null
    }

    /**
     * Map a recommendation string to a numeric tier:
     *   +2 STRONG BUY   +1 BUY   0 HOLD/NEUTRAL   -1 SELL   -2 AVOID/STRONG SELL
     */
    private fun recTier(rec: String): Int {
        val u = rec.uppercase()
        return when {
            u.contains("STRONG BUY") -> 2
            u.contains("STRONG SELL") || u.contains("AVOID") -> -2
            u.contains("BUY") && !u.contains("DON'T") && !u.contains("DO NOT") -> 1
            u.contains("SELL") -> -1
            else -> 0  // HOLD / NEUTRAL / unknown
        }
    }

    /**
     * One-line reasoning that summarises what's driving the move (technical
     * posture + sector context + analyst target). Mirrors the reasoning used
     * by DailyRecommendationWorker so the user sees consistent rationale.
     */
    private fun buildReasoning(item: ScanResultItem, bullish: Boolean): String {
        val parts = mutableListOf<String>()
        val rsi = item.rsi
        if (rsi != null) {
            val r = "%.0f".format(rsi)
            parts += when {
                rsi >= 70 -> "RSI $r overbought"
                rsi >= 60 -> "RSI $r strong"
                rsi in 45.0..60.0 -> "RSI $r healthy"
                rsi in 30.0..45.0 -> "RSI $r cooling"
                else -> "RSI $r oversold"
            }
        }
        val sma50 = item.sma50; val sma200 = item.sma200; val price = item.price
        if (sma50 != null && sma200 != null) {
            val golden = sma50 >= sma200
            parts += when {
                golden && price >= sma50 -> "above SMA50/200 (golden-cross trend)"
                !golden && price < sma50 -> "below SMA50/200 (death-cross)"
                golden && price < sma50 -> "pullback to SMA50 in uptrend"
                !golden && price >= sma50 -> "reclaim of SMA50 in downtrend"
                else -> "mixed MA posture"
            }
        }
        val signals = if (bullish) item.bullishSignals else item.bearishSignals
        signals?.firstOrNull { it.contains("vol", true) || it.contains("news", true) || it.contains("earn", true) }
            ?.let { parts += it }
        item.sector?.takeIf { it.isNotBlank() }?.let { parts += "sector: $it" }
        item.analystTarget?.upsidePct?.takeIf { it != 0.0 }?.let { parts += "analyst upside %+.0f%%".format(it) }
        return parts.take(5).joinToString(" • ")
    }

    // ==============================
    // Market hours / notification plumbing
    // ==============================

    private fun isMarketOpen(): Boolean {
        val cal = Calendar.getInstance()
        val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
        if (dayOfWeek == Calendar.SATURDAY || dayOfWeek == Calendar.SUNDAY) return false

        val monthDay = "%02d-%02d".format(cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
        if (US_MARKET_HOLIDAYS_2026.contains(monthDay)) return false

        // Only scan during market hours (9:30 AM - 4:00 PM, device-local time).
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val minute = cal.get(Calendar.MINUTE)
        val timeInMinutes = hour * 60 + minute
        return timeInMinutes in (9 * 60 + 30)..(16 * 60)
    }

    private fun sendNotification(title: String, body: String) {
        NotificationCache.save(applicationContext, title, body)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                Log.w(TAG, "POST_NOTIFICATIONS not granted — saved to in-app history only.")
                return
            }
        }
        if (!NotificationManagerCompat.from(applicationContext).areNotificationsEnabled()) {
            Log.w(TAG, "Notifications disabled at app level — saved to in-app history only.")
            return
        }

        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Hourly alerts when a portfolio or trending stock's thesis materially changes"
            }
            notificationManager.createNotificationChannel(channel)
        }

        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("navigate_to", "notifications")
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext, 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(body.lines().first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }
}
