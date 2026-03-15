package com.example.financestreamai

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AlphaStreamDashboard(apiService)
                }
            }
        }
    }
}

// 1. Define what the response from Python looks like
data class StockResult(
    @SerializedName("ticker") val ticker: String,
    @SerializedName(value = "price", alternate = ["current_price", "last_price", "price_at_scan", "last", "price_used"]) val price: Double,
    @SerializedName(value = "high52", alternate = ["52w_high", "high_52", "high_52w", "52_week_high"]) val high52: Double,
    @SerializedName(value = "discount_price", alternate = ["target_price", "discount"]) val discount_price: Double,
    @SerializedName(value = "lower_bb", alternate = ["lower_band", "bb_lower"]) val lower_bb: Double,
    @SerializedName("rsi") val rsi: Double,
    @SerializedName(value = "sma200", alternate = ["sma_200", "200_sma", "sma_200_day"]) val sma200: Double,
    @SerializedName(value = "iv", alternate = ["implied_volatility", "iv_percent"]) val iv: Double,
    @SerializedName("delta") val delta: Double,
    @SerializedName("match") val match: Boolean,
    @SerializedName("buy_distance_usd") val buyDistanceUsd: Double,
    @SerializedName("buy_distance_pct") val buyDistancePct: Double
)

// 2. Define the API "Contract"
interface AlphaStreamApi {
    @GET("check_multiple")
    suspend fun checkMultipleStocks(@Query("tickers") tickers: String): List<StockResult>
}

// 3. Create the Retrofit Instance with increased timeouts and logging
val logging = HttpLoggingInterceptor { message ->
    Log.d("AlphaStreamRaw", message)
}.apply {
    level = HttpLoggingInterceptor.Level.BODY
}

val okHttpClient = OkHttpClient.Builder()
    .addInterceptor(logging)
    .connectTimeout(60, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .writeTimeout(60, TimeUnit.SECONDS)
    .build()

val retrofit = Retrofit.Builder()
    .baseUrl("https://financestreamai-backend.onrender.com/")
    .client(okHttpClient)
    .addConverterFactory(GsonConverterFactory.create())
    .build()

val apiService = retrofit.create(AlphaStreamApi::class.java)

// Portfolio Constants
val PORTFOLIO_WATCHLIST_DEFAULT = listOf(
    "AMZN", "AVGO", "TSM", "NVDA", "CRDO", "ALAB", "CRWV", "IREN", "HOOD", 
    "SOFI", "SITM", "RDDT", "APP", "MELI", "AXON", "TSLA", "BE", "SNDK", 
    "MU", "ZS", "SHOP", "PLTR", "COIN", "MSTR", "VRT", "NBIS", "ANET", 
    "META", "PANW", "GOOG", "SNOW", "CRWD", "ADBE", "AMAT", "TTD", "ASTS", 
    "ONON", "SE"
)

data class ActivePosition(
    val ticker: String,
    val contracts: Int,
    val strike: Double,
    val entryPrice: Double,
    val premiumPerContract: Double
) {
    val totalPremium: Double = premiumPerContract * 100 * contracts
    val collateral: Double = strike * 100 * contracts
    val yield: Double = (totalPremium / collateral) * 100
}

val ACTIVE_TRACKER = listOf(
    ActivePosition("AMZN", 1, 190.0, 7.35, 7.35),
    ActivePosition("AVGO", 1, 300.0, 15.8, 15.8),
    ActivePosition("TSM", 1, 300.0, 10.25, 10.25),
    ActivePosition("NVDA", 1, 170.0, 8.00, 8.00),
    ActivePosition("ALAB", 1, 105.0, 10.5, 10.5),
    ActivePosition("CRWV", 1, 70.0, 7.25, 7.25),
    ActivePosition("HOOD", 2, 65.0, 5.05, 5.05),
    ActivePosition("IREN", 4, 35.0, 3.95, 3.95),
    ActivePosition("RDDT", 1, 115.0, 9.65, 9.65),
    ActivePosition("SITM", 1, 280.0, 26.1, 26.1)
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AlphaStreamDashboard(apiService: AlphaStreamApi) {
    val context = LocalContext.current
    val sharedPrefs = remember { context.getSharedPreferences("AlphaStreamPrefs", Context.MODE_PRIVATE) }
    
    var tickerInput by remember { mutableStateOf("") }
    
    // Load persisted watchlist or use default
    var watchlist by remember { 
        val saved = sharedPrefs.getString("watchlist", null)
        val initialList = saved?.split(",")?.filter { it.isNotBlank() } ?: PORTFOLIO_WATCHLIST_DEFAULT
        mutableStateOf(initialList) 
    }
    
    var stockResults by remember { mutableStateOf<List<StockResult>>(emptyList()) }
    var allStockData by remember { mutableStateOf<Map<String, StockResult>>(emptyMap()) }
    var isLoading by remember { mutableStateOf(false) }
    var showActiveTracker by remember { mutableStateOf(false) }
    var isEditingWatchlist by remember { mutableStateOf(false) }
    
    val monthlyGoal = 5000f
    val currentRealizedProfit = ACTIVE_TRACKER.sumOf { it.totalPremium }.toFloat()

    val strategies = listOf("Sell Puts", "Covered Calls")
    var expanded by remember { mutableStateOf(false) }
    var selectedStrategy by remember { mutableStateOf(strategies[0]) }

    val scope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    BackHandler(enabled = showActiveTracker) {
        showActiveTracker = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(if (showActiveTracker) "Active Tracker" else "JP/Anish Portfolio") 
                },
                navigationIcon = {
                    if (showActiveTracker) {
                        IconButton(onClick = { showActiveTracker = false }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(modifier = Modifier.padding(paddingValues).padding(horizontal = 16.dp)) {
            
            if (!showActiveTracker) {
                Surface(
                    shape = ButtonDefaults.shape,
                    color = if (isLoading) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp)
                        .combinedClickable(
                            enabled = !isLoading,
                            onClick = {
                                scope.launch {
                                    try {
                                        isLoading = true
                                        stockResults = emptyList()
                                        val results = apiService.checkMultipleStocks(watchlist.joinToString(","))
                                        Log.d("AlphaStream", "Watchlist Scan Results: $results")
                                        allStockData = results.associateBy { it.ticker }
                                        stockResults = results.filter { it.match }
                                            .sortedByDescending { it.iv }
                                            .take(5)
                                        if (stockResults.isEmpty()) {
                                            Toast.makeText(context, "No matches found.", Toast.LENGTH_SHORT).show()
                                        }
                                    } catch (e: Exception) {
                                        Log.e("AlphaStream", "Portfolio Scan Error: ${e.message}")
                                        Toast.makeText(context, "Connection Error: Backend timeout.", Toast.LENGTH_LONG).show()
                                    } finally {
                                        isLoading = false
                                    }
                                }
                            },
                            onLongClick = {
                                tickerInput = watchlist.joinToString(", ")
                                isEditingWatchlist = true
                                stockResults = emptyList()
                                Toast.makeText(context, "Editing watchlist", Toast.LENGTH_SHORT).show()
                            }
                        )
                ) {
                    Row(
                        modifier = Modifier.padding(ButtonDefaults.ContentPadding),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Scan WatchList")
                    }
                }

                OutlinedButton(
                    onClick = { 
                        showActiveTracker = true 
                        stockResults = emptyList()
                    },
                    modifier = Modifier.wrapContentSize().padding(vertical = 4.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Icon(Icons.Filled.List, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("View Active Tracker", style = MaterialTheme.typography.labelMedium)
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = !expanded },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                ) {
                    OutlinedTextField(
                        value = selectedStrategy,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Trading Strategy") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )

                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        strategies.forEach { strategy ->
                            DropdownMenuItem(
                                text = { Text(strategy) },
                                onClick = {
                                    selectedStrategy = strategy
                                    expanded = false
                                    stockResults = emptyList()
                                }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = tickerInput,
                    onValueChange = { 
                        tickerInput = it
                        if (it.isBlank()) isEditingWatchlist = false
                    },
                    label = { Text("Manual Symbol Entry") },
                    placeholder = { 
                        Text(
                            text = "AAPL, MSFT...", 
                            color = Color.Gray.copy(alpha = 0.6f)
                        ) 
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Button(
                    onClick = {
                        keyboardController?.hide()
                        focusManager.clearFocus()
                        if (isEditingWatchlist) {
                            val newTickers = tickerInput.split(Regex("[,\\s]+"))
                                .filter { it.isNotBlank() }
                                .map { it.uppercase().trim() }
                            if (newTickers.isNotEmpty()) {
                                watchlist = newTickers
                                // Persist updated watchlist
                                sharedPrefs.edit().putString("watchlist", newTickers.joinToString(",")).apply()
                                
                                isEditingWatchlist = false
                                tickerInput = ""
                                stockResults = emptyList()
                                Toast.makeText(context, "Watchlist updated", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            val tickers = tickerInput.split(Regex("[,\\s]+"))
                                .filter { it.isNotBlank() }
                                .map { it.uppercase().trim() }
                            if (tickers.isNotEmpty()) {
                                scope.launch {
                                    try {
                                        isLoading = true
                                        stockResults = emptyList()
                                        val results = apiService.checkMultipleStocks(tickers.joinToString(","))
                                        Log.d("AlphaStream", "Manual Scan Results: $results")
                                        allStockData = allStockData + results.associateBy { it.ticker }
                                        stockResults = results.sortedByDescending { it.iv }
                                        if (stockResults.isEmpty()) {
                                            Toast.makeText(context, "No data found.", Toast.LENGTH_SHORT).show()
                                        }
                                    } catch (e: Exception) {
                                        Log.e("AlphaStream", "API Error: ${e.message}")
                                        Toast.makeText(context, "API Error: Check backend", Toast.LENGTH_LONG).show()
                                    } finally {
                                        isLoading = false
                                    }
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    enabled = !isLoading && tickerInput.isNotBlank()
                ) {
                    if (isLoading) CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.White)
                    else Text(if (isEditingWatchlist) "Update WatchList" else "Run Manual Scan")
                }

                Spacer(modifier = Modifier.height(16.dp))
                
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    if (stockResults.isNotEmpty()) {
                        item {
                            Text("Results:", style = MaterialTheme.typography.titleMedium, color = Color(0xFF2E7D32), modifier = Modifier.padding(bottom = 8.dp))
                        }
                        items(stockResults) { result ->
                            StockResultCard(result)
                        }
                    } else if (!isLoading) {
                        item {
                            Box(modifier = Modifier.fillMaxWidth().padding(top = 20.dp), contentAlignment = Alignment.Center) {
                                Text("Ready for scan", color = Color.Gray)
                            }
                        }
                    }
                }
            } else {
                Text("Monthly Goal Progress: $${currentRealizedProfit.toInt()} / $${monthlyGoal.toInt()}", style = MaterialTheme.typography.labelMedium)
                LinearProgressIndicator(
                    progress = { (currentRealizedProfit / monthlyGoal).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    color = Color(0xFF2E7D32),
                    trackColor = Color(0xFFE8F5E9)
                )
                
                Text("May 15 Expiration Status", style = MaterialTheme.typography.labelMedium, color = Color.Gray)
                Spacer(modifier = Modifier.height(8.dp))
                
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(ACTIVE_TRACKER) { position ->
                        val currentStockInfo = allStockData[position.ticker]
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Column {
                                        Text(position.ticker, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                                        Text("${position.contracts} contracts @ $${position.strike} Strike", style = MaterialTheme.typography.bodyMedium)
                                    }
                                    Badge(containerColor = Color(0xFFE8F5E9)) {
                                        Text("Active", color = Color(0xFF2E7D32), modifier = Modifier.padding(4.dp))
                                    }
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                HorizontalDivider(thickness = 0.5.dp, color = Color.LightGray)
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Column {
                                        Text("Transaction Price", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                                        Text("$${position.premiumPerContract}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text("Current Stock Price", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                                        Text(
                                            if (currentStockInfo != null) "$${currentStockInfo.price}" else "Run Scan to see",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium,
                                            color = if (currentStockInfo != null) MaterialTheme.colorScheme.primary else Color.Gray
                                        )
                                    }
                                }
                            }
                        }
                    }
                    item {
                        Button(
                            onClick = { showActiveTracker = false },
                            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                        ) {
                            Text("Close Tracker")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StockResultCard(result: StockResult) {
    var expanded by remember { mutableStateOf(false) }
    
    val estPremium60 = result.price * (result.iv / 100.0) * 0.45 
    val monthlyReturn = if (result.price > 0) ((estPremium60 / 2) / (result.price * 0.90)) * 100 else 0.0

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(
            containerColor = if (result.match) Color(0xFFE8F5E9) else Color(0xFFF5F5F5)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text(result.ticker, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                    Text("Price: $${result.price}", style = MaterialTheme.typography.bodyMedium)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (result.match && result.rsi < 40 && result.price > result.sma200) {
                            Badge(containerColor = Color(0xFF2E7D32), modifier = Modifier.padding(end = 4.dp)) {
                                Text("HIGH MATCH", color = Color.White, modifier = Modifier.padding(2.dp))
                            }
                        }
                        Text(if (result.match) "MATCH" else "NO MATCH", 
                            fontWeight = FontWeight.Bold, 
                            color = if (result.match) Color(0xFF2E7D32) else Color.Gray)
                    }
                    Text("IV: ${result.iv}%", style = MaterialTheme.typography.bodySmall)
                }
            }

            if (expanded) {
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(12.dp))

                val dataPoints = listOf(
                    "Est. Monthly Return" to "%.2f%%".format(monthlyReturn),
                    "200-Day SMA" to "$${result.sma200}",
                    "Delta" to "${result.delta}",
                    "IV" to "${result.iv}%",
                    "Lower BB" to "$${result.lower_bb}",
                    "RSI (14)" to "${result.rsi}",
                    "52w High" to "$${result.high52}",
                    "15%% Target Price" to "$${result.discount_price}",
                    "Buy Distance ($)" to "$${result.buyDistanceUsd}",
                    "Buy Distance (%%)" to "%.2f%%".format(result.buyDistancePct)
                )

                dataPoints.forEach { (label, value) ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(label, style = MaterialTheme.typography.bodyMedium, color = Color.DarkGray)
                        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}
