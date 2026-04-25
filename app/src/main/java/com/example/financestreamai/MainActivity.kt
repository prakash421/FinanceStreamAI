package com.example.financestreamai

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.text.ClickableText
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.TypeAdapter
import com.google.gson.annotations.SerializedName
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import okhttp3.ResponseBody
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Calendar
import java.util.concurrent.TimeUnit
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Constraints
import androidx.work.NetworkType

import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption

// ==========================================
// 1. API DATA MODELS (Matching New Backend)
// ==========================================
data class LongLeapsResult(
    @SerializedName("strike") val strike: Double,
    @SerializedName("expiry") val expiry: String,
    @SerializedName("premium") val premium: Double,
    @SerializedName("delta") val delta: Double,
    @SerializedName(value = "intrinsic_buffer", alternate = ["intrinsic"]) val intrinsicBuffer: String?,
    @SerializedName("leverage") val leverage: String?,
    @SerializedName(value = "bt", alternate = ["bt_success"]) val bt: String?,
    @SerializedName("profile") val profile: String? = null,
    @SerializedName("otm_pct") val otmPct: String? = null,
    @SerializedName("stop_loss") val stopLoss: Double? = null,
    @SerializedName("target") val target: Double? = null,
    @SerializedName("risk_note") val riskNote: String? = null
)

data class CspResult(
    @SerializedName("strike") val strike: Double,
    @SerializedName("premium") val premium: Double,
    @SerializedName("delta") val delta: Double,
    @SerializedName(value = "bt", alternate = ["bt_success"]) val bt: String?,
    @SerializedName(value = "roc", alternate = ["monthly_roc"]) val roc: String?,
    @SerializedName("expiry") val expiry: String? = null,
    @SerializedName("stop_loss") val stopLoss: Double? = null,
    @SerializedName("target") val target: Double? = null,
    @SerializedName("risk_note") val riskNote: String? = null
)

data class DiagonalResult(
    @SerializedName(value = "long", alternate = ["long_strike", "long_leg"]) val longLeg: String?,
    @SerializedName(value = "short", alternate = ["short_strike", "short_leg"]) val shortLeg: String?,
    @SerializedName(value = "net_debt", alternate = ["net_debit", "debit"]) val netDebt: Double,
    @SerializedName(value = "yield", alternate = ["yield_ratio"]) val yieldRatio: String?,
    @SerializedName(value = "bt", alternate = ["bt_success"]) val bt: String?,
    @SerializedName("expiry") val expiry: String? = null,
    @SerializedName("stop_loss") val stopLoss: Double? = null,
    @SerializedName("target") val target: Double? = null,
    @SerializedName("short_strike_breach") val shortStrikeBreach: Double? = null,
    @SerializedName("risk_note") val riskNote: String? = null
)

data class VerticalResult(
    @SerializedName(value = "strikes", alternate = ["strike"]) val strikes: String?,
    @SerializedName(value = "net_debit", alternate = ["net_debt", "debit"]) val netDebit: Double,
    @SerializedName(value = "bt", alternate = ["bt_success"]) val bt: String?,
    @SerializedName("expiry") val expiry: String? = null,
    @SerializedName("stop_loss") val stopLoss: Double? = null,
    @SerializedName("target") val target: Double? = null,
    @SerializedName("risk_note") val riskNote: String? = null
)

data class StockLevels(
    @SerializedName("atr") val atr: Double? = null,
    @SerializedName("support") val support: Double? = null,
    @SerializedName("resistance") val resistance: Double? = null,
    @SerializedName("swing_high_60d") val swingHigh60d: Double? = null,
    @SerializedName("swing_low_60d") val swingLow60d: Double? = null,
    @SerializedName("high_52w") val high52w: Double? = null,
    @SerializedName("stop_loss") val stopLoss: Double? = null,
    @SerializedName("target") val target: Double? = null,
    @SerializedName("risk_reward") val riskReward: Double? = null,
    @SerializedName("risk_note") val riskNote: String? = null
)

data class ScanResultItem(
    @SerializedName("ticker") val ticker: String,
    @SerializedName("price") val price: Double,
    @SerializedName(value = "company_name", alternate = ["name"]) val name: String? = null,
    @SerializedName(value = "daily_change_pct", alternate = ["change_percent", "changePercent", "pct_change"]) val changePercent: Double? = null,
    @SerializedName("rsi") val rsi: Double?,
    @SerializedName("beta") val beta: Double?,
    @SerializedName("sector") val sector: String? = null,
    @SerializedName("next_earnings_date") val nextEarningsDate: String? = null,
    @SerializedName("analyst_target") val analystTarget: AnalystTarget? = null,
    @SerializedName("sma50") val sma50: Double? = null,
    @SerializedName(value = "csps", alternate = ["csp", "csp_results"]) val csps: List<CspResult>?,
    @SerializedName(value = "diagonals", alternate = ["diagonal", "diagonal_results"]) val diagonals: List<DiagonalResult>?,
    @SerializedName(value = "verticals", alternate = ["vertical", "vertical_results"]) val verticals: List<VerticalResult>?,
    @SerializedName(value = "long_leaps", alternate = ["long_leaps_results", "leaps"]) val longLeaps: List<LongLeapsResult>?,
    @SerializedName(value = "iv_rank", alternate = ["ivRank"]) val ivRank: String? = null,
    @SerializedName(value = "discount_from_high", alternate = ["discountFromHigh"]) val discountFromHigh: String? = null,
    @SerializedName("sma200") val sma200: Double? = null,
    @SerializedName("overall") val overall: String? = null,
    @SerializedName("stock_recommendation") val stockRecommendation: String? = null,
    @SerializedName("stock_summary") val stockSummary: String? = null,
    @SerializedName("bullish_signals") val bullishSignals: List<String>? = null,
    @SerializedName("bearish_signals") val bearishSignals: List<String>? = null,
    @SerializedName("levels") val levels: StockLevels? = null
)

data class CapitalHealth(
    @SerializedName("committed") val committed: Double
)

data class PerformanceMetrics(
    @SerializedName("monthly_realized") val monthlyRealized: Double,
    @SerializedName("monthly_goal_progress") val progress: String
)

data class ActivePosition(
    @SerializedName("id") val id: Int? = null,
    @SerializedName("ticker") val ticker: String,
    @SerializedName("strategy") val strategy: String,
    @SerializedName("contracts") val contracts: Int,
    @SerializedName("strike") val strike: Double,
    @SerializedName("expiry") val expiry: String,
    @SerializedName("entry_premium") val entryPremium: Double
)

data class ClosedPosition(
    @SerializedName("id") val id: Int? = null,
    @SerializedName("ticker") val ticker: String,
    @SerializedName("strategy") val strategy: String,
    @SerializedName("contracts") val contracts: Int,
    @SerializedName("strike") val strike: Double,
    @SerializedName("expiry") val expiry: String,
    @SerializedName("entry_premium") val entryPremium: Double,
    @SerializedName("exit_price") val exitPrice: Double,
    @SerializedName("exit_date") val exitDate: String
)

data class HealthResponse(
    @SerializedName("status") val status: String,
    @SerializedName("capital_health") val capitalHealth: CapitalHealth,
    @SerializedName("performance") val performance: PerformanceMetrics,
    @SerializedName("active_positions") val activePositions: List<ActivePosition>,
    @SerializedName("closed_positions") val closedPositions: List<ClosedPosition>? = emptyList()
)

data class TradeEntry(
    val ticker: String, val strike: Double, val expiry: String, val trigger_price: Double,
    val entry_premium: Double, val contracts: Int, val strategy: String, val is_call: Int, val is_buy: Int,
    val exit_price: Double? = null, val exit_date: String? = null
)

// Backtest / AI Guru models
data class BacktestRequest(
    val ticker: String,
    val strategy: String,         // "csp", "sell_call", "vertical", "diagonal", "long_leaps"
    val action: String,           // "buy" or "sell"
    val strike: Double? = null,
    val strike_sell: Double? = null,
    val expiry: String? = null,
    val expiry_sell: String? = null,
    val premium: Double? = null
)

data class BacktestResponse(
    @SerializedName("verdict") val verdict: String,           // "BUY", "SELL", "HOLD", "AVOID"
    @SerializedName("confidence") val confidence: String,     // "High", "Medium", "Low"
    @SerializedName("summary") val summary: String,
    @SerializedName("backtest_score") val backtestScore: String? = null,
    @SerializedName("price") val price: Double? = null,
    @SerializedName("rsi") val rsi: Double? = null,
    @SerializedName("signals") val signals: List<String>? = null,
    @SerializedName("warnings") val warnings: List<String>? = null,
    @SerializedName("levels") val levels: StockLevels? = null,
    @SerializedName("learning") val learning: BacktestLearning? = null
)

// Analyst target from scan results
data class AnalystTarget(
    @SerializedName("mean") val mean: Double? = null,
    @SerializedName("low") val low: Double? = null,
    @SerializedName("high") val high: Double? = null,
    @SerializedName("num_analysts") val numAnalysts: Int? = null,
    @SerializedName("upside_pct") val upsidePct: Double? = null,
    @SerializedName("consensus") val consensus: String? = null   // e.g. "Strong Buy", "Buy", "Hold"
)

// Backtest learning info
data class BacktestLearning(
    @SerializedName("enabled") val enabled: Boolean = false,
    @SerializedName("applied") val applied: Boolean = false,
    @SerializedName("original_verdict") val originalVerdict: String? = null,
    @SerializedName("adjusted_verdict") val adjustedVerdict: String? = null,
    @SerializedName("adjustment_reason") val adjustmentReason: String? = null
)

// Async scan models
data class AsyncScanResponse(
    @SerializedName("status") val status: String,
    @SerializedName("job_id") val jobId: String,
    @SerializedName("total_tickers") val totalTickers: Int? = null,
    @SerializedName("strong_only") val strongOnly: Boolean? = null,
    @SerializedName("poll_url") val pollUrl: String? = null,
    @SerializedName("tickers") val tickers: List<String>? = null
)

data class AsyncScanStatus(
    @SerializedName("status") val status: String,
    @SerializedName("progress") val progress: String? = null,
    @SerializedName("tickers_scanned") val tickersScanned: Int? = null,
    @SerializedName("total_tickers") val totalTickers: Int? = null
)

// Watchlist models
data class WatchlistResponse(
    @SerializedName("tickers") val tickers: List<String>,
    @SerializedName("is_default") val isDefault: Boolean? = null,
    @SerializedName("count") val count: Int? = null
)

data class WatchlistSetRequest(
    @SerializedName("tickers") val tickers: List<String>
)

// Sector Rotation models
data class SectorRotationResponse(
    @SerializedName("sectors") val sectors: List<SectorData>,
    @SerializedName("rotation_signals") val rotationSignals: List<String>? = null,
    @SerializedName("period") val period: String? = null,
    @SerializedName("top_sectors") val topSectors: List<String>? = null,
    @SerializedName("bottom_sectors") val bottomSectors: List<String>? = null
)

data class SectorData(
    @SerializedName("sector") val sector: String,
    @SerializedName("etf") val etf: String,
    @SerializedName("return_period") val returnPeriod: Double,
    @SerializedName("return_recent") val returnRecent: Double,
    @SerializedName("volume_change_pct") val volumeChangePct: Double,
    @SerializedName("money_flow") val moneyFlow: String,
    @SerializedName("acceleration") val acceleration: Double,
    @SerializedName("rank") val rank: Int
)

// Recommendations / AI Feedback Loop models
data class RecommendationItem(
    @SerializedName("rec_id") val recId: String,
    @SerializedName("source") val source: String? = null,
    @SerializedName("ticker") val ticker: String,
    @SerializedName("strategy") val strategy: String? = null,
    @SerializedName("action") val action: String? = null,
    @SerializedName("entry_price") val entryPrice: Double? = null,
    @SerializedName("verdict") val verdict: String? = null,
    @SerializedName("strike") val strike: Double? = null,
    @SerializedName("created_at") val createdAt: String? = null,
    @SerializedName("scan_date") val scanDate: String? = null,
    @SerializedName("closed") val closed: Boolean = false,
    @SerializedName("eval_count") val evalCount: Int? = null,
    @SerializedName("final_status") val finalStatus: String? = null,
    @SerializedName("outcome_history") val outcomeHistory: List<OutcomeEntry>? = null
)

data class OutcomeEntry(
    @SerializedName("week") val week: Int,
    @SerializedName("status") val status: String,
    @SerializedName("price_change_pct") val priceChangePct: Double? = null,
    @SerializedName("eval_at") val evalAt: String? = null
)

data class RecommendationStats(
    @SerializedName("enabled") val enabled: Boolean = false,
    @SerializedName("horizon_days") val horizonDays: Int? = null,
    @SerializedName("total_recommendations") val totalRecommendations: Int? = null,
    @SerializedName("by_strategy") val byStrategy: Map<String, StrategyStats>? = null,
    @SerializedName("by_verdict") val byVerdict: Map<String, StrategyStats>? = null
)

data class StrategyStats(
    @SerializedName("winning") val winning: Int = 0,
    @SerializedName("losing") val losing: Int = 0,
    @SerializedName("neutral") val neutral: Int = 0,
    @SerializedName("total") val total: Int = 0,
    @SerializedName("win_rate") val winRate: Double = 0.0
)

data class LearningsResponse(
    @SerializedName("enabled") val enabled: Boolean = false,
    @SerializedName("as_of") val asOf: String? = null,
    @SerializedName("verdict_baselines") val verdictBaselines: List<VerdictBaseline>? = null,
    @SerializedName("top_winning_signals") val topWinningSignals: List<SignalStat>? = null,
    @SerializedName("top_losing_signals") val topLosingSignals: List<SignalStat>? = null,
    @SerializedName("suggested_adjustments") val suggestedAdjustments: List<String>? = null
)

data class VerdictBaseline(
    @SerializedName("strategy") val strategy: String,
    @SerializedName("verdict") val verdict: String,
    @SerializedName("winning") val winning: Int = 0,
    @SerializedName("total") val total: Int = 0,
    @SerializedName("win_rate") val winRate: Double = 0.0
)

data class SignalStat(
    @SerializedName("strategy") val strategy: String? = null,
    @SerializedName("signal") val signal: String,
    @SerializedName("winning") val winning: Int = 0,
    @SerializedName("total") val total: Int = 0,
    @SerializedName("win_rate") val winRate: Double = 0.0
)

// ==========================================
// 2. RETROFIT API INTERFACE
// ==========================================
interface JPFinanceApi {
    // --- Scan ---
    @GET("scan")
    suspend fun getScanResults(
        @Query("tickers") tickers: String? = null,
        @Query("strategy") strategy: String? = null,
        @Query("target_delta") targetDelta: Double? = null,
        @Query("min_roc") minRoc: Double? = null,
        @Query("include_trending") includeTrending: Boolean? = null,
        @Query("strong_only") strongOnly: Boolean? = null
    ): List<ScanResultItem>

    @GET("scan/async")
    suspend fun scanAsync(
        @Query("tickers") tickers: String? = null,
        @Query("strategy") strategy: String? = null,
        @Query("include_trending") includeTrending: Boolean? = null,
        @Query("strong_only") strongOnly: Boolean? = null
    ): AsyncScanResponse

    @GET("scan/status/{jobId}")
    suspend fun getScanStatus(@Path("jobId") jobId: String): ResponseBody

    @GET("scan/trending")
    suspend fun scanTrending(
        @Query("limit") limit: Int? = null,
        @Query("strong_only") strongOnly: Boolean? = null
    ): List<ScanResultItem>

    @GET("scan/trending/async")
    suspend fun scanTrendingAsync(
        @Query("limit") limit: Int? = null,
        @Query("strong_only") strongOnly: Boolean? = null
    ): AsyncScanResponse

    // --- Health / Portfolio ---
    @GET("health")
    suspend fun getHealth(): HealthResponse

    @POST("portfolio/add")
    suspend fun addPosition(@Body trade: TradeEntry): Map<String, Any>

    @DELETE("portfolio/remove/{id}")
    suspend fun removePosition(@Path("id") id: Int): Map<String, String>

    @POST("portfolio/close/{id}")
    suspend fun closePosition(@Path("id") id: Int, @Body exitDetails: Map<String, String>): Map<String, String>

    @PUT("portfolio/update/{id}")
    suspend fun updatePosition(@Path("id") id: Int, @Body trade: TradeEntry): Map<String, Any>

    @GET("portfolio/positions")
    suspend fun getPositions(): HealthResponse

    // --- Watchlist ---
    @GET("watchlist")
    suspend fun getWatchlist(): WatchlistResponse

    @PUT("watchlist")
    suspend fun setWatchlist(@Body request: WatchlistSetRequest): WatchlistResponse

    @POST("watchlist/add")
    suspend fun addToWatchlist(@Query("ticker") ticker: String): WatchlistResponse

    @DELETE("watchlist/remove")
    suspend fun removeFromWatchlist(@Query("ticker") ticker: String): WatchlistResponse

    // --- Sector Rotation ---
    @GET("sector-rotation")
    suspend fun getSectorRotation(@Query("period") period: String? = null): SectorRotationResponse

    // --- Backtest / AI Guru ---
    @POST("backtest")
    suspend fun getBacktest(@Body request: BacktestRequest): BacktestResponse

    // --- Recommendations / AI Feedback Loop ---
    @GET("recommendations/history")
    suspend fun getRecommendationHistory(
        @Query("days") days: Int? = null,
        @Query("ticker") ticker: String? = null,
        @Query("strategy") strategy: String? = null,
        @Query("include_closed") includeClosed: Boolean? = null,
        @Query("limit") limit: Int? = null
    ): List<RecommendationItem>

    @GET("recommendations/stats")
    suspend fun getRecommendationStats(@Query("days") days: Int? = null): RecommendationStats

    @GET("recommendations/{recId}")
    suspend fun getRecommendationDetail(@Path("recId") recId: String): RecommendationItem

    @GET("recommendations/learnings")
    suspend fun getLearnings(
        @Query("strategy") strategy: String? = null,
        @Query("top_n") topN: Int? = null
    ): LearningsResponse

    @POST("recommendations/learnings/refresh")
    suspend fun refreshLearnings(): Map<String, Any>

    @POST("settings/update")
    suspend fun updateSettings(@Body settings: Map<String, String>): Map<String, String>
}

// Custom TypeAdapter: handles backend returning a single object OR an array for List<ScanResultItem>
class ScanResultListAdapter : TypeAdapter<List<ScanResultItem>>() {
    private val itemAdapter: Gson = Gson()
    override fun write(out: JsonWriter, value: List<ScanResultItem>?) {
        itemAdapter.toJson(value, object : TypeToken<List<ScanResultItem>>() {}.type)
    }
    override fun read(reader: JsonReader): List<ScanResultItem> {
        return if (reader.peek() == JsonToken.BEGIN_ARRAY) {
            val list = mutableListOf<ScanResultItem>()
            reader.beginArray()
            while (reader.hasNext()) {
                list.add(itemAdapter.fromJson(reader, ScanResultItem::class.java))
            }
            reader.endArray()
            list
        } else {
            listOf(itemAdapter.fromJson(reader, ScanResultItem::class.java))
        }
    }
}

val scanListType: java.lang.reflect.Type = object : TypeToken<List<ScanResultItem>>() {}.type
val gson: Gson = GsonBuilder()
    .registerTypeAdapter(scanListType, ScanResultListAdapter())
    .create()

// X-User-Id interceptor: attaches Firebase UID to all requests when signed in
object UserSession {
    var userId: String? = null
}

private val authInterceptor = Interceptor { chain ->
    val requestBuilder = chain.request().newBuilder()
    UserSession.userId?.let { uid ->
        requestBuilder.addHeader("X-User-Id", uid)
    }
    chain.proceed(requestBuilder.build())
}

// Render backend URL. Ensure it ends with a trailing slash.
val retrofit: Retrofit = Retrofit.Builder()
    .baseUrl("https://financestreamai-backend.onrender.com/api/v1/")
    .client(OkHttpClient.Builder()
        .addInterceptor(authInterceptor)
        .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()
    )
    .addConverterFactory(GsonConverterFactory.create(gson))
    .build()

val apiService: JPFinanceApi = retrofit.create(JPFinanceApi::class.java)

// Watchlist Defaults
val MASTER_WATCHLIST_DEFAULT = listOf("ALAB", "PLTR", "CRWD", "SNOW", "TSLA", "NFLX", "ARM", "MSFT", "META", "NVDA", "MSTR", "SMCI", "APP", "SHOP", "AVGO", "SITM", "HOOD", "CRWV", "IREN", "RDDT", "AMZN", "TSM", "UBER", "COIN", "SNDK", "MU", "WDC", "STX", "BE", "NOW", "CRM", "ADBE", "VRT", "TEAM", "NBIS", "CRDO")

// Helper to parse numeric values from strings like "5.4%" or "10.2"
internal fun String?.parseToDouble(): Double {
    if (this == null) return 0.0
    return try {
        val regex = """-?\d+(\.\d+)?""".toRegex()
        val match = regex.find(this)
        match?.value?.toDoubleOrNull() ?: 0.0
    } catch (e: Exception) {
        0.0
    }
}

// Helper to format date from "2026-12-18" or "2026-12-18 $530.0C" to "12.18.2026" style
internal fun String?.formatDate(): String {
    if (this == null) return "N/A"
    return try {
        // Extract the date portion (first 10 chars matching YYYY-MM-DD)
        val dateRegex = """(\d{4})-(\d{2})-(\d{2})""".toRegex()
        val match = dateRegex.find(this) ?: return this
        val (year, month, day) = match.destructured
        val formatted = "$month.$day.$year"
        // Replace the date in the original string, preserving any suffix like " $530.0C"
        this.replaceRange(match.range, formatted)
    } catch (_: Exception) { this }
}

// Helper to produce user-friendly error messages
private fun friendlyErrorMessage(e: Exception): String {
    return when (e) {
        is SocketTimeoutException -> "Request timed out. The server is processing — please try again in a moment."
        is UnknownHostException -> "No internet connection. Please check your network and try again."
        is HttpException -> {
            when (e.code()) {
                429 -> "Too many requests. Please wait a moment before trying again."
                in 500..599 -> "Server error (${e.code()}). The backend may be restarting — please retry shortly."
                else -> "Server returned error ${e.code()}. Please try again."
            }
        }
        is java.io.IOException -> "Connection lost. Please check your network and try again."
        else -> e.message ?: "An unexpected error occurred. Please try again."
    }
}

// Helper to translate Credential Manager exceptions into actionable messages.
private fun friendlyCredentialError(e: androidx.credentials.exceptions.GetCredentialException): String {
    val type = e.type
    val msg = e.message.orEmpty()
    return when {
        e is androidx.credentials.exceptions.GetCredentialCancellationException ->
            "Sign-in was cancelled."
        e is androidx.credentials.exceptions.NoCredentialException ->
            "No Google accounts available on this device. Add a Google account in " +
                "Android Settings, then try again."
        e is androidx.credentials.exceptions.GetCredentialInterruptedException ->
            "Sign-in was interrupted. Please try again."
        type.contains("DEVELOPER", true) || msg.contains("DEVELOPER_ERROR", true) ->
            "Sign-in misconfigured (DEVELOPER_ERROR). Verify the Web Client ID and that " +
                "this app's package name + SHA-1 fingerprint are registered in Google Cloud Console."
        msg.contains("16:", true) || msg.contains("network", true) ->
            "Network problem during sign-in. Check connectivity and retry."
        else -> "Sign-in failed: ${msg.ifBlank { type }}"
    }
}

/**
 * Normalise user-typed expiry dates to YYYY-MM-DD expected by the backend.
 * Accepted input examples:
 *   2026-06-18   → 2026-06-18  (already correct)
 *   18Jun2026    → 2026-06-18
 *   18Jun26      → 2026-06-18
 *   18-Jun-2026  → 2026-06-18
 *   Jun 18 2026  → 2026-06-18
 *   06/18/2026   → 2026-06-18
 *   06/18/26     → 2026-06-18
 * Returns null if the string cannot be parsed.
 */
private fun normaliseExpiry(raw: String): String? {
    val s = raw.trim().ifBlank { return null }
    // Already in YYYY-MM-DD
    val isoRe = Regex("""^(\d{4})-(\d{2})-(\d{2})$""")
    isoRe.matchEntire(s)?.let { return s }

    val months = mapOf(
        "jan" to "01", "feb" to "02", "mar" to "03", "apr" to "04",
        "may" to "05", "jun" to "06", "jul" to "07", "aug" to "08",
        "sep" to "09", "oct" to "10", "nov" to "11", "dec" to "12"
    )

    fun expandYear(y: String) = if (y.length == 2) "20$y" else y

    // DDMonYYYY or DDMonYY or DD-Mon-YYYY etc.: 18Jun2026, 18-Jun-2026
    val dmy = Regex("""^(\d{1,2})[-\s]?([A-Za-z]{3,9})[-\s]?(\d{2,4})$""")
    dmy.matchEntire(s)?.let { m ->
        val day = m.groupValues[1].padStart(2, '0')
        val mon = months[m.groupValues[2].lowercase().take(3)] ?: return null
        val yr  = expandYear(m.groupValues[3])
        return "$yr-$mon-$day"
    }

    // MonDDYYYY or Mon-DD-YYYY: Jun182026, Jun 18 2026
    val mdy = Regex("""^([A-Za-z]{3,9})[-\s]?(\d{1,2})[-\s]?(\d{2,4})$""")
    mdy.matchEntire(s)?.let { m ->
        val mon = months[m.groupValues[1].lowercase().take(3)] ?: return null
        val day = m.groupValues[2].padStart(2, '0')
        val yr  = expandYear(m.groupValues[3])
        return "$yr-$mon-$day"
    }

    // MM/DD/YYYY or MM/DD/YY
    val slash = Regex("""^(\d{1,2})/(\d{1,2})/(\d{2,4})$""")
    slash.matchEntire(s)?.let { m ->
        val mon = m.groupValues[1].padStart(2, '0')
        val day = m.groupValues[2].padStart(2, '0')
        val yr  = expandYear(m.groupValues[3])
        return "$yr-$mon-$day"
    }

    return null // unrecognised
}

// ==========================================
// SIGNAL ABBREVIATION (compact display)
// ==========================================
private fun abbreviateSignal(signal: String): String {
    val s = signal.trim()
    return when {
        s.contains("200-day SMA", true) || s.contains("SMA200", true) || s.contains("200 SMA", true) ->
            if (s.contains("above", true)) "↑SMA200" else "↓SMA200"
        s.contains("50-day SMA", true) || s.contains("SMA50", true) || s.contains("50 SMA", true) ->
            if (s.contains("above", true)) "↑SMA50" else "↓SMA50"
        s.contains("oversold", true) -> "RSI Oversold"
        s.contains("overbought", true) -> "RSI Overbought"
        s.contains("golden cross", true) -> "Golden Cross"
        s.contains("death cross", true) -> "Death Cross"
        s.contains("MACD", true) -> if (s.contains("bull", true) || s.contains("above", true) || s.contains("positive", true)) "MACD Bullish" else "MACD Bearish"
        s.contains("momentum", true) -> if (s.contains("positive", true) || s.contains("bull", true) || s.contains("strong", true)) "Momentum +" else "Momentum −"
        s.contains("volume", true) -> if (s.contains("high", true) || s.contains("above", true) || s.contains("increas", true)) "Volume ↑" else "Volume ↓"
        s.contains("52-week", true) || s.contains("52 week", true) ->
            if (s.contains("near", true) || s.contains("high", true)) "Near 52W High" else "Near 52W Low"
        s.contains("breakout", true) -> "Breakout"
        s.contains("breakdown", true) -> "Breakdown"
        s.contains("support", true) -> "At Support"
        s.contains("resistance", true) -> "At Resistance"
        s.contains("dividend", true) -> "Dividend"
        s.contains("earnings", true) -> "Earnings"
        s.contains("uptrend", true) || s.contains("up trend", true) -> "Uptrend"
        s.contains("downtrend", true) || s.contains("down trend", true) -> "Downtrend"
        s.contains("bollinger", true) -> "Bollinger Band"
        s.contains("win r", true) -> s  // Keep full: "60-day win rate: 75%"
        s.contains("revenue", true) -> s  // Keep full: "Revenue growth +12%"
        s.contains("price", true) && s.contains("%", true) -> s  // Keep: "Price +2.0%"
        s.contains("below", true) && s.contains("%", true) -> s  // Keep: "25% below 52..."
        s.contains("growth", true) -> s
        s.contains("decline", true) -> s
        else -> s  // Show full text — never truncate
    }
}

// ==========================================
// LOCAL PORTFOLIO CACHE (survives app restart)
// ==========================================
object PortfolioCache {
    private const val PREFS_NAME = "PortfolioCache"
    private const val KEY_ACTIVE = "active_positions"
    private const val KEY_CLOSED = "closed_positions"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun savePositions(context: Context, active: List<ActivePosition>, closed: List<ClosedPosition>) {
        prefs(context).edit()
            .putString(KEY_ACTIVE, gson.toJson(active))
            .putString(KEY_CLOSED, gson.toJson(closed))
            .apply()
    }

    fun addPosition(context: Context, pos: ActivePosition) {
        val current = loadActivePositions(context).toMutableList()
        current.add(pos)
        prefs(context).edit().putString(KEY_ACTIVE, gson.toJson(current)).apply()
    }

    fun updatePosition(context: Context, index: Int, pos: ActivePosition) {
        val current = loadActivePositions(context).toMutableList()
        if (index in current.indices) {
            current[index] = pos
            prefs(context).edit().putString(KEY_ACTIVE, gson.toJson(current)).apply()
        }
    }

    fun removePosition(context: Context, index: Int) {
        val current = loadActivePositions(context).toMutableList()
        if (index in current.indices) {
            current.removeAt(index)
            prefs(context).edit().putString(KEY_ACTIVE, gson.toJson(current)).apply()
        }
    }

    fun loadActivePositions(context: Context): List<ActivePosition> {
        val json = prefs(context).getString(KEY_ACTIVE, null) ?: return emptyList()
        return try {
            gson.fromJson(json, object : TypeToken<List<ActivePosition>>() {}.type)
        } catch (_: Exception) { emptyList() }
    }

    fun loadClosedPositions(context: Context): List<ClosedPosition> {
        val json = prefs(context).getString(KEY_CLOSED, null) ?: return emptyList()
        return try {
            gson.fromJson(json, object : TypeToken<List<ClosedPosition>>() {}.type)
        } catch (_: Exception) { emptyList() }
    }
}

// ==========================================
// NOTIFICATION HISTORY CACHE
// ==========================================
data class NotificationRecord(
    val title: String,
    val body: String,
    val timestamp: Long
)

object NotificationCache {
    private const val PREFS_NAME = "NotificationHistory"
    private const val KEY_NOTIFICATIONS = "notifications"
    private const val MAX_NOTIFICATIONS = 50

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun save(context: Context, title: String, body: String) {
        val current = load(context).toMutableList()
        current.add(0, NotificationRecord(title, body, System.currentTimeMillis()))
        if (current.size > MAX_NOTIFICATIONS) current.subList(MAX_NOTIFICATIONS, current.size).clear()
        prefs(context).edit().putString(KEY_NOTIFICATIONS, gson.toJson(current)).apply()
    }

    fun load(context: Context): List<NotificationRecord> {
        val json = prefs(context).getString(KEY_NOTIFICATIONS, null) ?: return emptyList()
        return try {
            gson.fromJson(json, object : TypeToken<List<NotificationRecord>>() {}.type)
        } catch (_: Exception) { emptyList() }
    }
}

// ==========================================
// 3. AI CROSS-VALIDATION UI COMPONENTS
// ==========================================

/** Dialog for entering AI API keys */
@Composable
fun AiApiKeysDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var claudeKey by remember { mutableStateOf(AiKeyManager.getKey(context, AiKeyManager.KEY_CLAUDE) ?: "") }
    var geminiKey by remember { mutableStateOf(AiKeyManager.getKey(context, AiKeyManager.KEY_GEMINI) ?: "") }
    var chatgptKey by remember { mutableStateOf(AiKeyManager.getKey(context, AiKeyManager.KEY_CHATGPT) ?: "") }
    var perplexityKey by remember { mutableStateOf(AiKeyManager.getKey(context, AiKeyManager.KEY_PERPLEXITY) ?: "") }
    var grokKey by remember { mutableStateOf(AiKeyManager.getKey(context, AiKeyManager.KEY_GROK) ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("AI Engine API Keys", fontWeight = FontWeight.Bold)
                Text(
                    "Keys are stored encrypted on your device only. Enter keys for any engines you want to use for cross-validation.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = claudeKey, onValueChange = { claudeKey = it },
                    label = { Text("Claude (Anthropic)") },
                    placeholder = { Text("sk-ant-...") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = geminiKey, onValueChange = { geminiKey = it },
                    label = { Text("Gemini (Google)") },
                    placeholder = { Text("AIza...") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = chatgptKey, onValueChange = { chatgptKey = it },
                    label = { Text("ChatGPT (OpenAI)") },
                    placeholder = { Text("sk-...") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = perplexityKey, onValueChange = { perplexityKey = it },
                    label = { Text("Perplexity") },
                    placeholder = { Text("pplx-...") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = grokKey, onValueChange = { grokKey = it },
                    label = { Text("Grok (xAI)") },
                    placeholder = { Text("xai-...") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                if (claudeKey.isNotBlank()) AiKeyManager.setKey(context, AiKeyManager.KEY_CLAUDE, claudeKey)
                else AiKeyManager.clearKey(context, AiKeyManager.KEY_CLAUDE)
                if (geminiKey.isNotBlank()) AiKeyManager.setKey(context, AiKeyManager.KEY_GEMINI, geminiKey)
                else AiKeyManager.clearKey(context, AiKeyManager.KEY_GEMINI)
                if (chatgptKey.isNotBlank()) AiKeyManager.setKey(context, AiKeyManager.KEY_CHATGPT, chatgptKey)
                else AiKeyManager.clearKey(context, AiKeyManager.KEY_CHATGPT)
                if (perplexityKey.isNotBlank()) AiKeyManager.setKey(context, AiKeyManager.KEY_PERPLEXITY, perplexityKey)
                else AiKeyManager.clearKey(context, AiKeyManager.KEY_PERPLEXITY)
                if (grokKey.isNotBlank()) AiKeyManager.setKey(context, AiKeyManager.KEY_GROK, grokKey)
                else AiKeyManager.clearKey(context, AiKeyManager.KEY_GROK)
                AiCrossValidator.clearCache()
                Toast.makeText(context, "API keys saved (${AiKeyManager.getConfiguredEngines(context).size} engines configured)", Toast.LENGTH_SHORT).show()
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/** Compact badge showing AI cross-validation consensus on a scan result card */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AiCrossValidationBadge(validation: AiCrossValidation?) {
    if (validation == null) return

    var expanded by remember { mutableStateOf(false) }

    val consensusColor = when {
        validation.consensus.contains("STRONG BUY", true) -> Color(0xFF1B5E20)
        validation.consensus.contains("BUY", true) -> Color(0xFF2E7D32)
        validation.consensus.contains("HOLD", true) -> Color(0xFFEF6C00)
        validation.consensus.contains("SELL", true) || validation.consensus.contains("AVOID", true) -> Color(0xFFC62828)
        validation.consensus == "MIXED" -> Color(0xFF7C3AED)
        else -> Color.Gray
    }

    Column {
        // Clickable consensus badge
        Card(
            colors = CardDefaults.cardColors(containerColor = consensusColor.copy(alpha = 0.12f)),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.clickable { expanded = !expanded }
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text("🤖", style = MaterialTheme.typography.labelSmall)
                Text(
                    "AI: ${validation.consensus}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = consensusColor
                )
                Text(
                    "(${validation.agreementPct}%)",
                    style = MaterialTheme.typography.labelSmall,
                    color = consensusColor.copy(alpha = 0.7f)
                )
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = consensusColor
                )
            }
        }

        // Expanded detail showing each engine's verdict
        if (expanded) {
            Spacer(modifier = Modifier.height(4.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("AI Cross-Validation", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    Text(validation.summary, style = MaterialTheme.typography.bodySmall, color = Color.Gray)

                    validation.engines.forEach { engine ->
                        HorizontalDivider()
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(engine.engine, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            if (engine.error != null) {
                                Column {
                                    Text("⚠ Failed", style = MaterialTheme.typography.labelSmall, color = Color(0xFFC62828))
                                }
                            } else {
                                val vColor = when {
                                    engine.verdict.contains("BUY", true) -> Color(0xFF2E7D32)
                                    engine.verdict.contains("HOLD", true) -> Color(0xFFEF6C00)
                                    else -> Color(0xFFC62828)
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Card(
                                        colors = CardDefaults.cardColors(containerColor = vColor.copy(alpha = 0.12f)),
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Text(
                                            engine.verdict,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = vColor
                                        )
                                    }
                                    Text(engine.confidence, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                                }
                            }
                        }
                        if (engine.error != null) {
                            Text(
                                "Error: ${engine.error.take(120)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFC62828).copy(alpha = 0.8f)
                            )
                        } else if (engine.reasoning.isNotBlank()) {
                            Text(engine.reasoning, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

// ==========================================
// 4. MAIN ACTIVITY & UI
// ==========================================
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scheduleDailyRecommendations()
        schedulePortfolioFlipScan()
        // Pre-warm: wake up Render backend so it's ready when user scans
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            try { apiService.getHealth() } catch (_: Exception) { }
        }
        val startTab = if (intent?.getStringExtra("navigate_to") == "notifications") 3 else 0
        val isSignedIn = GoogleAuthManager.isSignedIn(this)
        // Set user session for API auth header
        if (isSignedIn) {
            UserSession.userId = GoogleAuthManager.getUserId(this)
        }
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    var signedIn by remember { mutableStateOf(isSignedIn) }
                    if (signedIn) {
                        MainScreen(startTab = startTab)
                    } else {
                        GoogleSignInScreen(onSignInSuccess = { signedIn = true })
                    }
                }
            }
        }
    }

    private fun scheduleDailyRecommendations() {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 6)
            set(Calendar.MINUTE, 50)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            // If 6:50am already passed today, schedule for tomorrow
            if (before(now)) add(Calendar.DAY_OF_MONTH, 1)
        }
        val initialDelayMs = target.timeInMillis - now.timeInMillis

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val dailyWork = PeriodicWorkRequestBuilder<DailyRecommendationWorker>(
            24, TimeUnit.HOURS
        )
            .setInitialDelay(initialDelayMs, TimeUnit.MILLISECONDS)
            .setConstraints(constraints)
            .addTag(DailyRecommendationWorker.TAG)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            DailyRecommendationWorker.TAG,
            ExistingPeriodicWorkPolicy.UPDATE,
            dailyWork
        )

        Log.d("MainActivity", "Daily recommendations scheduled. Initial delay: ${initialDelayMs / 1000 / 60} min")
    }

    private fun schedulePortfolioFlipScan() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val hourlyWork = PeriodicWorkRequestBuilder<PortfolioFlipWorker>(
            1, TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .addTag(PortfolioFlipWorker.TAG)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            PortfolioFlipWorker.TAG,
            ExistingPeriodicWorkPolicy.UPDATE,
            hourlyWork
        )

        Log.d("MainActivity", "Portfolio flip scan scheduled (hourly during market hours)")
    }
}

@Composable
fun GoogleSignInScreen(onSignInSuccess: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun performSignIn() {
        isLoading = true
        errorMessage = null
        if (!GoogleAuthManager.isWebClientIdConfigured()) {
            errorMessage = "Google Sign-In is not configured. " +
                "Set GoogleAuthManager.WEB_CLIENT_ID to your OAuth 2.0 Web client ID " +
                "from Google Cloud Console."
            isLoading = false
            return
        }
        scope.launch {
            try {
                val credentialManager = CredentialManager.create(context)
                val googleIdOption = GoogleAuthManager.buildGoogleIdOption(filterAuthorizedAccounts = false)
                val request = GetCredentialRequest.Builder()
                    .addCredentialOption(googleIdOption)
                    .build()
                val result = withContext(Dispatchers.IO) {
                    credentialManager.getCredential(context as android.app.Activity, request)
                }
                if (GoogleAuthManager.handleSignInResult(context, result)) {
                    onSignInSuccess()
                } else {
                    errorMessage = "Sign-in failed: token could not be parsed."
                }
            } catch (e: GetCredentialException) {
                Log.e("GoogleSignIn", "Sign-in failed [${e.type}]: ${e.message}", e)
                errorMessage = friendlyCredentialError(e)
            } catch (e: Exception) {
                Log.e("GoogleSignIn", "Unexpected error: ${e.message}", e)
                errorMessage = e.message ?: "An unexpected error occurred."
            } finally {
                isLoading = false
            }
        }
    }

    // Auto-attempt sign-in with previously authorized accounts on launch
    LaunchedEffect(Unit) {
        if (!GoogleAuthManager.isWebClientIdConfigured()) return@LaunchedEffect
        try {
            val credentialManager = CredentialManager.create(context)
            val googleIdOption = GoogleAuthManager.buildGoogleIdOption(filterAuthorizedAccounts = true)
            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()
            val result = withContext(Dispatchers.IO) {
                credentialManager.getCredential(context as android.app.Activity, request)
            }
            if (GoogleAuthManager.handleSignInResult(context, result)) {
                onSignInSuccess()
            }
        } catch (_: Exception) {
            // No previously authorized account — show sign-in button
        }
    }

    Box(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Icon(
                Icons.Default.TipsAndUpdates,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                "StockWiz AI",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Smart stock scanning & options analysis powered by AI",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.Gray,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = { performSignIn() },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                enabled = !isLoading,
                shape = RoundedCornerShape(12.dp)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Signing in...")
                } else {
                    Text("Sign in with Google", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                }
            }

            OutlinedButton(
                onClick = {
                    GoogleAuthManager.signInAsGuest(context)
                    onSignInSuccess()
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                enabled = !isLoading,
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    "Skip for now (continue as guest)",
                    style = MaterialTheme.typography.labelLarge
                )
            }

            Text(
                "You can sign in later from Settings.",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray,
                textAlign = TextAlign.Center
            )

            if (errorMessage != null) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        errorMessage!!,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }
    }
}

@Composable
fun MainScreen(startTab: Int = 0) {
    var selectedTab by remember { mutableIntStateOf(startTab) }
    var subScreen by remember { mutableStateOf<String?>(null) }
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current

    // First-launch prompt for AI API keys
    var showAiKeysPrompt by remember { mutableStateOf(false) }
    var showAiKeysDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (!AiKeyManager.wasPromptShown(context) && !AiKeyManager.hasAnyKeys(context)) {
            showAiKeysPrompt = true
        }
    }

    // First-launch AI key prompt dialog
    if (showAiKeysPrompt) {
        val uriHandler = LocalUriHandler.current
        AlertDialog(
            onDismissRequest = {
                showAiKeysPrompt = false
                AiKeyManager.markPromptShown(context)
            },
            icon = { Icon(Icons.Default.Psychology, contentDescription = null, modifier = Modifier.size(40.dp), tint = Color(0xFF7C3AED)) },
            title = { Text("AI Cross-Validation", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "StockWiz can cross-validate Strong Buy recommendations with top AI engines " +
                        "(Claude, Gemini, ChatGPT, Perplexity, Grok) for extra confidence.\n\n" +
                        "You'll need your own API keys — they're stored encrypted on your device only.",
                        style = MaterialTheme.typography.bodyMedium
                    )

                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF7C3AED).copy(alpha = 0.08f)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("How to get API keys:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)

                            val links = listOf(
                                "Gemini" to "https://aistudio.google.com/apikey" to "FREE — no credit card needed",
                                "Claude" to "https://console.anthropic.com" to "Settings → API Keys (pay-as-you-go)",
                                "ChatGPT" to "https://platform.openai.com/api-keys" to "Settings → API Keys (pay-as-you-go)",
                                "Perplexity" to "https://www.perplexity.ai/settings/api" to "Settings → API (pay-as-you-go)",
                                "Grok" to "https://console.x.ai" to "FREE credits for new users"
                            )
                            links.forEach { (nameUrl, note) ->
                                val (name, url) = nameUrl
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("• $name: ", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                                    Text(
                                        "Get key",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            color = Color(0xFF1565C0),
                                            textDecoration = TextDecoration.Underline
                                        ),
                                        modifier = Modifier.clickable { uriHandler.openUri(url) }
                                    )
                                }
                                Text("  $note", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                            }

                            HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
                            Text(
                                "\uD83D\uDCA1 Tip: Start with Gemini or Grok (both have free tiers). You don't need all 5.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF2E7D32),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Text(
                        "Would you like to set up AI validation now?",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    showAiKeysPrompt = false
                    AiKeyManager.markPromptShown(context)
                    showAiKeysDialog = true
                }) { Text("Set Up Keys") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showAiKeysPrompt = false
                    AiKeyManager.markPromptShown(context)
                }) { Text("Maybe Later") }
            }
        )
    }

    if (showAiKeysDialog) {
        AiApiKeysDialog(onDismiss = { showAiKeysDialog = false })
    }

    // Keep-alive: ping backend every 5 minutes to prevent Render from sleeping
    LaunchedEffect(Unit) {
        while (true) {
            delay(5 * 60 * 1000L)
            try { withContext(Dispatchers.IO) { apiService.getHealth() } } catch (_: Exception) { }
        }
    }

    // Sub-screen navigation
    if (subScreen != null) {
        when (subScreen) {
            "sector_rotation" -> SectorRotationScreen(onBack = { subScreen = null })
            "ai_learnings" -> AiLearningsScreen(onBack = { subScreen = null })
        }
        return
    }

    Scaffold(
        modifier = Modifier.clickable(
            indication = null,
            interactionSource = remember { MutableInteractionSource() }
        ) {
            keyboardController?.hide()
            focusManager.clearFocus()
        },
        bottomBar = {
            var showMoreMenu by remember { mutableStateOf(false) }
            NavigationBar(tonalElevation = 4.dp) {
                val scanColor = Color(0xFF4338CA) // Indigo
                val portfolioColor = Color(0xFF059669) // Emerald
                val guruColor = Color(0xFF7C3AED) // Purple
                val alertColor = Color(0xFFD97706) // Amber
                val moreColor = Color(0xFF64748B) // Slate

                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Search, contentDescription = null, tint = scanColor) },
                    label = { Text("Scan", color = scanColor, fontSize = 10.sp) },
                    colors = androidx.compose.material3.NavigationBarItemDefaults.colors(
                        selectedIconColor = scanColor,
                        selectedTextColor = scanColor,
                        indicatorColor = Color(0xFFE0E7FF)
                    )
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.AccountBalance, contentDescription = null, tint = portfolioColor) },
                    label = { Text("Portfolio", color = portfolioColor, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 10.sp) },
                    colors = androidx.compose.material3.NavigationBarItemDefaults.colors(
                        selectedIconColor = portfolioColor,
                        selectedTextColor = portfolioColor,
                        indicatorColor = Color(0xFFD1FAE5)
                    )
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.TipsAndUpdates, contentDescription = null, tint = guruColor) },
                    label = { Text("AI Guru", color = guruColor, fontSize = 10.sp) },
                    colors = androidx.compose.material3.NavigationBarItemDefaults.colors(
                        selectedIconColor = guruColor,
                        selectedTextColor = guruColor,
                        indicatorColor = Color(0xFFEDE9FE)
                    )
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    icon = { Icon(Icons.Default.Notifications, contentDescription = null, tint = alertColor) },
                    label = { Text("Alerts", color = alertColor, fontSize = 10.sp) },
                    colors = androidx.compose.material3.NavigationBarItemDefaults.colors(
                        selectedIconColor = alertColor,
                        selectedTextColor = alertColor,
                        indicatorColor = Color(0xFFFEF3C7)
                    )
                )
                // ── More ▾ menu (Sectors + Learn) ─────────────────────────
                NavigationBarItem(
                    selected = showMoreMenu,
                    onClick = { showMoreMenu = true },
                    icon = {
                        Box {
                            Icon(Icons.Default.MoreVert, contentDescription = "More", tint = moreColor)
                            DropdownMenu(
                                expanded = showMoreMenu,
                                onDismissRequest = { showMoreMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                            Icon(Icons.Default.PieChart, contentDescription = null, tint = Color(0xFF0891B2), modifier = Modifier.size(20.dp))
                                            Text("Sectors")
                                        }
                                    },
                                    onClick = { showMoreMenu = false; subScreen = "sector_rotation" }
                                )
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                            Icon(Icons.Default.Psychology, contentDescription = null, tint = Color(0xFFDB2777), modifier = Modifier.size(20.dp))
                                            Text("Learn")
                                        }
                                    },
                                    onClick = { showMoreMenu = false; subScreen = "ai_learnings" }
                                )
                            }
                        }
                    },
                    label = { Text("More", color = moreColor, fontSize = 10.sp) },
                    colors = androidx.compose.material3.NavigationBarItemDefaults.colors(
                        selectedIconColor = moreColor,
                        selectedTextColor = moreColor,
                        indicatorColor = Color(0xFFF1F5F9)
                    )
                )
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            when (selectedTab) {
                0 -> ScanScreen()
                1 -> PortfolioScreen()
                2 -> AiGuruScreen()
                3 -> NotificationsScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current
    val sharedPrefs = remember { context.getSharedPreferences("FinanceStreamPrefs", Context.MODE_PRIVATE) }

    var isLoading by remember { mutableStateOf(false) }
    var scanResults by remember { mutableStateOf<List<ScanResultItem>>(emptyList()) }
    var manualTicker by remember { mutableStateOf("") }
    var scanProgress by remember { mutableStateOf("") }
    var scanError by remember { mutableStateOf<String?>(null) }

    val strategies = listOf("All", "CSPs", "Diagonals", "Verticals", "Long LEAPS")
    var selectedStrategy by remember { mutableStateOf(strategies[0]) }
    var expandedDropdown by remember { mutableStateOf(false) }

    var showTunerDialog by remember { mutableStateOf(false) }
    var showWatchlistDialog by remember { mutableStateOf(false) }
    var showAiKeysDialog by remember { mutableStateOf(false) }

    // AI cross-validation state
    val aiValidations = remember { mutableStateMapOf<String, AiCrossValidation>() }
    var aiValidatingTickers by remember { mutableStateOf<Set<String>>(emptySet()) }

    // Auto-trigger AI validation for Strong Buy results
    LaunchedEffect(scanResults) {
        if (scanResults.isEmpty()) return@LaunchedEffect
        if (!AiKeyManager.hasAnyKeys(context)) return@LaunchedEffect

        val strongBuys = scanResults.filter { item ->
            val rec = item.stockRecommendation ?: ""
            val overall = item.overall ?: ""
            rec.contains("STRONG BUY", true) || overall.contains("STRONG", true)
        }

        for (item in strongBuys) {
            if (aiValidations.containsKey(item.ticker)) continue
            if (aiValidatingTickers.contains(item.ticker)) continue

            aiValidatingTickers = aiValidatingTickers + item.ticker
            scope.launch {
                try {
                    val strategies = buildList {
                        if (!item.csps.isNullOrEmpty()) add("CSP")
                        if (!item.diagonals.isNullOrEmpty()) add("Diagonal")
                        if (!item.verticals.isNullOrEmpty()) add("Vertical")
                        if (!item.longLeaps.isNullOrEmpty()) add("LEAPS")
                    }.joinToString(", ")

                    val result = AiCrossValidator.validate(
                        context = context,
                        ticker = item.ticker,
                        price = item.price,
                        recommendation = item.stockRecommendation ?: item.overall ?: "STRONG BUY",
                        signals = item.bullishSignals ?: emptyList(),
                        warnings = item.bearishSignals ?: emptyList(),
                        levels = item.levels,
                        sector = item.sector,
                        strategies = strategies
                    )
                    aiValidations[item.ticker] = result
                } catch (e: Exception) {
                    Log.e("AiValidation", "Failed for ${item.ticker}: ${e.message}")
                } finally {
                    aiValidatingTickers = aiValidatingTickers - item.ticker
                }
            }
        }
    }

    // Persisted Watchlist State
    var watchlist by remember {
        val saved = sharedPrefs.getString("watchlist", null)
        val list = saved?.split(",")?.filter { it.isNotBlank() } ?: MASTER_WATCHLIST_DEFAULT
        mutableStateOf(list)
    }

    // Sync watchlist from server on first load
    LaunchedEffect(Unit) {
        try {
            val serverWatchlist = withContext(Dispatchers.IO) { apiService.getWatchlist() }
            watchlist = serverWatchlist.tickers
            sharedPrefs.edit().putString("watchlist", serverWatchlist.tickers.joinToString(",")).apply()
        } catch (_: Exception) { /* Use local cache */ }
    }

    // Tuner Settings State
    var targetDelta by remember { mutableStateOf("-0.25") }
    var minRoc by remember { mutableStateOf("4.0") }

    if (showTunerDialog) {
        AlertDialog(
            onDismissRequest = { showTunerDialog = false },
            title = { Text("Tune Strategy Engine") },
            text = {
                Column {
                    OutlinedTextField(value = targetDelta, onValueChange = { targetDelta = it }, label = { Text("CSP Target Delta") })
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(value = minRoc, onValueChange = { minRoc = it }, label = { Text("Min. Monthly ROC (%)") })
                    Text("Note: Backend API tuner parameters will be passed with each scan request.", style = MaterialTheme.typography.bodySmall, color = Color.Gray, modifier = Modifier.padding(top=8.dp))
                }
            },
            confirmButton = {
                Button(onClick = { showTunerDialog = false }) { Text("Apply Locally") }
            }
        )
    }

    if (showWatchlistDialog) {
        var tempWatchlistText by remember { mutableStateOf(watchlist.joinToString(", ")) }
        AlertDialog(
            onDismissRequest = { showWatchlistDialog = false },
            title = { Text("Edit Market Watchlist") },
            text = {
                Column {
                    Text("Enter ticker symbols separated by commas or spaces.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = tempWatchlistText,
                        onValueChange = { tempWatchlistText = it.uppercase() },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("AAPL, MSFT, TSLA...") }
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val newList = tempWatchlistText.split(Regex("[,\\s]+"))
                        .filter { it.isNotBlank() }
                        .map { it.trim() }
                    if (newList.isNotEmpty()) {
                        watchlist = newList
                        sharedPrefs.edit().putString("watchlist", newList.joinToString(",")).apply()
                        showWatchlistDialog = false
                        Toast.makeText(context, "Watchlist updated (${newList.size} symbols)", Toast.LENGTH_SHORT).show()
                        // Sync to server
                        scope.launch {
                            try { withContext(Dispatchers.IO) { apiService.setWatchlist(WatchlistSetRequest(newList)) } }
                            catch (_: Exception) { }
                        }
                    }
                }) { Text("Save Watchlist") }
            },
            dismissButton = {
                TextButton(onClick = { showWatchlistDialog = false }) { Text("Cancel") }
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
        // Strategy Filter & Tuner
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            ExposedDropdownMenuBox(
                expanded = expandedDropdown,
                onExpandedChange = { expandedDropdown = !expandedDropdown },
                modifier = Modifier.weight(1f)
            ) {
                OutlinedTextField(
                    value = selectedStrategy,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Strategy") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedDropdown) },
                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyMedium
                )
                ExposedDropdownMenu(expanded = expandedDropdown, onDismissRequest = { expandedDropdown = false }) {
                    strategies.forEach { selectionOption ->
                        DropdownMenuItem(text = { Text(selectionOption) }, onClick = {
                            selectedStrategy = selectionOption
                            expandedDropdown = false
                        })
                    }
                }
            }
            IconButton(onClick = { showWatchlistDialog = true }) {
                Icon(Icons.Default.EditNote, contentDescription = "Edit Watchlist")
            }
            IconButton(onClick = { showAiKeysDialog = true }) {
                val hasKeys = AiKeyManager.hasAnyKeys(context)
                Icon(Icons.Default.Psychology, contentDescription = "AI Keys", tint = if (hasKeys) Color(0xFF7C3AED) else Color.Gray)
            }
            IconButton(onClick = { showTunerDialog = true }) {
                Icon(Icons.Default.Settings, contentDescription = "Tune Strategy")
            }
        }

        // AI Keys Dialog
        if (showAiKeysDialog) {
            AiApiKeysDialog(onDismiss = { showAiKeysDialog = false })
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Manual Search Bar
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = manualTicker,
                onValueChange = { manualTicker = it.uppercase() },
                label = { Text("Ticker") },
                placeholder = { Text("e.g. TSLA, AMD") },
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboardController?.hide() })
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Scan Action Button
        Button(
            onClick = {
                keyboardController?.hide()
                if (manualTicker.isBlank()) return@Button
                scope.launch {
                    try {
                        isLoading = true
                        scanResults = emptyList()
                        scanError = null
                        scanProgress = "Scanning ${manualTicker}..."

                        val strategyParam = when (selectedStrategy) {
                            "CSPs" -> "csp"
                            "Diagonals" -> "diagonal"
                            "Verticals" -> "vertical"
                            "Long LEAPS" -> "long_leaps"
                            else -> null
                        }
                        val deltaParam = targetDelta.toDoubleOrNull()
                        val rocParam = minRoc.toDoubleOrNull()

                        val results = apiService.getScanResults(
                            tickers = manualTicker,
                            strategy = strategyParam,
                            targetDelta = deltaParam,
                            minRoc = rocParam
                        )
                        scanResults = results
                        if (results.isEmpty()) {
                            scanError = "No opportunities found for this ticker."
                        }
                    } catch (e: Exception) {
                        Log.e("API_ERROR", "Scan failed: ${e.message}")
                        scanError = friendlyErrorMessage(e)
                    } finally {
                        isLoading = false
                        scanProgress = ""
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            enabled = !isLoading,
            shape = RoundedCornerShape(12.dp)
        ) {
            if (isLoading && scanProgress.contains("Scanning")) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(8.dp))
                Text(scanProgress, style = MaterialTheme.typography.labelLarge)
            } else {
                Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Scan Stocks", style = MaterialTheme.typography.labelLarge)
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Scan Watchlist Button (uses async scan with polling)
        Button(
            onClick = {
                keyboardController?.hide()
                scope.launch {
                    try {
                        isLoading = true
                        scanResults = emptyList()
                        scanError = null
                        scanProgress = "Starting watchlist scan..."

                        val strategyParam = when (selectedStrategy) {
                            "CSPs" -> "csp"; "Diagonals" -> "diagonal"
                            "Verticals" -> "vertical"; "Long LEAPS" -> "long_leaps"
                            else -> null
                        }

                        // Start async scan
                        val asyncResp = withContext(Dispatchers.IO) {
                            apiService.scanAsync(
                                tickers = watchlist.joinToString(","),
                                strategy = strategyParam
                            )
                        }
                        val jobId = asyncResp.jobId
                        val total = asyncResp.totalTickers ?: watchlist.size
                        scanProgress = "Scanning 0/$total symbols..."

                        // Poll for results
                        while (true) {
                            delay(2000)
                            val body = withContext(Dispatchers.IO) {
                                apiService.getScanStatus(jobId).string()
                            }
                            // Check if response is the status object or the final results array
                            if (body.trimStart().startsWith("[")) {
                                // Final results array
                                val results: List<ScanResultItem> = gson.fromJson(body, scanListType)
                                scanResults = results
                                if (results.isEmpty()) {
                                    scanError = "No opportunities found. Try adjusting tuner parameters or your watchlist."
                                }
                                break
                            } else {
                                // Status object
                                val status = gson.fromJson(body, AsyncScanStatus::class.java)
                                scanProgress = "Scanning ${status.tickersScanned ?: 0}/${status.totalTickers ?: total} symbols..."
                                if (status.status == "complete" || status.status == "failed") break
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("API_ERROR", "Async scan failed: ${e.message}")
                        // Fallback to batch scan
                        scanProgress = "Trying batch scan..."
                        try {
                            val strategyParam = when (selectedStrategy) {
                                "CSPs" -> "csp"; "Diagonals" -> "diagonal"
                                "Verticals" -> "vertical"; "Long LEAPS" -> "long_leaps"
                                else -> null
                            }
                            val batches = watchlist.chunked(5)
                            val combinedResults = mutableListOf<ScanResultItem>()
                            for ((index, batch) in batches.withIndex()) {
                                scanProgress = "Batch ${index + 1}/${batches.size}..."
                                try {
                                    combinedResults.addAll(apiService.getScanResults(tickers = batch.joinToString(","), strategy = strategyParam))
                                } catch (_: Exception) { }
                            }
                            scanResults = combinedResults
                            if (combinedResults.isEmpty()) scanError = "No results found."
                        } catch (e2: Exception) {
                            scanError = friendlyErrorMessage(e2)
                        }
                    } finally {
                        isLoading = false
                        scanProgress = ""
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            enabled = !isLoading,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669))
        ) {
            if (isLoading && (scanProgress.contains("Scanning") || scanProgress.contains("Batch") || scanProgress.contains("Starting"))) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(8.dp))
                Text(scanProgress, style = MaterialTheme.typography.labelLarge)
            } else {
                Icon(Icons.Default.Checklist, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Scan Watchlist (${watchlist.size} symbols)", style = MaterialTheme.typography.labelLarge)
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Scan Trending Button
        Button(
            onClick = {
                keyboardController?.hide()
                scope.launch {
                    try {
                        isLoading = true
                        scanResults = emptyList()
                        scanError = null
                        scanProgress = "Fetching trending stocks..."
                        val results = apiService.scanTrending()
                        scanResults = results
                        if (results.isEmpty()) {
                            scanError = "No trending stocks found."
                        }
                    } catch (e: Exception) {
                        Log.e("API_ERROR", "Trending scan failed: ${e.message}")
                        scanError = friendlyErrorMessage(e)
                    } finally {
                        isLoading = false
                        scanProgress = ""
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            enabled = !isLoading,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF59E0B))
        ) {
            if (isLoading && scanProgress.contains("Fetching")) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(8.dp))
                Text(scanProgress, style = MaterialTheme.typography.labelLarge)
            } else {
                Icon(Icons.Default.TrendingUp, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Scan Trending Stocks", style = MaterialTheme.typography.labelLarge)
            }
        }

        // Hint for editing watchlist
        if (manualTicker.isBlank()) {
            Row(
                modifier = Modifier.padding(start = 4.dp, top = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Tap ",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
                Icon(Icons.Default.EditNote, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.Gray)
                Text(
                    " to edit watchlist symbols",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Error / Status message
        if (scanError != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (scanResults.isNotEmpty()) MaterialTheme.colorScheme.tertiaryContainer
                    else MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (scanResults.isNotEmpty()) Icons.Default.Warning else Icons.Default.Error,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = scanError!!,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { scanError = null }, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Dismiss", modifier = Modifier.size(16.dp))
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Results List
        if (scanResults.isNotEmpty()) {
            // Sort the overall list of tickers based on the selected strategy's best metric
            val sortedResults = remember(scanResults, selectedStrategy) {
                when (selectedStrategy) {
                    "CSPs" -> scanResults.sortedByDescending { item ->
                        item.csps?.maxOfOrNull { it.roc.parseToDouble() } ?: -1.0
                    }
                    "Diagonals" -> scanResults.sortedByDescending { item ->
                        item.diagonals?.maxOfOrNull { it.yieldRatio.parseToDouble() } ?: -1.0
                    }
                    else -> scanResults
                }
            }
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(sortedResults) { item ->
                    ScanResultCard(
                        item, selectedStrategy, scope, context,
                        aiValidation = aiValidations[item.ticker],
                        isAiValidating = aiValidatingTickers.contains(item.ticker)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScanResultCard(
    item: ScanResultItem,
    strategyFilter: String,
    scope: kotlinx.coroutines.CoroutineScope,
    context: android.content.Context,
    aiValidation: AiCrossValidation? = null,
    isAiValidating: Boolean = false
) {
    val hasStrategies = !item.csps.isNullOrEmpty() || !item.diagonals.isNullOrEmpty() ||
            !item.verticals.isNullOrEmpty() || !item.longLeaps.isNullOrEmpty()

    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Header: Ticker + Name + Price + % Change
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = item.ticker, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                    if (item.name != null) {
                        Text(text = item.name, style = MaterialTheme.typography.bodySmall, color = Color.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (item.changePercent != null) {
                        val pctColor = if (item.changePercent >= 0) Color(0xFF2E7D32) else Color(0xFFC62828)
                        Card(colors = CardDefaults.cardColors(containerColor = pctColor.copy(alpha = 0.12f)), shape = RoundedCornerShape(8.dp)) {
                            Text(
                                text = "${if (item.changePercent >= 0) "+" else ""}${"%.2f".format(item.changePercent)}%",
                                fontWeight = FontWeight.Bold,
                                color = pctColor,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(8.dp)) {
                        Text(
                            text = "$${item.price}",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Key metrics (always visible): RSI, Beta, IV
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                if (item.rsi != null) {
                    val rsiColor = if (item.rsi < 30) Color(0xFF2E7D32) else if (item.rsi > 70) Color(0xFFC62828) else Color.Gray
                    Card(colors = CardDefaults.cardColors(containerColor = rsiColor.copy(alpha = 0.12f)), shape = RoundedCornerShape(6.dp)) {
                        Text("RSI ${"%.0f".format(item.rsi)}", modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = rsiColor)
                    }
                }
                if (item.beta != null) {
                    val betaColor = when {
                        item.beta < 0.8 -> Color(0xFF1565C0)   // Low vol — blue
                        item.beta <= 1.2 -> Color.Gray          // Normal
                        item.beta <= 1.8 -> Color(0xFFEF6C00)   // Elevated — orange
                        else -> Color(0xFFC62828)                // High vol — red
                    }
                    Card(colors = CardDefaults.cardColors(containerColor = betaColor.copy(alpha = 0.12f)), shape = RoundedCornerShape(6.dp)) {
                        Text("β ${"%.2f".format(item.beta)}", modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = betaColor)
                    }
                }
                if (item.ivRank != null) {
                    val ivNum = item.ivRank.replace("%", "").trim().toDoubleOrNull()
                    val ivColor = when {
                        ivNum == null -> Color.Gray
                        ivNum < 25 -> Color(0xFF1565C0)         // Low IV — blue
                        ivNum <= 50 -> Color.Gray                // Normal
                        ivNum <= 75 -> Color(0xFF2E7D32)         // High IV — green (good for sellers)
                        else -> Color(0xFFEF6C00)                // Very high IV — orange
                    }
                    Card(colors = CardDefaults.cardColors(containerColor = ivColor.copy(alpha = 0.12f)), shape = RoundedCornerShape(6.dp)) {
                        Text("IV ${item.ivRank}", modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = ivColor)
                    }
                }
                // Earnings date chip
                if (item.nextEarningsDate != null) {
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFD97706).copy(alpha = 0.12f)), shape = RoundedCornerShape(6.dp)) {
                        Text("📅 Earnings ${item.nextEarningsDate}", modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = Color(0xFFD97706))
                    }
                }
                // Analyst target price chip (consensus shown separately in recommendation badge below)
                if (item.analystTarget?.mean != null) {
                    val at = item.analystTarget
                    val targetColor = when {
                        (at.upsidePct ?: 0.0) >= 15.0 -> Color(0xFF2E7D32)
                        (at.upsidePct ?: 0.0) >= 0.0  -> Color(0xFF1565C0)
                        else                           -> Color(0xFFC62828)
                    }
                    val parts = buildList {
                        add("Analyst Target: $${"%.0f".format(at.mean)}")
                        if (at.upsidePct != null) add("${"%+.0f".format(at.upsidePct)}% from today")
                        if (at.numAnalysts != null) add("${at.numAnalysts} analysts")
                    }
                    Card(colors = CardDefaults.cardColors(containerColor = targetColor.copy(alpha = 0.12f)), shape = RoundedCornerShape(6.dp)) {
                        Text(
                            parts.joinToString(" · "),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = targetColor
                        )
                    }
                }
            }

            // Stock Recommendation Badge + Summary
            if (item.stockRecommendation != null || item.overall != null) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (item.stockRecommendation != null) {
                        val recColor = when {
                            item.stockRecommendation.contains("STRONG BUY", true) -> Color(0xFF1B5E20)
                            item.stockRecommendation.contains("BUY", true) -> Color(0xFF2E7D32)
                            item.stockRecommendation.contains("SELL", true) -> Color(0xFFC62828)
                            item.stockRecommendation.contains("HOLD", true) -> Color(0xFFEF6C00)
                            else -> Color.Gray
                        }
                        Card(colors = CardDefaults.cardColors(containerColor = recColor.copy(alpha = 0.15f))) {
                            Text(
                                item.stockRecommendation,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = recColor
                            )
                        }
                    }
                    if (item.overall != null) {
                        val overallColor = when {
                            item.overall.contains("STRONG", true) -> Color(0xFF1565C0)
                            item.overall.contains("OPPORTUNITY", true) -> Color(0xFF2E7D32)
                            item.overall.contains("CAUTION", true) -> Color(0xFFEF6C00)
                            else -> Color.Gray
                        }
                        Card(colors = CardDefaults.cardColors(containerColor = overallColor.copy(alpha = 0.12f))) {
                            Text(
                                item.overall,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = overallColor
                            )
                        }
                    }
                }
            }
            // Summary (always visible)
            if (item.stockSummary != null) {
                Text(
                    item.stockSummary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // AI Cross-Validation Badge (for Strong Buy results)
            if (aiValidation != null) {
                Spacer(modifier = Modifier.height(6.dp))
                AiCrossValidationBadge(validation = aiValidation)
            } else if (isAiValidating) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = Color(0xFF7C3AED))
                    Text("AI cross-validating...", style = MaterialTheme.typography.labelSmall, color = Color(0xFF7C3AED))
                }
            }

            // Expandable details section
            val hasDetails = item.sma200 != null || item.discountFromHigh != null ||
                    !item.bullishSignals.isNullOrEmpty() || !item.bearishSignals.isNullOrEmpty() ||
                    item.levels != null
            if (hasDetails) {
                var detailsExpanded by remember { mutableStateOf(false) }
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { detailsExpanded = !detailsExpanded }.padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (detailsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        if (detailsExpanded) "Hide details" else "More details",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }

                if (detailsExpanded) {
                    Spacer(modifier = Modifier.height(4.dp))
                    // SMA200 & Off High
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (item.sma200 != null) {
                            val aboveSma = item.price > item.sma200
                            val smaColor = if (aboveSma) Color(0xFF2E7D32) else Color(0xFFC62828)
                            Card(colors = CardDefaults.cardColors(containerColor = smaColor.copy(alpha = 0.12f)), shape = RoundedCornerShape(6.dp)) {
                                Text(
                                    "SMA200 $${"%.2f".format(item.sma200)} ${if (aboveSma) "▲" else "▼"}",
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = smaColor
                                )
                            }
                        }
                        if (item.discountFromHigh != null) {
                            Card(colors = CardDefaults.cardColors(containerColor = Color.Gray.copy(alpha = 0.10f)), shape = RoundedCornerShape(6.dp)) {
                                Text(
                                    "Off 52W High: ${item.discountFromHigh}",
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                    // SMA & Momentum chips (single line)
                    val smaSignals = mutableListOf<Pair<String, Boolean>>() // label, isBullish
                    val momentumSignals = mutableListOf<Pair<String, Boolean>>()
                    val otherBullish = mutableListOf<String>()
                    val otherBearish = mutableListOf<String>()
                    item.bullishSignals?.forEach { signal ->
                        val s = signal.trim()
                        when {
                            s.contains("SMA200", true) || s.contains("200-day", true) || s.contains("200 SMA", true) -> smaSignals.add("↑SMA200" to true)
                            s.contains("SMA50", true) || s.contains("50-day", true) || s.contains("50 SMA", true) -> smaSignals.add("↑SMA50" to true)
                            s.contains("momentum", true) || s.contains("uptrend", true) -> momentumSignals.add("Momentum — Uptrend" to true)
                            else -> otherBullish.add(abbreviateSignal(signal))
                        }
                    }
                    item.bearishSignals?.forEach { signal ->
                        val s = signal.trim()
                        when {
                            s.contains("SMA200", true) || s.contains("200-day", true) || s.contains("200 SMA", true) -> smaSignals.add("↓SMA200" to false)
                            s.contains("SMA50", true) || s.contains("50-day", true) || s.contains("50 SMA", true) -> smaSignals.add("↓SMA50" to false)
                            s.contains("momentum", true) || s.contains("downtrend", true) -> momentumSignals.add("Momentum — Downtrend" to false)
                            else -> otherBearish.add(abbreviateSignal(signal))
                        }
                    }
                    if (smaSignals.isNotEmpty() || momentumSignals.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            smaSignals.distinctBy { it.first }.forEach { (label, bullish) ->
                                val c = if (bullish) Color(0xFF2E7D32) else Color(0xFFC62828)
                                Card(colors = CardDefaults.cardColors(containerColor = c.copy(alpha = 0.12f)), shape = RoundedCornerShape(6.dp)) {
                                    Text(label, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = c)
                                }
                            }
                            momentumSignals.distinctBy { it.first }.forEach { (label, bullish) ->
                                val c = if (bullish) Color(0xFF2E7D32) else Color(0xFFC62828)
                                Card(colors = CardDefaults.cardColors(containerColor = c.copy(alpha = 0.12f)), shape = RoundedCornerShape(6.dp)) {
                                    Text(label, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = c)
                                }
                            }
                        }
                    }
                    // Other bullish / bearish signals
                    if (otherBullish.isNotEmpty() || otherBearish.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            otherBullish.forEach { signal ->
                                Text("▲ $signal", style = MaterialTheme.typography.bodySmall, color = Color(0xFF2E7D32))
                            }
                            otherBearish.forEach { signal ->
                                Text("▼ $signal", style = MaterialTheme.typography.bodySmall, color = Color(0xFFC62828))
                            }
                        }
                    }

                    // Key Levels (deduplicated)
                    if (item.levels != null) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text("Key Levels", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.height(6.dp))

                                // Collect all levels into an ordered list (high → low)
                                val stopVal = item.levels.stopLoss
                                val supportVal = item.levels.support
                                val swingLowVal = item.levels.swingLow60d
                                val targetVal = item.levels.target
                                val resistVal = item.levels.resistance
                                val swingHighVal = item.levels.swingHigh60d
                                val high52w = item.levels.high52w
                                val currentPrice = item.price

                                data class LevelRow(val label: String, val value: Double, val color: Color, val bold: Boolean = false, val emoji: String = "")
                                val levels = mutableListOf<LevelRow>()
                                if (high52w != null) levels.add(LevelRow("52-Week High", high52w, Color.Gray))
                                if (targetVal != null) levels.add(LevelRow("Target", targetVal, Color(0xFF2E7D32), bold = true, emoji = "🎯"))
                                if (resistVal != null && "%.2f".format(resistVal) != "%.2f".format(targetVal ?: -1.0)) levels.add(LevelRow("Resistance", resistVal, Color(0xFF1565C0)))
                                if (swingHighVal != null && "%.2f".format(swingHighVal) != "%.2f".format(targetVal ?: -1.0) && "%.2f".format(swingHighVal) != "%.2f".format(resistVal ?: -1.0)) levels.add(LevelRow("60d Swing High", swingHighVal, Color.Gray))
                                levels.add(LevelRow("Current Price", currentPrice, MaterialTheme.colorScheme.primary, bold = true, emoji = "📍"))
                                if (supportVal != null && "%.2f".format(supportVal) != "%.2f".format(stopVal ?: -1.0)) levels.add(LevelRow("Support", supportVal, Color(0xFFEF6C00)))
                                if (swingLowVal != null && "%.2f".format(swingLowVal) != "%.2f".format(stopVal ?: -1.0) && "%.2f".format(swingLowVal) != "%.2f".format(supportVal ?: -1.0)) levels.add(LevelRow("60d Swing Low", swingLowVal, Color.Gray))
                                if (stopVal != null) levels.add(LevelRow("Stop Loss", stopVal, Color(0xFFC62828), bold = true, emoji = "🛑"))

                                // Sort descending by value
                                val sortedLevels = levels.sortedByDescending { it.value }
                                sortedLevels.forEach { level ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            "${level.emoji}${if (level.emoji.isNotEmpty()) " " else ""}${level.label}",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = if (level.bold) FontWeight.Bold else FontWeight.Normal,
                                            color = level.color
                                        )
                                        Text(
                                            "$${"%.2f".format(level.value)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = if (level.bold) FontWeight.Bold else FontWeight.Normal,
                                            color = level.color
                                        )
                                    }
                                }
                                // Risk/Reward, Daily Move, 52W High chips
                                val hasChips = item.levels.riskReward != null || item.levels.atr != null || item.levels.high52w != null
                                if (hasChips) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        if (item.levels.riskReward != null) {
                                            val rr = item.levels.riskReward
                                            val rrColor = if (rr >= 2.0) Color(0xFF2E7D32) else if (rr >= 1.0) Color(0xFFEF6C00) else Color(0xFFC62828)
                                            val rrLabel = when {
                                                rr >= 3.0 -> "Excellent ${"%.1f".format(rr)}:1"
                                                rr >= 2.0 -> "Good ${"%.1f".format(rr)}:1"
                                                rr >= 1.0 -> "Fair ${"%.1f".format(rr)}:1"
                                                else -> "Poor ${"%.1f".format(rr)}:1 (risk > reward)"
                                            }
                                            Card(colors = CardDefaults.cardColors(containerColor = rrColor.copy(alpha = 0.12f)), shape = RoundedCornerShape(6.dp)) {
                                                Text("Risk/Reward: $rrLabel", modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = rrColor)
                                            }
                                        }
                                        if (item.levels.atr != null) {
                                            val atrPct = if (item.price > 0) (item.levels.atr / item.price) * 100 else 0.0
                                            Card(colors = CardDefaults.cardColors(containerColor = Color.Gray.copy(alpha = 0.10f)), shape = RoundedCornerShape(6.dp)) {
                                                Text("Avg Daily Move: $${"%.2f".format(item.levels.atr)} (${"%.1f".format(atrPct)}%)", modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall)
                                            }
                                        }
                                        if (item.levels.high52w != null) {
                                            Card(colors = CardDefaults.cardColors(containerColor = Color.Gray.copy(alpha = 0.10f)), shape = RoundedCornerShape(6.dp)) {
                                                Text("52W Hi $${"%.2f".format(item.levels.high52w)}", modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall)
                                            }
                                        }
                                    }
                                }
                                // Risk note
                                if (item.levels.riskNote != null) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(item.levels.riskNote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }

            if (!hasStrategies) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text(
                    "No strategy matches found — showing basic info only",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }

            // CSP Results (Best by ROC — 1 per stock)
            if (strategyFilter == "All" || strategyFilter == "CSPs") {
                val sortedCsps = item.csps?.sortedByDescending {
                    it.roc.parseToDouble()
                }?.take(1)

                sortedCsps?.forEach { csp ->
                    val expiryInfo = if (csp.expiry != null) " | Exp: ${csp.expiry.formatDate()}" else ""
                    OpportunityRow(
                        title = "CSP Strike ${csp.strike}",
                        subtitle = "Prem: $${csp.premium} | Delta: ${csp.delta} | ROC: ${csp.roc ?: "N/A"}$expiryInfo",
                        bt = csp.bt ?: "N/A",
                        riskNote = csp.riskNote,
                        onAdd = {
                            scope.launch {
                                try {
                                    val trade = TradeEntry(
                                        ticker = item.ticker, strike = csp.strike, expiry = csp.expiry ?: "45DTE",
                                        trigger_price = item.price, entry_premium = csp.premium,
                                        contracts = 1, strategy = "CSP", is_call = 0, is_buy = 0
                                    )
                                    val backendId = try {
                                        val resp = withContext(Dispatchers.IO) { apiService.addPosition(trade) }
                                        (resp["id"] as? Number)?.toInt()
                                    } catch (_: Exception) { null }
                                    PortfolioCache.addPosition(context, ActivePosition(id = backendId, ticker = item.ticker, strategy = "CSP", contracts = 1, strike = csp.strike, expiry = csp.expiry ?: "45DTE", entryPremium = csp.premium))
                                    Toast.makeText(context, "Added ${item.ticker} CSP to portfolio", Toast.LENGTH_SHORT).show()
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Failed to add: ${friendlyErrorMessage(e)}", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    )
                }
            }

            // Diagonal Results (Best by Yield — 1 per stock)
            if (strategyFilter == "All" || strategyFilter == "Diagonals") {
                val sortedDiagonals = item.diagonals?.sortedByDescending {
                    it.yieldRatio.parseToDouble()
                }?.take(1)

                sortedDiagonals?.forEach { diag ->
                    val expiryInfo = if (diag.expiry != null) " | Exp: ${diag.expiry.formatDate()}" else ""
                    OpportunityRow(
                        title = "Diagonal: BUY ${diag.longLeg.formatDate()} / SELL ${diag.shortLeg.formatDate()}",
                        subtitle = "Net Debit: $${diag.netDebt} | Yield: ${diag.yieldRatio ?: "N/A"}$expiryInfo",
                        bt = diag.bt ?: "N/A",
                        riskNote = diag.riskNote,
                        onAdd = {
                            scope.launch {
                                try {
                                    val trade = TradeEntry(
                                        ticker = item.ticker,
                                        strike = diag.netDebt,
                                        expiry = diag.expiry ?: "N/A",
                                        trigger_price = item.price,
                                        entry_premium = diag.netDebt,
                                        contracts = 1,
                                        strategy = "Diagonal BUY ${diag.longLeg ?: "?"} / SELL ${diag.shortLeg ?: "?"}",
                                        is_call = 1, is_buy = 1
                                    )
                                    val backendId = try {
                                        val resp = withContext(Dispatchers.IO) { apiService.addPosition(trade) }
                                        (resp["id"] as? Number)?.toInt()
                                    } catch (_: Exception) { null }
                                    PortfolioCache.addPosition(context, ActivePosition(id = backendId, ticker = item.ticker, strategy = trade.strategy, contracts = 1, strike = diag.netDebt, expiry = diag.expiry ?: "N/A", entryPremium = diag.netDebt))
                                    Toast.makeText(context, "Added ${item.ticker} Diagonal to portfolio", Toast.LENGTH_SHORT).show()
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Failed to add: ${friendlyErrorMessage(e)}", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    )
                }
            }

            // Vertical Results (Limit 10, sorted by yield desc)
            if (strategyFilter == "All" || strategyFilter == "Verticals") {
                val sortedVerticals = item.verticals?.mapNotNull { vert ->
                    // Parse strikes "L110.0/S180.0" to compute yield
                    val yieldPct = try {
                        val parts = vert.strikes?.replace("L", "")?.replace("S", "")?.split("/")
                        if (parts?.size == 2) {
                            val low = parts[0].toDouble()
                            val high = parts[1].toDouble()
                            val width = high - low
                            if (vert.netDebit > 0) ((width - vert.netDebit) / vert.netDebit) * 100.0 else null
                        } else null
                    } catch (_: Exception) { null }
                    Pair(vert, yieldPct)
                }?.sortedByDescending { it.second ?: -1.0 }?.take(1)

                sortedVerticals?.forEach { (vert, yieldPct) ->
                    val yieldStr = if (yieldPct != null) "%.1f%%".format(yieldPct) else "N/A"
                    val expiryInfo = if (vert.expiry != null) " | Exp: ${vert.expiry.formatDate()}" else ""
                    OpportunityRow(
                        title = "Vertical: ${vert.strikes ?: "N/A"}",
                        subtitle = "Net Debit: $${vert.netDebit} | Yield: $yieldStr$expiryInfo",
                        bt = vert.bt ?: "N/A",
                        riskNote = vert.riskNote,
                        onAdd = {
                            scope.launch {
                                try {
                                    val buyStrike = try {
                                        vert.strikes?.replace("L", "")?.split("/")?.get(0)?.toDouble() ?: 0.0
                                    } catch (_: Exception) { 0.0 }
                                    val trade = TradeEntry(
                                        ticker = item.ticker,
                                        strike = buyStrike,
                                        expiry = vert.expiry ?: "N/A",
                                        trigger_price = item.price,
                                        entry_premium = vert.netDebit,
                                        contracts = 1,
                                        strategy = "Vertical ${vert.strikes ?: ""}",
                                        is_call = 1, is_buy = 1
                                    )
                                    val backendId = try {
                                        val resp = withContext(Dispatchers.IO) { apiService.addPosition(trade) }
                                        (resp["id"] as? Number)?.toInt()
                                    } catch (_: Exception) { null }
                                    PortfolioCache.addPosition(context, ActivePosition(id = backendId, ticker = item.ticker, strategy = trade.strategy, contracts = 1, strike = buyStrike, expiry = vert.expiry ?: "N/A", entryPremium = vert.netDebit))
                                    Toast.makeText(context, "Added ${item.ticker} Vertical to portfolio", Toast.LENGTH_SHORT).show()
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Failed to add: ${friendlyErrorMessage(e)}", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    )
                }
            }

            // Long LEAPS Results UI Block (Limit 10)
            if (strategyFilter == "All" || strategyFilter == "Long LEAPS") {
                item.longLeaps?.take(1)?.forEach { leaps ->
                    val profileInfo = if (leaps.profile != null) " [${leaps.profile}]" else ""
                    val otmInfo = if (leaps.otmPct != null) " OTM: ${leaps.otmPct}" else ""
                    OpportunityRow(
                        title = "Long LEAPS: ${leaps.expiry.formatDate()} $${leaps.strike}C$profileInfo",
                        subtitle = "Prem: $${leaps.premium} | Lev: ${leaps.leverage ?: "N/A"} | Buffer: ${leaps.intrinsicBuffer ?: "N/A"}$otmInfo",
                        bt = leaps.bt ?: "N/A",
                        riskNote = leaps.riskNote,
                        onAdd = {
                            scope.launch {
                                try {
                                    val trade = TradeEntry(
                                        ticker = item.ticker, strike = leaps.strike, expiry = leaps.expiry,
                                        trigger_price = item.price, entry_premium = leaps.premium,
                                        contracts = 1, strategy = "Long LEAPS", is_call = 1, is_buy = 1
                                    )
                                    val backendId = try {
                                        val resp = withContext(Dispatchers.IO) { apiService.addPosition(trade) }
                                        (resp["id"] as? Number)?.toInt()
                                    } catch (_: Exception) { null }
                                    PortfolioCache.addPosition(context, ActivePosition(id = backendId, ticker = item.ticker, strategy = "Long LEAPS", contracts = 1, strike = leaps.strike, expiry = leaps.expiry, entryPremium = leaps.premium))
                                    Toast.makeText(context, "Added ${item.ticker} LEAPS to portfolio", Toast.LENGTH_SHORT).show()
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Failed to add: ${friendlyErrorMessage(e)}", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun OpportunityRow(title: String, subtitle: String, bt: String, riskNote: String? = null, onAdd: () -> Unit) {
    HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val btValue = bt.replace("%", "").toDoubleOrNull()
            val btColor = when {
                btValue != null && btValue >= 80 -> Color(0xFF2E7D32)
                btValue != null && btValue >= 60 -> Color(0xFFEF6C00)
                else -> Color(0xFFC62828)
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                Card(colors = CardDefaults.cardColors(containerColor = btColor.copy(alpha = 0.12f)), shape = RoundedCornerShape(6.dp)) {
                    Text(
                        "BT: $bt",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = btColor
                    )
                }
            }
            if (riskNote != null) {
                Text("⚠ $riskNote", style = MaterialTheme.typography.bodySmall, color = Color(0xFFEF6C00), modifier = Modifier.padding(top = 2.dp))
            }
        }
        FilledIconButton(
            onClick = onAdd,
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Icon(Icons.Default.Add, contentDescription = "Add Position", tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

// ==========================================
// AI GURU SCREEN
// ==========================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiGuruScreen() {
    val scope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current
    val context = LocalContext.current

    var ticker by remember { mutableStateOf("") }
    var selectedType by remember { mutableStateOf("CSP") }
    var expandedType by remember { mutableStateOf(false) }
    val typeOptions = listOf("CSP", "Sell Call", "Vertical", "Diagonal", "Long LEAPS")

    var strike by remember { mutableStateOf("") }
    var strikeSell by remember { mutableStateOf("") }
    var expiry by remember { mutableStateOf("") }
    var expirySell by remember { mutableStateOf("") }
    var premium by remember { mutableStateOf("") }

    var isLoading by remember { mutableStateOf(false) }
    var response by remember { mutableStateOf<BacktestResponse?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // AI cross-validation for backtest results
    var aiValidation by remember { mutableStateOf<AiCrossValidation?>(null) }
    var isAiValidating by remember { mutableStateOf(false) }

    // Auto-validate when backtest returns Strong Buy / BUY with High confidence
    LaunchedEffect(response) {
        val res = response ?: return@LaunchedEffect
        aiValidation = null
        if (!AiKeyManager.hasAnyKeys(context)) return@LaunchedEffect
        val isSell = selectedType in listOf("CSP", "Sell Call")
        // For sell strategies (CSP/Sell Call) the backend returns SELL/STRONG SELL for good trades
        val isStrongResult = if (isSell)
            res.verdict.contains("SELL", true) && (res.confidence != "None")
        else
            res.verdict.contains("BUY", true) && (res.confidence.equals("High", true) || res.verdict.contains("STRONG", true))
        if (!isStrongResult) return@LaunchedEffect

        isAiValidating = true
        try {
            aiValidation = AiCrossValidator.validate(
                context = context,
                ticker = ticker,
                price = res.price ?: 0.0,
                recommendation = res.verdict,
                signals = res.signals ?: emptyList(),
                warnings = res.warnings ?: emptyList(),
                levels = res.levels,
                sector = null,
                strategies = selectedType
            )
        } catch (e: Exception) {
            Log.e("AiGuru", "AI validation failed: ${e.message}")
        } finally {
            isAiValidating = false
        }
    }

    val isSpread = selectedType == "Vertical" || selectedType == "Diagonal"
    val isDiagonal = selectedType == "Diagonal"

    // Focus requesters for field navigation
    val strikeFocus = remember { FocusRequester() }
    val strikeSellFocus = remember { FocusRequester() }
    val expiryFocus = remember { FocusRequester() }
    val expirySellFocus = remember { FocusRequester() }
    val premiumFocus = remember { FocusRequester() }

    // Submit function
    fun submitForm() {
        if (ticker.isBlank() || isLoading) return
        keyboardController?.hide()

        // Normalise and validate expiry dates before sending
        val normExpiry = if (expiry.isNotBlank()) {
            normaliseExpiry(expiry) ?: run {
                errorMessage = "Unrecognised expiry format: \"$expiry\". Use YYYY-MM-DD (e.g. 2026-06-18) or DDMonYYYY (e.g. 18Jun2026)."
                return
            }
        } else null
        val normExpirySell = if (isDiagonal && expirySell.isNotBlank()) {
            normaliseExpiry(expirySell) ?: run {
                errorMessage = "Unrecognised sell-leg expiry format: \"$expirySell\". Use YYYY-MM-DD or DDMonYYYY."
                return
            }
        } else null

        isLoading = true
        errorMessage = null
        response = null
        val strategyKey = when (selectedType) {
            "CSP" -> "csp"; "Sell Call" -> "sell_call"; "Vertical" -> "vertical"
            "Diagonal" -> "diagonal"; "Long LEAPS" -> "long_leaps"; else -> "csp"
        }
        val action = when (selectedType) { "CSP", "Sell Call" -> "sell"; else -> "buy" }
        scope.launch {
            try {
                val request = BacktestRequest(
                    ticker = ticker, strategy = strategyKey, action = action,
                    strike = strike.toDoubleOrNull(), strike_sell = strikeSell.toDoubleOrNull(),
                    expiry = normExpiry,
                    expiry_sell = normExpirySell,
                    premium = premium.toDoubleOrNull()
                )
                response = withContext(Dispatchers.IO) { apiService.getBacktest(request) }
            } catch (e: Exception) { errorMessage = friendlyErrorMessage(e) }
            finally { isLoading = false }
        }
    }

    Scaffold { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.TipsAndUpdates,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("AI Guru", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                }
                Text(
                    "Select a strategy, enter your trade parameters, and get a backtesting-powered verdict.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                    modifier = Modifier.padding(top = 2.dp, bottom = 6.dp)
                )
            }

            // Ticker Input
            item {
                OutlinedTextField(
                    value = ticker,
                    onValueChange = { ticker = it.uppercase().trim() },
                    label = { Text("Stock Symbol") },
                    placeholder = { Text("e.g. TSLA") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
                )
            }

            // Type Selection
            item {
                ExposedDropdownMenuBox(expanded = expandedType, onExpandedChange = { expandedType = it }) {
                    OutlinedTextField(
                        value = selectedType,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Strategy Type") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedType) },
                        modifier = Modifier.fillMaxWidth().menuAnchor()
                    )
                    ExposedDropdownMenu(expanded = expandedType, onDismissRequest = { expandedType = false }) {
                        typeOptions.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option) },
                                onClick = { selectedType = option; expandedType = false }
                            )
                        }
                    }
                }
            }

            // Strategy-specific fields
            item {
                OutlinedTextField(
                    value = strike,
                    onValueChange = { strike = it },
                    label = { Text(if (isSpread) "Buy Leg Strike" else "Strike Price") },
                    placeholder = { Text("e.g. 200") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { if (isSpread) strikeSellFocus.requestFocus() else expiryFocus.requestFocus() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(strikeFocus)
                )
            }
            if (isSpread) {
                item {
                    OutlinedTextField(
                        value = strikeSell,
                        onValueChange = { strikeSell = it },
                        label = { Text("Sell Leg Strike") },
                        placeholder = { Text("e.g. 250") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(onNext = { expiryFocus.requestFocus() }),
                        modifier = Modifier.fillMaxWidth().focusRequester(strikeSellFocus)
                    )
                }
            }
            item {
                OutlinedTextField(
                    value = expiry,
                    onValueChange = { expiry = it },
                    label = { Text(if (isDiagonal) "Buy Leg Expiry" else "Expiry") },
                    placeholder = { Text("e.g. 2026-06-18 or 18Jun2026") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { if (isDiagonal) expirySellFocus.requestFocus() else premiumFocus.requestFocus() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(expiryFocus)
                )
            }
            if (isDiagonal) {
                item {
                    OutlinedTextField(
                        value = expirySell,
                        onValueChange = { expirySell = it },
                        label = { Text("Sell Leg Expiry") },
                        placeholder = { Text("e.g. 2026-05-16 or 16May2026") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(onNext = { premiumFocus.requestFocus() }),
                        modifier = Modifier.fillMaxWidth().focusRequester(expirySellFocus)
                    )
                }
            }
            item {
                OutlinedTextField(
                    value = premium,
                    onValueChange = { premium = it },
                    label = { Text(if (isSpread) "Net Debit" else "Premium") },
                    placeholder = { Text("e.g. 5.00") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submitForm() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(premiumFocus)
                )
            }

            // Ask AI Guru Button
            item {
                Button(
                    onClick = { submitForm() },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    enabled = ticker.isNotBlank() && !isLoading,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Analyzing...")
                    } else {
                        Icon(Icons.Default.TipsAndUpdates, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Ask AI Guru", fontWeight = FontWeight.Bold)
                    }
                }
            }

            // Error
            if (errorMessage != null) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Error, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onErrorContainer)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                errorMessage!!,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }

            // Recommendation Result
            if (response != null) {
                item { BacktestResultCard(response!!, isSellStrategy = selectedType in listOf("CSP", "Sell Call")) }

                // AI Cross-Validation for backtest
                if (aiValidation != null) {
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        AiCrossValidationBadge(validation = aiValidation)
                    }
                } else if (isAiValidating) {
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = Color(0xFF7C3AED))
                            Text("Cross-validating with AI engines...", style = MaterialTheme.typography.labelSmall, color = Color(0xFF7C3AED))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun BacktestResultCard(res: BacktestResponse, isSellStrategy: Boolean = false) {
    // For sell-action strategies (CSP, Sell Call), the backend verdicts are inverted:
    //   STRONG SELL = strongly recommend entering the trade (sell the put/call)
    //   SELL        = recommend entering the trade
    //   HOLD        = marginal, consider waiting
    //   AVOID       = skip this trade
    // For stock/buy strategies, verdicts are stock-direction signals (BUY/SELL/HOLD/AVOID).
    val rawVerdict = res.verdict.uppercase()
    val displayVerdict: String
    val verdictColor: Color
    val verdictSubtitle: String   // brief explainer shown under the verdict
    if (isSellStrategy) {
        when (rawVerdict) {
            "STRONG SELL" -> {
                displayVerdict = "STRONG ENTRY"
                verdictColor = Color(0xFF1B5E20)
                verdictSubtitle = "Backtest strongly supports selling this option"
            }
            "SELL" -> {
                displayVerdict = "ENTER TRADE"
                verdictColor = Color(0xFF2E7D32)
                verdictSubtitle = "Conditions support entering this position"
            }
            "HOLD" -> {
                displayVerdict = "WAIT"
                verdictColor = Color(0xFFEF6C00)
                verdictSubtitle = "Setup is marginal — consider waiting for better conditions"
            }
            "AVOID" -> {
                displayVerdict = "SKIP"
                verdictColor = Color(0xFFC62828)
                verdictSubtitle = "Current conditions do not favour this trade"
            }
            else -> {
                displayVerdict = rawVerdict
                verdictColor = Color(0xFF757575)
                verdictSubtitle = ""
            }
        }
    } else {
        when (rawVerdict) {
            "STRONG BUY" -> { displayVerdict = "STRONG BUY"; verdictColor = Color(0xFF1B5E20); verdictSubtitle = "" }
            "BUY"        -> { displayVerdict = "BUY";        verdictColor = Color(0xFF2E7D32); verdictSubtitle = "" }
            "SELL"       -> { displayVerdict = "SELL";       verdictColor = Color(0xFFC62828); verdictSubtitle = "" }
            "STRONG SELL"-> { displayVerdict = "STRONG SELL";verdictColor = Color(0xFFB71C1C); verdictSubtitle = "" }
            "HOLD"       -> { displayVerdict = "HOLD";       verdictColor = Color(0xFFEF6C00); verdictSubtitle = "" }
            else         -> { displayVerdict = rawVerdict;   verdictColor = Color(0xFF757575); verdictSubtitle = "" }
        }
    }
    // Confidence label — for sell strategies, clarify that confidence reflects signal quality,
    // not the backtest success rate (which is shown separately in the metrics row).
    val confidenceLabel = when (res.confidence) {
        "High"   -> if (isSellStrategy) "High Signal Confidence" else "High Confidence"
        "Medium" -> if (isSellStrategy) "Mixed Signals" else "Medium Confidence"
        "Low"    -> if (isSellStrategy) "Signals Mixed — see backtest score" else "Low Confidence"
        "None"   -> if (isSellStrategy) "No Signals — use caution" else "No Confidence"
        else     -> "${res.confidence} Confidence"
    }
    val confidenceColor = when (res.confidence) {
        "High" -> Color(0xFF2E7D32)
        "Medium" -> Color(0xFFEF6C00)
        else -> Color(0xFF757575)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = verdictColor.copy(alpha = 0.08f)),
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Verdict header + confidence
            Column {
                Text(
                    displayVerdict,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Black,
                    color = verdictColor
                )
                if (verdictSubtitle.isNotEmpty()) {
                    Text(
                        verdictSubtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = verdictColor.copy(alpha = 0.8f),
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = confidenceColor.copy(alpha = 0.12f)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        confidenceLabel,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = confidenceColor
                    )
                }
            }

            // Summary
            Text(res.summary, style = MaterialTheme.typography.bodyLarge)

            HorizontalDivider()

            // Key metrics row
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    if (res.price != null) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Price", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                            Text("$${"%,.2f".format(res.price)}", fontWeight = FontWeight.Bold)
                        }
                    }
                    if (res.rsi != null) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("RSI", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                            Text(
                                "${"%.1f".format(res.rsi)}",
                                fontWeight = FontWeight.Bold,
                                color = if (res.rsi < 30) Color(0xFF2E7D32) else if (res.rsi > 70) Color(0xFFC62828) else Color.Unspecified
                            )
                        }
                    }
                    if (res.backtestScore != null) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Backtest", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                            Text(res.backtestScore, fontWeight = FontWeight.Bold, color = Color(0xFF1565C0))
                        }
                    }
                }
            }

            // Signals
            val signals = res.signals ?: emptyList()
            if (signals.isNotEmpty()) {
                HorizontalDivider()
                Text("Signals", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                signals.forEach { signal ->
                    Row(modifier = Modifier.padding(vertical = 1.dp)) {
                        Text("✦ ", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                        Text(signal, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            // Warnings
            val warnings = res.warnings ?: emptyList()
            if (warnings.isNotEmpty()) {
                HorizontalDivider()
                Text("Warnings", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Color(0xFFEF6C00))
                warnings.forEach { warning ->
                    Row(modifier = Modifier.padding(vertical = 1.dp)) {
                        Text("⚠ ", style = MaterialTheme.typography.bodyMedium)
                        Text(warning, style = MaterialTheme.typography.bodyMedium, color = Color(0xFFEF6C00))
                    }
                }
            }

            // Key Levels from backtest
            if (res.levels != null) {
                HorizontalDivider()
                Text("Key Levels", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                val lvl = res.levels
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (lvl.target != null) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("🎯 Target", style = MaterialTheme.typography.bodySmall, color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold)
                            Text("$${"%.2f".format(lvl.target)}", style = MaterialTheme.typography.bodySmall, color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold)
                        }
                    }
                    if (lvl.resistance != null) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Resistance", style = MaterialTheme.typography.bodySmall, color = Color(0xFF1565C0))
                            Text("$${"%.2f".format(lvl.resistance)}", style = MaterialTheme.typography.bodySmall, color = Color(0xFF1565C0))
                        }
                    }
                    if (lvl.support != null) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Support", style = MaterialTheme.typography.bodySmall, color = Color(0xFFEF6C00))
                            Text("$${"%.2f".format(lvl.support)}", style = MaterialTheme.typography.bodySmall, color = Color(0xFFEF6C00))
                        }
                    }
                    if (lvl.stopLoss != null) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("🛑 Stop Loss", style = MaterialTheme.typography.bodySmall, color = Color(0xFFC62828), fontWeight = FontWeight.Bold)
                            Text("$${"%.2f".format(lvl.stopLoss)}", style = MaterialTheme.typography.bodySmall, color = Color(0xFFC62828), fontWeight = FontWeight.Bold)
                        }
                    }
                    if (lvl.riskReward != null) {
                        val rr = lvl.riskReward
                        val rrColor = if (rr >= 2.0) Color(0xFF2E7D32) else if (rr >= 1.0) Color(0xFFEF6C00) else Color(0xFFC62828)
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Risk/Reward", style = MaterialTheme.typography.bodySmall)
                            Text("${"%.1f".format(rr)}:1", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = rrColor)
                        }
                    }
                    if (lvl.riskNote != null) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text("💡 ${lvl.riskNote}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // AI Learning info
            if (res.learning != null && res.learning.enabled) {
                HorizontalDivider()
                Text("AI Learning", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Color(0xFF7C3AED))
                if (res.learning.adjustmentReason != null) {
                    Text(res.learning.adjustmentReason, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
                if (res.learning.applied == true && res.learning.originalVerdict != res.learning.adjustedVerdict) {
                    Text(
                        "Adjusted: ${res.learning.originalVerdict} → ${res.learning.adjustedVerdict}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFEF6C00)
                    )
                }
            }
        }
    }
}

@Composable
fun PortfolioScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var healthData by remember { mutableStateOf<HealthResponse?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showAddManualDialog by remember { mutableStateOf(false) }
    var closingPosition by remember { mutableStateOf<ActivePosition?>(null) }
    var closingIndex by remember { mutableIntStateOf(-1) }
    var editingPosition by remember { mutableStateOf<ActivePosition?>(null) }
    var editingIndex by remember { mutableIntStateOf(-1) }
    var deletingPosition by remember { mutableStateOf<ActivePosition?>(null) }
    var deletingIndex by remember { mutableIntStateOf(-1) }

    fun refreshData() {
        scope.launch {
            try {
                isLoading = true
                errorMessage = null
                val response = try {
                    apiService.getPositions()
                } catch (_: Exception) {
                    apiService.getHealth()
                }
                val cachedActive = PortfolioCache.loadActivePositions(context)
                val backendActive = response.activePositions
                val backendClosed = response.closedPositions ?: emptyList()
                if (backendActive.isNotEmpty() || cachedActive.isEmpty()) {
                    val backendIds = backendActive.mapNotNull { it.id }.toSet()
                    val localOnly = cachedActive.filter { it.id == null || it.id !in backendIds }
                    val mergedActive = backendActive + localOnly
                    val cachedClosed = PortfolioCache.loadClosedPositions(context)
                    val backendClosedIds = backendClosed.mapNotNull { it.id }.toSet()
                    val localOnlyClosed = cachedClosed.filter { it.id == null || it.id !in backendClosedIds }
                    val mergedClosed = backendClosed + localOnlyClosed
                    healthData = response.copy(activePositions = mergedActive, closedPositions = mergedClosed)
                    PortfolioCache.savePositions(context, mergedActive, mergedClosed)
                } else {
                    val cachedClosed = PortfolioCache.loadClosedPositions(context)
                    healthData = HealthResponse(
                        status = response.status,
                        capitalHealth = response.capitalHealth,
                        performance = response.performance,
                        activePositions = cachedActive,
                        closedPositions = cachedClosed
                    )
                }
            } catch (e: Exception) {
                Log.e("PORTFOLIO", "Health load failed: ${e.message}")
                errorMessage = friendlyErrorMessage(e)
                // If backend fails and we have no data yet, load from local cache
                if (healthData == null) {
                    val cachedActive = PortfolioCache.loadActivePositions(context)
                    val cachedClosed = PortfolioCache.loadClosedPositions(context)
                    if (cachedActive.isNotEmpty() || cachedClosed.isNotEmpty()) {
                        healthData = HealthResponse(
                            status = "cached",
                            capitalHealth = CapitalHealth(0.0),
                            performance = PerformanceMetrics(0.0, "N/A"),
                            activePositions = cachedActive,
                            closedPositions = cachedClosed
                        )
                        errorMessage = null
                    }
                }
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        // Load cached data first for instant display
        val cachedActive = PortfolioCache.loadActivePositions(context)
        val cachedClosed = PortfolioCache.loadClosedPositions(context)
        if (cachedActive.isNotEmpty() || cachedClosed.isNotEmpty()) {
            healthData = HealthResponse(
                status = "cached",
                capitalHealth = CapitalHealth(0.0),
                performance = PerformanceMetrics(0.0, "N/A"),
                activePositions = cachedActive,
                closedPositions = cachedClosed
            )
        }
        // Then refresh from backend (will overwrite with live data)
        refreshData()
    }

    // --- Dialogs ---

    if (showAddManualDialog) {
        AddManualPositionDialog(
            onDismiss = { showAddManualDialog = false },
            onSave = { trade ->
                scope.launch {
                    try {
                        val backendId = try {
                            val resp = withContext(Dispatchers.IO) { apiService.addPosition(trade) }
                            (resp["id"] as? Number)?.toInt()
                        } catch (_: Exception) { null }
                        if (trade.exit_price != null) {
                            val current = PortfolioCache.loadClosedPositions(context).toMutableList()
                            current.add(ClosedPosition(
                                id = backendId, ticker = trade.ticker, strategy = trade.strategy,
                                contracts = trade.contracts, strike = trade.strike, expiry = trade.expiry,
                                entryPremium = trade.entry_premium, exitPrice = trade.exit_price,
                                exitDate = trade.exit_date ?: ""
                            ))
                            val active = PortfolioCache.loadActivePositions(context)
                            PortfolioCache.savePositions(context, active, current)
                        } else {
                            PortfolioCache.addPosition(context, ActivePosition(
                                id = backendId, ticker = trade.ticker, strategy = trade.strategy,
                                contracts = trade.contracts, strike = trade.strike, expiry = trade.expiry,
                                entryPremium = trade.entry_premium
                            ))
                        }
                        showAddManualDialog = false
                        val cachedActive = PortfolioCache.loadActivePositions(context)
                        val cachedClosed = PortfolioCache.loadClosedPositions(context)
                        healthData = HealthResponse(
                            status = healthData?.status ?: "cached",
                            capitalHealth = healthData?.capitalHealth ?: CapitalHealth(0.0),
                            performance = healthData?.performance ?: PerformanceMetrics(0.0, "N/A"),
                            activePositions = cachedActive,
                            closedPositions = cachedClosed
                        )
                        snackbarHostState.showSnackbar("Position added successfully")
                    } catch (e: Exception) {
                        snackbarHostState.showSnackbar("Failed to add: ${friendlyErrorMessage(e)}")
                    }
                }
            }
        )
    }

    if (closingPosition != null) {
        ClosePositionDialog(
            position = closingPosition!!,
            onDismiss = { closingPosition = null; closingIndex = -1 },
            onConfirm = { exitPrice, exitDate ->
                scope.launch {
                    try {
                        val pos = closingPosition!!
                        val idx = closingIndex
                        try {
                            pos.id?.let { apiService.closePosition(it, mapOf("exit_price" to exitPrice, "exit_date" to exitDate)) }
                        } catch (_: Exception) { }
                        if (idx >= 0) {
                            PortfolioCache.removePosition(context, idx)
                        }
                        val closedList = PortfolioCache.loadClosedPositions(context).toMutableList()
                        closedList.add(ClosedPosition(
                            id = pos.id, ticker = pos.ticker, strategy = pos.strategy,
                            contracts = pos.contracts, strike = pos.strike, expiry = pos.expiry,
                            entryPremium = pos.entryPremium,
                            exitPrice = exitPrice.toDoubleOrNull() ?: 0.0,
                            exitDate = exitDate
                        ))
                        val activeList = PortfolioCache.loadActivePositions(context)
                        PortfolioCache.savePositions(context, activeList, closedList)
                        closingPosition = null
                        closingIndex = -1
                        healthData = HealthResponse(
                            status = healthData?.status ?: "cached",
                            capitalHealth = healthData?.capitalHealth ?: CapitalHealth(0.0),
                            performance = healthData?.performance ?: PerformanceMetrics(0.0, "N/A"),
                            activePositions = activeList,
                            closedPositions = closedList
                        )
                        snackbarHostState.showSnackbar("Position closed")
                    } catch (e: Exception) {
                        snackbarHostState.showSnackbar("Failed to close: ${friendlyErrorMessage(e)}")
                    }
                }
            }
        )
    }

    if (editingPosition != null) {
        EditPositionDialog(
            position = editingPosition!!,
            onDismiss = { editingPosition = null; editingIndex = -1 },
            onSave = { trade ->
                scope.launch {
                    try {
                        // Update local cache directly
                        val updatedPos = ActivePosition(
                            id = editingPosition?.id,
                            ticker = trade.ticker,
                            strategy = trade.strategy,
                            contracts = trade.contracts,
                            strike = trade.strike,
                            expiry = trade.expiry,
                            entryPremium = trade.entry_premium
                        )
                        if (editingIndex >= 0) {
                            PortfolioCache.updatePosition(context, editingIndex, updatedPos)
                        }
                        // Best-effort backend sync (only update, never create duplicate)
                        try {
                            val posId = editingPosition?.id
                            if (posId != null) apiService.updatePosition(posId, trade)
                        } catch (_: Exception) { }
                        editingPosition = null
                        editingIndex = -1
                        // Reload from cache to reflect the edit
                        val cachedActive = PortfolioCache.loadActivePositions(context)
                        val cachedClosed = PortfolioCache.loadClosedPositions(context)
                        healthData = HealthResponse(
                            status = healthData?.status ?: "cached",
                            capitalHealth = healthData?.capitalHealth ?: CapitalHealth(0.0),
                            performance = healthData?.performance ?: PerformanceMetrics(0.0, "N/A"),
                            activePositions = cachedActive,
                            closedPositions = cachedClosed
                        )
                        snackbarHostState.showSnackbar("Position updated")
                    } catch (e: Exception) {
                        snackbarHostState.showSnackbar("Failed to update: ${friendlyErrorMessage(e)}")
                    }
                }
            }
        )
    }

    if (deletingPosition != null) {
        AlertDialog(
            onDismissRequest = { deletingPosition = null },
            icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Delete Position") },
            text = {
                Text("Remove ${deletingPosition!!.ticker} ${deletingPosition!!.strategy} (${deletingPosition!!.contracts}x ${deletingPosition!!.strike})?\n\nThis action cannot be undone.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        val pos = deletingPosition!!
                        val idx = deletingIndex
                        deletingPosition = null
                        deletingIndex = -1
                        scope.launch {
                            try {
                                // Remove from local cache
                                if (idx >= 0) {
                                    PortfolioCache.removePosition(context, idx)
                                }
                                // Best-effort backend sync
                                try { pos.id?.let { apiService.removePosition(it) } } catch (_: Exception) { }
                                // Reload from cache
                                val cachedActive = PortfolioCache.loadActivePositions(context)
                                val cachedClosed = PortfolioCache.loadClosedPositions(context)
                                healthData = HealthResponse(
                                    status = healthData?.status ?: "cached",
                                    capitalHealth = healthData?.capitalHealth ?: CapitalHealth(0.0),
                                    performance = healthData?.performance ?: PerformanceMetrics(0.0, "N/A"),
                                    activePositions = cachedActive,
                                    closedPositions = cachedClosed
                                )
                                snackbarHostState.showSnackbar("${pos.ticker} position removed")
                            } catch (e: Exception) {
                                snackbarHostState.showSnackbar("Failed to delete: ${friendlyErrorMessage(e)}")
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deletingPosition = null }) { Text("Cancel") }
            }
        )
    }

    // --- Main Layout ---

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddManualDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add Manual Position")
            }
        }
    ) { padding ->
        // Initial state or error state with retry
        if (!isLoading && healthData == null && errorMessage == null) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (!isLoading && healthData == null && errorMessage != null) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                    Icon(Icons.Default.Warning, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.error)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Could not load portfolio", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(errorMessage!!, style = MaterialTheme.typography.bodyMedium, color = Color.Gray, textAlign = TextAlign.Center)
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(onClick = { refreshData() }) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Retry")
                    }
                }
            }
        }
        // Loading state
        else if (isLoading && healthData == null) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Loading portfolio...", style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
                }
            }
        }
        // Data loaded
        else if (healthData != null) {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
                item {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Portfolio Health", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        IconButton(onClick = { refreshData() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                        }
                    }
                    if (isLoading) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    Spacer(modifier = Modifier.height(16.dp))

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Capital Committed", style = MaterialTheme.typography.labelLarge)
                            Text("$${"%,.2f".format(healthData?.capitalHealth?.committed)}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Monthly Realized Profit", style = MaterialTheme.typography.labelLarge)
                            Text("$${"%,.2f".format(healthData?.performance?.monthlyRealized)}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Text("Goal Progress: ${healthData?.performance?.progress}", style = MaterialTheme.typography.bodyMedium)
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Active Positions", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text("${healthData?.activePositions?.size ?: 0}", style = MaterialTheme.typography.titleMedium, color = Color.Gray)
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                }

                val activePositions = healthData?.activePositions ?: emptyList()
                if (activePositions.isEmpty()) {
                    item {
                        Text(
                            "No active positions. Tap + to add one.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.Gray,
                            modifier = Modifier.padding(vertical = 16.dp).fillMaxWidth(),
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    itemsIndexed(activePositions) { index, pos ->
                        PositionCard(
                            pos = pos,
                            onEdit = { editingPosition = pos; editingIndex = index },
                            onRemove = { deletingPosition = pos; deletingIndex = index },
                            onClose = { closingPosition = pos; closingIndex = index }
                        )
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(24.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Closed Positions", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text("${healthData?.closedPositions?.size ?: 0}", style = MaterialTheme.typography.titleMedium, color = Color.Gray)
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                }

                val closedPositions = healthData?.closedPositions ?: emptyList()
                if (closedPositions.isEmpty()) {
                    item {
                        Text(
                            "No closed positions yet.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.Gray,
                            modifier = Modifier.padding(vertical = 16.dp).fillMaxWidth(),
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    items(closedPositions) { pos ->
                        ClosedPositionCard(pos)
                    }
                }

                // Bottom spacer for FAB
                item { Spacer(modifier = Modifier.height(80.dp)) }
            }
        }
    }
}

@Composable
fun PositionCard(pos: ActivePosition, onEdit: () -> Unit, onRemove: () -> Unit, onClose: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(pos.ticker, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                pos.strategy,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("${pos.contracts}x $${pos.strike} | Exp: ${pos.expiry}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        "$${pos.entryPremium}",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Edit", style = MaterialTheme.typography.labelMedium)
                }
                TextButton(onClick = onClose) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(15.dp), tint = Color(0xFF388E3C))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Close", style = MaterialTheme.typography.labelMedium, color = Color(0xFF388E3C))
                }
                TextButton(onClick = onRemove) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(15.dp), tint = MaterialTheme.colorScheme.error)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Delete", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
fun ClosedPositionCard(pos: ClosedPosition) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("${pos.ticker} ${pos.strategy}", fontWeight = FontWeight.Bold)
                Text("${pos.contracts}x $${pos.strike} | Exp: ${pos.expiry}", style = MaterialTheme.typography.bodySmall)
                Text("Closed on ${pos.exitDate}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
            Column(horizontalAlignment = Alignment.End) {
                val profit = (pos.exitPrice - pos.entryPremium) * pos.contracts * 100
                Text("Exit: $${pos.exitPrice}", fontWeight = FontWeight.Bold)
                Text(
                    text = "${if (profit >= 0) "+" else ""}$${"%.2f".format(profit)}",
                    color = if (profit >= 0) Color(0xFF388E3C) else Color.Red,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClosePositionDialog(position: ActivePosition, onDismiss: () -> Unit, onConfirm: (String, String) -> Unit) {
    var exitPrice by remember { mutableStateOf("") }
    var exitDate by remember { mutableStateOf(java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Close Position: ${position.ticker}") },
        text = {
            Column {
                Text("${position.strategy} | ${position.contracts}x $${position.strike}")
                Text("Entry Premium: $${position.entryPremium}", color = Color.Gray)
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = exitPrice,
                    onValueChange = { exitPrice = it },
                    label = { Text("Exit Price") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = exitDate,
                    onValueChange = { exitDate = it },
                    label = { Text("Exit Date (YYYY-MM-DD)") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(exitPrice, exitDate) },
                enabled = exitPrice.toDoubleOrNull() != null && exitDate.isNotBlank()
            ) { Text("Confirm Close") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditPositionDialog(position: ActivePosition, onDismiss: () -> Unit, onSave: (TradeEntry) -> Unit) {
    var contracts by remember { mutableStateOf(position.contracts.toString()) }
    var strike by remember { mutableStateOf(position.strike.toString()) }
    var expiry by remember { mutableStateOf(position.expiry) }
    var entryPremium by remember { mutableStateOf(position.entryPremium.toString()) }
    var strategy by remember { mutableStateOf(position.strategy) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit: ${position.ticker}") },
        text = {
            Column {
                OutlinedTextField(value = strategy, onValueChange = { strategy = it }, label = { Text("Strategy") }, modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(value = strike, onValueChange = { strike = it }, label = { Text("Strike Price") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(value = contracts, onValueChange = { contracts = it }, label = { Text("Contracts") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(value = expiry, onValueChange = { expiry = it }, label = { Text("Expiry Date (YYYY-MM-DD)") }, modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(value = entryPremium, onValueChange = { entryPremium = it }, label = { Text("Entry Premium") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val trade = TradeEntry(
                        ticker = position.ticker,
                        strike = strike.toDoubleOrNull() ?: position.strike,
                        expiry = expiry,
                        trigger_price = 0.0,
                        entry_premium = entryPremium.toDoubleOrNull() ?: position.entryPremium,
                        contracts = contracts.toIntOrNull() ?: position.contracts,
                        strategy = strategy,
                        is_call = if (strategy.contains("Call", true) || strategy.contains("LEAPS", true)) 1 else 0,
                        is_buy = 0
                    )
                    onSave(trade)
                },
                enabled = strike.toDoubleOrNull() != null && contracts.toIntOrNull() != null && entryPremium.toDoubleOrNull() != null
            ) { Text("Save Changes") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddManualPositionDialog(onDismiss: () -> Unit, onSave: (TradeEntry) -> Unit) {
    var ticker by remember { mutableStateOf("") }
    var strategy by remember { mutableStateOf("CSP") }
    var expandedStrategy by remember { mutableStateOf(false) }
    val strategyOptions = listOf("CSP", "Vertical", "Diagonal", "Long LEAPS")
    var contracts by remember { mutableStateOf("1") }
    var strike by remember { mutableStateOf("") }
    var strikeSell by remember { mutableStateOf("") }
    var expiry by remember { mutableStateOf(java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())) }
    var expiryShort by remember { mutableStateOf("") }
    var entryPremium by remember { mutableStateOf("") }
    var isClosed by remember { mutableStateOf(false) }
    var exitPrice by remember { mutableStateOf("") }
    var exitDate by remember { mutableStateOf(java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())) }

    val isSpread = strategy == "Vertical" || strategy == "Diagonal"
    val isValid = ticker.isNotBlank() && strike.toDoubleOrNull() != null && entryPremium.toDoubleOrNull() != null &&
            (!isSpread || strikeSell.toDoubleOrNull() != null) &&
            (!isClosed || exitPrice.toDoubleOrNull() != null)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Manual Position") },
        text = {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                item {
                    OutlinedTextField(value = ticker, onValueChange = { ticker = it.uppercase() }, label = { Text("Ticker *") }, modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(8.dp))

                    // Strategy dropdown
                    ExposedDropdownMenuBox(
                        expanded = expandedStrategy,
                        onExpandedChange = { expandedStrategy = !expandedStrategy }
                    ) {
                        OutlinedTextField(
                            value = strategy,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Strategy") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedStrategy) },
                            modifier = Modifier.menuAnchor().fillMaxWidth()
                        )
                        ExposedDropdownMenu(expanded = expandedStrategy, onDismissRequest = { expandedStrategy = false }) {
                            strategyOptions.forEach { option ->
                                DropdownMenuItem(text = { Text(option) }, onClick = {
                                    strategy = option
                                    expandedStrategy = false
                                })
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))

                    // Buy leg strike
                    OutlinedTextField(
                        value = strike, onValueChange = { strike = it },
                        label = { Text(if (isSpread) "Buy Leg Strike *" else "Strike Price *") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    // Sell leg strike (only for spreads)
                    if (isSpread) {
                        OutlinedTextField(
                            value = strikeSell, onValueChange = { strikeSell = it },
                            label = { Text("Sell Leg Strike *") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    OutlinedTextField(value = contracts, onValueChange = { contracts = it }, label = { Text("Contracts") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(value = expiry, onValueChange = { expiry = it }, label = { Text(if (isSpread && strategy == "Diagonal") "Buy Leg Expiry (YYYY-MM-DD)" else "Expiry Date (YYYY-MM-DD)") }, modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(8.dp))

                    // Short leg expiry for Diagonal
                    if (strategy == "Diagonal") {
                        OutlinedTextField(value = expiryShort, onValueChange = { expiryShort = it }, label = { Text("Sell Leg Expiry (YYYY-MM-DD)") }, modifier = Modifier.fillMaxWidth())
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    OutlinedTextField(value = entryPremium, onValueChange = { entryPremium = it }, label = { Text(if (isSpread) "Net Debit *" else "Entry Premium *") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = isClosed, onCheckedChange = { isClosed = it })
                        Text("Add as Closed Position")
                    }

                    if (isClosed) {
                        OutlinedTextField(value = exitPrice, onValueChange = { exitPrice = it }, label = { Text("Exit Price *") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(value = exitDate, onValueChange = { exitDate = it }, label = { Text("Exit Date (YYYY-MM-DD)") }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val strategyStr = if (isSpread) {
                        val sellStrikeVal = strikeSell.toDoubleOrNull() ?: 0.0
                        val buyStrikeVal = strike.toDoubleOrNull() ?: 0.0
                        if (strategy == "Diagonal") {
                            "Diagonal BUY $expiry $$buyStrikeVal / SELL ${expiryShort.ifBlank { "N/A" }} $$sellStrikeVal"
                        } else {
                            "Vertical L$buyStrikeVal/S$sellStrikeVal"
                        }
                    } else strategy

                    val trade = TradeEntry(
                        ticker = ticker,
                        strike = strike.toDoubleOrNull() ?: 0.0,
                        expiry = expiry,
                        trigger_price = 0.0,
                        entry_premium = entryPremium.toDoubleOrNull() ?: 0.0,
                        contracts = contracts.toIntOrNull() ?: 1,
                        strategy = strategyStr,
                        is_call = if (strategy.contains("Call", true) || strategy.contains("LEAPS", true) || strategy == "Vertical" || strategy == "Diagonal") 1 else 0,
                        is_buy = if (strategy == "CSP") 0 else 1,
                        exit_price = if (isClosed) exitPrice.toDoubleOrNull() else null,
                        exit_date = if (isClosed) exitDate else null
                    )
                    onSave(trade)
                },
                enabled = isValid
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = { onDismiss() }) { Text("Cancel") }
        }
    )
}

// ==========================================
// NOTIFICATIONS SCREEN
// ==========================================
@Composable
fun NotificationsScreen() {
    val context = LocalContext.current
    val notifications = remember { NotificationCache.load(context) }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.Notifications,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Notification History", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.height(12.dp))

        if (notifications.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Notifications, contentDescription = null, modifier = Modifier.size(48.dp), tint = Color.Gray)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("No notifications yet", style = MaterialTheme.typography.titleMedium, color = Color.Gray)
                    Text(
                        "Daily recommendations will appear here at 6:50 AM on market days.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 8.dp)
                    )
                }
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(notifications) { notification ->
                    NotificationCard(notification)
                }
            }
        }
    }
}

@Composable
fun NotificationCard(notification: NotificationRecord) {
    val dateFormat = remember { java.text.SimpleDateFormat("MMM dd, yyyy 'at' h:mm a", java.util.Locale.getDefault()) }
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(notification.title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                dateFormat.format(java.util.Date(notification.timestamp)),
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(notification.body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
