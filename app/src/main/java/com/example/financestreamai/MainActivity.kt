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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
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
    @SerializedName(value = "bt", alternate = ["bt_success"]) val bt: String?
)

data class CspResult(
    @SerializedName("strike") val strike: Double,
    @SerializedName("premium") val premium: Double,
    @SerializedName("delta") val delta: Double,
    @SerializedName(value = "bt", alternate = ["bt_success"]) val bt: String?,
    @SerializedName(value = "roc", alternate = ["monthly_roc"]) val roc: String?,
    @SerializedName("expiry") val expiry: String? = null
)

data class DiagonalResult(
    @SerializedName(value = "long", alternate = ["long_strike", "long_leg"]) val longLeg: String?,
    @SerializedName(value = "short", alternate = ["short_strike", "short_leg"]) val shortLeg: String?,
    @SerializedName(value = "net_debt", alternate = ["net_debit", "debit"]) val netDebt: Double,
    @SerializedName(value = "yield", alternate = ["yield_ratio"]) val yieldRatio: String?,
    @SerializedName(value = "bt", alternate = ["bt_success"]) val bt: String?,
    @SerializedName("expiry") val expiry: String? = null
)

data class VerticalResult(
    @SerializedName(value = "strikes", alternate = ["strike"]) val strikes: String?,
    @SerializedName(value = "net_debit", alternate = ["net_debt", "debit"]) val netDebit: Double,
    @SerializedName(value = "bt", alternate = ["bt_success"]) val bt: String?,
    @SerializedName("expiry") val expiry: String? = null
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
    @SerializedName("rsi") val rsi: Double?,
    @SerializedName("beta") val beta: Double?,
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
    @SerializedName("warnings") val warnings: List<String>? = null
)

// ==========================================
// 2. RETROFIT API INTERFACE
// ==========================================
interface JPFinanceApi {
    @GET("scan")
    suspend fun getScanResults(
        @Query("tickers") tickers: String? = null,
        @Query("strategy") strategy: String? = null,
        @Query("target_delta") targetDelta: Double? = null,
        @Query("min_roc") minRoc: Double? = null
    ): List<ScanResultItem>

    @GET("scan/trending")
    suspend fun scanTrending(): List<ScanResultItem>

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

    // Future endpoint for saving tuned parameters
    @POST("settings/update")
    suspend fun updateSettings(@Body settings: Map<String, String>): Map<String, String>

    @POST("backtest")
    suspend fun getBacktest(@Body request: BacktestRequest): BacktestResponse
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

// Render backend URL. Ensure it ends with a trailing slash.
val retrofit: Retrofit = Retrofit.Builder()
    .baseUrl("https://financestreamai-backend.onrender.com/api/v1/")
    .client(OkHttpClient.Builder()
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
// 3. MAIN ACTIVITY & UI
// ==========================================
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scheduleDailyRecommendations()
        // Pre-warm: wake up Render backend so it's ready when user scans
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            try { apiService.getHealth() } catch (_: Exception) { }
        }
        val startTab = if (intent?.getStringExtra("navigate_to") == "notifications") 3 else 0
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MainScreen(startTab = startTab)
                }
            }
        }
    }

    private fun scheduleDailyRecommendations() {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 9)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            // If 9am already passed today, schedule for tomorrow
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
            ExistingPeriodicWorkPolicy.KEEP,
            dailyWork
        )

        Log.d("MainActivity", "Daily recommendations scheduled. Initial delay: ${initialDelayMs / 1000 / 60} min")
    }
}

@Composable
fun MainScreen(startTab: Int = 0) {
    var selectedTab by remember { mutableIntStateOf(startTab) }
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    // Keep-alive: ping backend every 5 minutes to prevent Render from sleeping
    LaunchedEffect(Unit) {
        while (true) {
            delay(5 * 60 * 1000L)
            try { withContext(Dispatchers.IO) { apiService.getHealth() } } catch (_: Exception) { }
        }
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
            NavigationBar(tonalElevation = 4.dp) {
                val scanColor = Color(0xFF4338CA) // Indigo
                val portfolioColor = Color(0xFF059669) // Emerald
                val guruColor = Color(0xFF7C3AED) // Purple
                val alertColor = Color(0xFFD97706) // Amber

                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Search, contentDescription = null, tint = scanColor) },
                    label = { Text("Scan", color = scanColor) },
                    colors = androidx.compose.material3.NavigationBarItemDefaults.colors(
                        selectedIconColor = scanColor,
                        selectedTextColor = scanColor,
                        indicatorColor = Color(0xFFE0E7FF) // Indigo-100
                    )
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.AccountBalance, contentDescription = null, tint = portfolioColor) },
                    label = { Text("Portfolio", color = portfolioColor, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp) },
                    colors = androidx.compose.material3.NavigationBarItemDefaults.colors(
                        selectedIconColor = portfolioColor,
                        selectedTextColor = portfolioColor,
                        indicatorColor = Color(0xFFD1FAE5) // Emerald-100
                    )
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.TipsAndUpdates, contentDescription = null, tint = guruColor) },
                    label = { Text("AI Guru", color = guruColor) },
                    colors = androidx.compose.material3.NavigationBarItemDefaults.colors(
                        selectedIconColor = guruColor,
                        selectedTextColor = guruColor,
                        indicatorColor = Color(0xFFEDE9FE) // Purple-100
                    )
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    icon = { Icon(Icons.Default.Notifications, contentDescription = null, tint = alertColor) },
                    label = { Text("Alerts", color = alertColor) },
                    colors = androidx.compose.material3.NavigationBarItemDefaults.colors(
                        selectedIconColor = alertColor,
                        selectedTextColor = alertColor,
                        indicatorColor = Color(0xFFFEF3C7) // Amber-100
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

    // Persisted Watchlist State
    var watchlist by remember {
        val saved = sharedPrefs.getString("watchlist", null)
        val list = saved?.split(",")?.filter { it.isNotBlank() } ?: MASTER_WATCHLIST_DEFAULT
        mutableStateOf(list)
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
            IconButton(onClick = { showTunerDialog = true }) {
                Icon(Icons.Default.Settings, contentDescription = "Tune Strategy")
            }
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
            enabled = !isLoading && manualTicker.isNotBlank(),
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

        // Scan Watchlist Button
        Button(
            onClick = {
                keyboardController?.hide()
                scope.launch {
                    try {
                        isLoading = true
                        scanResults = emptyList()
                        scanError = null
                        scanProgress = "Connecting to server..."

                        val strategyParam = when (selectedStrategy) {
                            "CSPs" -> "csp"
                            "Diagonals" -> "diagonal"
                            "Verticals" -> "vertical"
                            "Long LEAPS" -> "long_leaps"
                            else -> null
                        }
                        val deltaParam = targetDelta.toDoubleOrNull()
                        val rocParam = minRoc.toDoubleOrNull()

                        val batches = watchlist.chunked(5)
                        val combinedResults = mutableListOf<ScanResultItem>()
                        var failedBatches = 0

                        for ((index, batch) in batches.withIndex()) {
                            val batchString = batch.joinToString(",")
                            scanProgress = "Batch ${index + 1} of ${batches.size} (${batch.size} symbols)..."
                            try {
                                val batchResults = apiService.getScanResults(
                                    tickers = batchString,
                                    strategy = strategyParam,
                                    targetDelta = deltaParam,
                                    minRoc = rocParam
                                )
                                combinedResults.addAll(batchResults)
                            } catch (e: Exception) {
                                failedBatches++
                                Log.e("SCAN_LOGIC", "Batch ${index + 1} failed: ${e.message}")
                            }
                        }
                        scanResults = combinedResults

                        if (failedBatches > 0 && combinedResults.isNotEmpty()) {
                            scanError = "$failedBatches of ${batches.size} batches failed. Showing partial results."
                        } else if (failedBatches > 0 && combinedResults.isEmpty()) {
                            scanError = "All batches failed. The server may be slow — please try again."
                        } else if (combinedResults.isEmpty()) {
                            scanError = "No opportunities found. Try adjusting tuner parameters or your watchlist."
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
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669))
        ) {
            if (isLoading && scanProgress.contains("Batch")) {
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
            Icon(Icons.Default.TrendingUp, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Scan Trending Stocks", style = MaterialTheme.typography.labelLarge)
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
                    ScanResultCard(item, selectedStrategy, scope, context)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScanResultCard(item: ScanResultItem, strategyFilter: String, scope: kotlinx.coroutines.CoroutineScope, context: android.content.Context) {
    val hasStrategies = !item.csps.isNullOrEmpty() || !item.diagonals.isNullOrEmpty() ||
            !item.verticals.isNullOrEmpty() || !item.longLeaps.isNullOrEmpty()

    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Header: Ticker + Price
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(text = item.ticker, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
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
                    // Bullish / Bearish signals
                    if (!item.bullishSignals.isNullOrEmpty() || !item.bearishSignals.isNullOrEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            item.bullishSignals?.forEach { signal ->
                                Text("▲ ${abbreviateSignal(signal)}", style = MaterialTheme.typography.bodySmall, color = Color(0xFF2E7D32))
                            }
                            item.bearishSignals?.forEach { signal ->
                                Text("▼ ${abbreviateSignal(signal)}", style = MaterialTheme.typography.bodySmall, color = Color(0xFFC62828))
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
                                Spacer(modifier = Modifier.height(4.dp))

                                // Deduplicate: collect unique values for downside and upside
                                val stopVal = item.levels.stopLoss
                                val supportVal = item.levels.support
                                val swingLowVal = item.levels.swingLow60d
                                val targetVal = item.levels.target
                                val resistVal = item.levels.resistance
                                val swingHighVal = item.levels.swingHigh60d

                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    // Left: downside levels (deduplicated)
                                    Column {
                                        if (stopVal != null) {
                                            Text("🛑 Stop $${"%.2f".format(stopVal)}", style = MaterialTheme.typography.bodySmall, color = Color(0xFFC62828), fontWeight = FontWeight.Bold)
                                        }
                                        if (supportVal != null && (stopVal == null || "%.2f".format(supportVal) != "%.2f".format(stopVal))) {
                                            Text("Support $${"%.2f".format(supportVal)}", style = MaterialTheme.typography.bodySmall, color = Color(0xFFEF6C00))
                                        }
                                        if (swingLowVal != null && (stopVal == null || "%.2f".format(swingLowVal) != "%.2f".format(stopVal)) && (supportVal == null || "%.2f".format(swingLowVal) != "%.2f".format(supportVal))) {
                                            Text("60d Low $${"%.2f".format(swingLowVal)}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                                        }
                                    }
                                    // Right: upside levels (deduplicated)
                                    Column(horizontalAlignment = Alignment.End) {
                                        if (targetVal != null) {
                                            Text("🎯 Target $${"%.2f".format(targetVal)}", style = MaterialTheme.typography.bodySmall, color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold)
                                        }
                                        if (resistVal != null && (targetVal == null || "%.2f".format(resistVal) != "%.2f".format(targetVal))) {
                                            Text("Resist $${"%.2f".format(resistVal)}", style = MaterialTheme.typography.bodySmall, color = Color(0xFF1565C0))
                                        }
                                        if (swingHighVal != null && (targetVal == null || "%.2f".format(swingHighVal) != "%.2f".format(targetVal)) && (resistVal == null || "%.2f".format(swingHighVal) != "%.2f".format(resistVal))) {
                                            Text("60d High $${"%.2f".format(swingHighVal)}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                                        }
                                    }
                                }
                                // R:R, ATR, 52W High chips
                                val hasChips = item.levels.riskReward != null || item.levels.atr != null || item.levels.high52w != null
                                if (hasChips) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        if (item.levels.riskReward != null) {
                                            val rrColor = if (item.levels.riskReward >= 2.0) Color(0xFF2E7D32) else if (item.levels.riskReward >= 1.0) Color(0xFFEF6C00) else Color(0xFFC62828)
                                            Card(colors = CardDefaults.cardColors(containerColor = rrColor.copy(alpha = 0.12f)), shape = RoundedCornerShape(6.dp)) {
                                                Text("R:R ${"%.1f".format(item.levels.riskReward)}", modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = rrColor)
                                            }
                                        }
                                        if (item.levels.atr != null) {
                                            Card(colors = CardDefaults.cardColors(containerColor = Color.Gray.copy(alpha = 0.10f)), shape = RoundedCornerShape(6.dp)) {
                                                Text("ATR $${"%.2f".format(item.levels.atr)}", modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall)
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

            // CSP Results (Ordered by ROC desc, limit 10)
            if (strategyFilter == "All" || strategyFilter == "CSPs") {
                val sortedCsps = item.csps?.sortedByDescending {
                    it.roc.parseToDouble()
                }?.take(10)

                sortedCsps?.forEach { csp ->
                    val expiryInfo = if (csp.expiry != null) " | Exp: ${csp.expiry.formatDate()}" else ""
                    OpportunityRow(
                        title = "CSP Strike ${csp.strike}",
                        subtitle = "Prem: $${csp.premium} | Delta: ${csp.delta} | ROC: ${csp.roc ?: "N/A"}$expiryInfo",
                        bt = csp.bt ?: "N/A",
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

            // Diagonal Results (Ordered by Yield desc, limit 10)
            if (strategyFilter == "All" || strategyFilter == "Diagonals") {
                val sortedDiagonals = item.diagonals?.sortedByDescending {
                    it.yieldRatio.parseToDouble()
                }?.take(10)

                sortedDiagonals?.forEach { diag ->
                    val expiryInfo = if (diag.expiry != null) " | Exp: ${diag.expiry.formatDate()}" else ""
                    OpportunityRow(
                        title = "Diagonal: BUY ${diag.longLeg.formatDate()} / SELL ${diag.shortLeg.formatDate()}",
                        subtitle = "Net Debit: $${diag.netDebt} | Yield: ${diag.yieldRatio ?: "N/A"}$expiryInfo",
                        bt = diag.bt ?: "N/A",
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
                }?.sortedByDescending { it.second ?: -1.0 }?.take(10)

                sortedVerticals?.forEach { (vert, yieldPct) ->
                    val yieldStr = if (yieldPct != null) "%.1f%%".format(yieldPct) else "N/A"
                    val expiryInfo = if (vert.expiry != null) " | Exp: ${vert.expiry.formatDate()}" else ""
                    OpportunityRow(
                        title = "Vertical: ${vert.strikes ?: "N/A"}",
                        subtitle = "Net Debit: $${vert.netDebit} | Yield: $yieldStr$expiryInfo",
                        bt = vert.bt ?: "N/A",
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
                item.longLeaps?.take(10)?.forEach { leaps ->
                    OpportunityRow(
                        title = "Long LEAPS: ${leaps.expiry.formatDate()} $${leaps.strike}C",
                        subtitle = "Prem: $${leaps.premium} | Lev: ${leaps.leverage ?: "N/A"} | Buffer: ${leaps.intrinsicBuffer ?: "N/A"}",
                        bt = leaps.bt ?: "N/A",
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
fun OpportunityRow(title: String, subtitle: String, bt: String, onAdd: () -> Unit) {
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

    val isSpread = selectedType == "Vertical" || selectedType == "Diagonal"
    val isDiagonal = selectedType == "Diagonal"

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
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (isSpread) {
                item {
                    OutlinedTextField(
                        value = strikeSell,
                        onValueChange = { strikeSell = it },
                        label = { Text("Sell Leg Strike") },
                        placeholder = { Text("e.g. 250") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            item {
                OutlinedTextField(
                    value = expiry,
                    onValueChange = { expiry = it },
                    label = { Text(if (isDiagonal) "Buy Leg Expiry (YYYY-MM-DD)" else "Expiry (YYYY-MM-DD)") },
                    placeholder = { Text("e.g. 2026-06-18") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (isDiagonal) {
                item {
                    OutlinedTextField(
                        value = expirySell,
                        onValueChange = { expirySell = it },
                        label = { Text("Sell Leg Expiry (YYYY-MM-DD)") },
                        placeholder = { Text("e.g. 2026-05-16") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            item {
                OutlinedTextField(
                    value = premium,
                    onValueChange = { premium = it },
                    label = { Text(if (isSpread) "Net Debit" else "Premium") },
                    placeholder = { Text("e.g. 5.00") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Ask AI Guru Button
            item {
                val strategyKey = when (selectedType) {
                    "CSP" -> "csp"
                    "Sell Call" -> "sell_call"
                    "Vertical" -> "vertical"
                    "Diagonal" -> "diagonal"
                    "Long LEAPS" -> "long_leaps"
                    else -> "csp"
                }
                val action = when (selectedType) {
                    "CSP", "Sell Call" -> "sell"
                    else -> "buy"
                }
                Button(
                    onClick = {
                        keyboardController?.hide()
                        isLoading = true
                        errorMessage = null
                        response = null
                        scope.launch {
                            try {
                                val request = BacktestRequest(
                                    ticker = ticker,
                                    strategy = strategyKey,
                                    action = action,
                                    strike = strike.toDoubleOrNull(),
                                    strike_sell = strikeSell.toDoubleOrNull(),
                                    expiry = expiry.ifBlank { null },
                                    expiry_sell = if (isDiagonal) expirySell.ifBlank { null } else null,
                                    premium = premium.toDoubleOrNull()
                                )
                                response = withContext(Dispatchers.IO) {
                                    apiService.getBacktest(request)
                                }
                            } catch (e: Exception) {
                                errorMessage = friendlyErrorMessage(e)
                            } finally {
                                isLoading = false
                            }
                        }
                    },
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
                item { BacktestResultCard(response!!) }
            }
        }
    }
}

@Composable
fun BacktestResultCard(res: BacktestResponse) {
    val verdictColor = when (res.verdict.uppercase()) {
        "BUY" -> Color(0xFF2E7D32)
        "SELL" -> Color(0xFFC62828)
        "HOLD" -> Color(0xFFEF6C00)
        else -> Color(0xFF757575) // AVOID, N/A
    }
    val confidenceColor = when (res.confidence) {
        "High" -> Color(0xFF2E7D32)
        "Medium" -> Color(0xFFEF6C00)
        else -> Color(0xFFC62828)
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
                    res.verdict.uppercase(),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Black,
                    color = verdictColor
                )
                Spacer(modifier = Modifier.height(4.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = confidenceColor.copy(alpha = 0.15f)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        "${res.confidence} Confidence",
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
                        "Daily recommendations will appear here at 9 AM on market days.",
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
