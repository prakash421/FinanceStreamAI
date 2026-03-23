package com.example.financestreamai

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.unit.dp
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import java.util.concurrent.TimeUnit

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
    @SerializedName(value = "roc", alternate = ["monthly_roc"]) val roc: String?
)

data class DiagonalResult(
    @SerializedName(value = "long", alternate = ["long_strike", "long_leg"]) val longLeg: String?,
    @SerializedName(value = "short", alternate = ["short_strike", "short_leg"]) val shortLeg: String?,
    @SerializedName(value = "net_debt", alternate = ["net_debit", "debit"]) val netDebt: Double,
    @SerializedName(value = "yield", alternate = ["yield_ratio"]) val yieldRatio: String?,
    @SerializedName(value = "bt", alternate = ["bt_success"]) val bt: String?
)

data class VerticalResult(
    @SerializedName(value = "strikes", alternate = ["strike"]) val strikes: String?,
    @SerializedName(value = "net_debit", alternate = ["net_debt", "debit"]) val netDebit: Double,
    @SerializedName(value = "bt", alternate = ["bt_success"]) val bt: String?
)

data class ScanResultItem(
    @SerializedName("ticker") val ticker: String,
    @SerializedName("price") val price: Double,
    @SerializedName("rsi") val rsi: Double?,
    @SerializedName("beta") val beta: Double?,
    @SerializedName(value = "csps", alternate = ["csp", "csp_results"]) val csps: List<CspResult>?,
    @SerializedName(value = "diagonals", alternate = ["diagonal", "diagonal_results"]) val diagonals: List<DiagonalResult>?,
    @SerializedName(value = "verticals", alternate = ["vertical", "vertical_results"]) val verticals: List<VerticalResult>?,
    @SerializedName(value = "long_leaps", alternate = ["long_leaps_results", "leaps"]) val longLeaps: List<LongLeapsResult>?
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

    @GET("health")
    suspend fun getHealth(): HealthResponse

    @POST("portfolio/add")
    suspend fun addPosition(@Body trade: TradeEntry): Map<String, Any>

    @DELETE("portfolio/remove/{id}")
    suspend fun removePosition(@Path("id") id: Int): Map<String, String>

    @POST("portfolio/close/{id}")
    suspend fun closePosition(@Path("id") id: Int, @Body exitDetails: Map<String, String>): Map<String, String>

    // Future endpoint for saving tuned parameters
    @POST("settings/update")
    suspend fun updateSettings(@Body settings: Map<String, String>): Map<String, String>
}

// Render backend URL. Ensure it ends with a trailing slash.
val retrofit: Retrofit = Retrofit.Builder()
    .baseUrl("https://financestreamai-backend.onrender.com/api/v1/")
    .client(OkHttpClient.Builder()
        .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BODY })
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    )
    .addConverterFactory(GsonConverterFactory.create())
    .build()

val apiService: JPFinanceApi = retrofit.create(JPFinanceApi::class.java)

// Watchlist Defaults
val MASTER_WATCHLIST_DEFAULT = listOf("ALAB", "PLTR", "CRWD", "SNOW", "TSLA", "NFLX", "ARM", "MSFT", "META", "NVDA", "MSTR", "SMCI", "APP", "SHOP", "AVGO", "SITM", "HOOD", "CRWV", "IREN", "RDDT", "AMZN", "TSM", "UBER", "COIN", "SNDK", "MU", "WDC", "BE", "NOW", "CRM", "ADBE", "VRT", "TEAM", "NBIS", "CRDO")

// Helper to parse numeric values from strings like "5.4%" or "10.2"
private fun String?.parseToDouble(): Double {
    if (this == null) return 0.0
    return try {
        // Regex to find the first decimal number in the string
        val regex = """-?\d+(\.\d+)?""".toRegex()
        val match = regex.find(this)
        match?.value?.toDoubleOrNull() ?: 0.0
    } catch (e: Exception) {
        0.0
    }
}

// ==========================================
// 3. MAIN ACTIVITY & UI
// ==========================================
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MainScreen()
                }
            }
        }
    }
}

@Composable
fun MainScreen() {
    var selectedTab by remember { mutableIntStateOf(0) }
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    Column(modifier = Modifier.fillMaxSize().clickable {
        keyboardController?.hide()
        focusManager.clearFocus()
    }) {
        TabRow(selectedTabIndex = selectedTab) {
            Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Market Scan") })
            Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("Portfolio Health") })
        }

        when (selectedTab) {
            0 -> ScanScreen()
            1 -> PortfolioScreen()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ScanScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current
    val sharedPrefs = remember { context.getSharedPreferences("FinanceStreamPrefs", Context.MODE_PRIVATE) }

    var isLoading by remember { mutableStateOf(false) }
    var scanResults by remember { mutableStateOf<List<ScanResultItem>>(emptyList()) }
    var manualTicker by remember { mutableStateOf("") }

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

    Column(modifier = Modifier.padding(16.dp)) {
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
                    label = { Text("Strategy Filter") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedDropdown) },
                    modifier = Modifier.menuAnchor().fillMaxWidth()
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
            IconButton(onClick = { showTunerDialog = true }) {
                Icon(Icons.Default.Settings, contentDescription = "Tune Strategy")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Manual Search Bar
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = manualTicker,
                onValueChange = { manualTicker = it.uppercase() },
                label = { Text("Manual Ticker Check") },
                modifier = Modifier.weight(1f),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboardController?.hide() })
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Scan Action Button with Long Click
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = ButtonDefaults.shape,
            color = if (isLoading) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        ) {
            Row(
                modifier = Modifier
                    .combinedClickable(
                        enabled = !isLoading,
                        onClick = {
                            keyboardController?.hide()
                            scope.launch {
                                try {
                                    isLoading = true
                                    scanResults = emptyList() // Clear old results

                                    val strategyParam = when (selectedStrategy) {
                                        "CSPs" -> "csp"
                                        "Diagonals" -> "diagonal"
                                        "Verticals" -> "vertical"
                                        "Long LEAPS" -> "long_leaps"
                                        else -> null
                                    }
                                    val deltaParam = targetDelta.toDoubleOrNull()
                                    val rocParam = minRoc.toDoubleOrNull()

                                    Log.d("SCAN_LOGIC", "Starting scan with strategy=$strategyParam, delta=$deltaParam, roc=$rocParam")

                                    if (manualTicker.isNotBlank()) {
                                        val results = apiService.getScanResults(
                                            tickers = manualTicker,
                                            strategy = strategyParam,
                                            targetDelta = deltaParam,
                                            minRoc = rocParam
                                        )
                                        Log.d("SCAN_LOGIC", "Manual scan for $manualTicker returned ${results.size} items")
                                        scanResults = results
                                    } else {
                                        val batches = watchlist.chunked(7)
                                        val combinedResults = mutableListOf<ScanResultItem>()

                                        for (batch in batches) {
                                            val batchString = batch.joinToString(",")
                                            Log.d("SCAN_LOGIC", "Requesting batch: $batchString")
                                            val batchResults = apiService.getScanResults(
                                                tickers = batchString,
                                                strategy = strategyParam,
                                                targetDelta = deltaParam,
                                                minRoc = rocParam
                                            )
                                            Log.d("SCAN_LOGIC", "Batch returned ${batchResults.size} items")
                                            combinedResults.addAll(batchResults)
                                        }
                                        scanResults = combinedResults
                                    }

                                    if (scanResults.isEmpty()) {
                                        Toast.makeText(context, "No opportunities found. Try adjusting tuner parameters.", Toast.LENGTH_LONG).show()
                                    }
                                } catch (e: Exception) {
                                    Log.e("API_ERROR", "Scan failed: ${e.message}")
                                    Toast.makeText(context, "Scan error: ${e.message}", Toast.LENGTH_LONG).show()
                                } finally {
                                    isLoading = false
                                }
                            }
                        },
                        onLongClick = {
                            showWatchlistDialog = true
                        }
                    )
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp)
                } else {
                    val buttonText = if (manualTicker.isNotBlank()) "Run Stock Scan" else "Run Watch List Scan"
                    Text(buttonText, fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

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

@Composable
fun ScanResultCard(item: ScanResultItem, strategyFilter: String, scope: kotlinx.coroutines.CoroutineScope, context: android.content.Context) {
    // Debug logging to help identify why results might be missing
    LaunchedEffect(item) {
        Log.d("SCAN_UI", "Rendering ticker: ${item.ticker}")
        Log.d("SCAN_UI", "Data - CSPs: ${item.csps?.size ?: 0}, Diags: ${item.diagonals?.size ?: 0}, Verts: ${item.verticals?.size ?: 0}, LEAPS: ${item.longLeaps?.size ?: 0}")
    }

    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = RoundedCornerShape(8.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(text = item.ticker, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                Text(text = "$${item.price}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
            Row {
                if (item.rsi != null) Text("RSI: ${"%.1f".format(item.rsi)} ", style = MaterialTheme.typography.bodySmall)
                if (item.beta != null) Text("Beta: ${"%.2f".format(item.beta)}", style = MaterialTheme.typography.bodySmall)
            }

            // CSP Results (Ordered by ROC desc, limit 10)
            if (strategyFilter == "All" || strategyFilter == "CSPs") {
                val sortedCsps = item.csps?.sortedByDescending {
                    it.roc.parseToDouble()
                }?.take(10)

                sortedCsps?.forEach { csp ->
                    OpportunityRow(
                        title = "CSP Strike ${csp.strike}",
                        subtitle = "Prem: $${csp.premium} | Delta: ${csp.delta} | ROC: ${csp.roc ?: "N/A"}",
                        bt = csp.bt ?: "N/A",
                        onAdd = {
                            scope.launch {
                                try {
                                    val trade = TradeEntry(
                                        ticker = item.ticker, strike = csp.strike, expiry = "45DTE",
                                        trigger_price = item.price, entry_premium = csp.premium,
                                        contracts = 1, strategy = "CSP", is_call = 0, is_buy = 0
                                    )
                                    apiService.addPosition(trade)
                                    Toast.makeText(context, "Added ${item.ticker} CSP to portfolio", Toast.LENGTH_SHORT).show()
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Failed to add position", Toast.LENGTH_SHORT).show()
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
                    OpportunityRow(
                        title = "Diagonal: ${diag.longLeg ?: "?"} / ${diag.shortLeg ?: "?"}",
                        subtitle = "Net Debit: $${diag.netDebt} | Yield: ${diag.yieldRatio ?: "N/A"}",
                        bt = diag.bt ?: "N/A",
                        onAdd = { /* Logic for adding diagonal */ }
                    )
                }
            }

            // Vertical Results (Limit 10)
            if (strategyFilter == "All" || strategyFilter == "Verticals") {
                item.verticals?.take(10)?.forEach { vert ->
                    OpportunityRow(
                        title = "Vertical: ${vert.strikes ?: "N/A"}",
                        subtitle = "Net Debit: $${vert.netDebit}",
                        bt = vert.bt ?: "N/A",
                        onAdd = { /* Logic for adding vertical */ }
                    )
                }
            }

            // Long LEAPS Results UI Block (Limit 10)
            if (strategyFilter == "All" || strategyFilter == "Long LEAPS") {
                item.longLeaps?.take(10)?.forEach { leaps ->
                    OpportunityRow(
                        title = "Long LEAPS: ${leaps.expiry} $${leaps.strike}C",
                        subtitle = "Prem: $${leaps.premium} | Lev: ${leaps.leverage ?: "N/A"} | Intr: ${leaps.intrinsicBuffer ?: "N/A"}",
                        bt = leaps.bt ?: "N/A",
                        onAdd = {
                            scope.launch {
                                try {
                                    val trade = TradeEntry(
                                        ticker = item.ticker, strike = leaps.strike, expiry = leaps.expiry,
                                        trigger_price = item.price, entry_premium = leaps.premium,
                                        contracts = 1, strategy = "Long LEAPS", is_call = 1, is_buy = 1
                                    )
                                    apiService.addPosition(trade)
                                    Toast.makeText(context, "Added ${item.ticker} LEAPS to portfolio", Toast.LENGTH_SHORT).show()
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Failed to add position", Toast.LENGTH_SHORT).show()
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
    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
            val isSuccess = bt.contains("Success", ignoreCase = true) || bt.contains("OK", ignoreCase = true)
            Text("Backtest: $bt", style = MaterialTheme.typography.bodySmall, color = if (isSuccess) Color(0xFF388E3C) else Color.Red)
        }
        IconButton(onClick = onAdd) {
            Icon(Icons.Default.AddCircle, contentDescription = "Add Position", tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
fun PortfolioScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var healthData by remember { mutableStateOf<HealthResponse?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var showAddManualDialog by remember { mutableStateOf(false) }
    var closingPosition by remember { mutableStateOf<ActivePosition?>(null) }

    fun refreshData() {
        scope.launch {
            try {
                isLoading = true
                healthData = apiService.getHealth()
            } catch (e: Exception) {
                Toast.makeText(context, "Failed to load health data", Toast.LENGTH_SHORT).show()
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        refreshData()
    }

    if (showAddManualDialog) {
        AddManualPositionDialog(
            onDismiss = { showAddManualDialog = false },
            onSave = { trade ->
                scope.launch {
                    try {
                        apiService.addPosition(trade)
                        Toast.makeText(context, "Position added successfully", Toast.LENGTH_SHORT).show()
                        showAddManualDialog = false
                        refreshData()
                    } catch (e: Exception) {
                        Toast.makeText(context, "Failed to add position", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }

    if (closingPosition != null) {
        ClosePositionDialog(
            position = closingPosition!!,
            onDismiss = { closingPosition = null },
            onConfirm = { exitPrice, exitDate ->
                scope.launch {
                    try {
                        apiService.closePosition(closingPosition!!.id!!, mapOf("exit_price" to exitPrice, "exit_date" to exitDate))
                        Toast.makeText(context, "Position closed", Toast.LENGTH_SHORT).show()
                        closingPosition = null
                        refreshData()
                    } catch (e: Exception) {
                        Toast.makeText(context, "Failed to close position", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddManualDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add Manual Position")
            }
        }
    ) { padding ->
        if (isLoading && healthData == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (healthData != null) {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
                item {
                    Text("Portfolio Health", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(16.dp))

                    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Capital Committed", style = MaterialTheme.typography.labelLarge)
                            Text("$${"%,.2f".format(healthData?.capitalHealth?.committed)}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Monthly Realized Profit", style = MaterialTheme.typography.labelLarge)
                            Text("$${"%,.2f".format(healthData?.performance?.monthlyRealized)}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Text("Goal Progress: ${healthData?.performance?.progress}", style = MaterialTheme.typography.bodyMedium)
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                    Text("Active Positions", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                }

                items(healthData?.activePositions ?: emptyList()) { pos ->
                    PositionCard(
                        pos = pos,
                        onRemove = {
                            scope.launch {
                                try {
                                    pos.id?.let { id ->
                                        apiService.removePosition(id)
                                        Toast.makeText(context, "Position removed", Toast.LENGTH_SHORT).show()
                                        refreshData()
                                    }
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Failed to remove position", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        onClose = { closingPosition = pos }
                    )
                }

                item {
                    Spacer(modifier = Modifier.height(24.dp))
                    Text("Closed Positions", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                }

                items(healthData?.closedPositions ?: emptyList()) { pos ->
                    ClosedPositionCard(pos)
                }
            }
        }
    }
}

@Composable
fun PositionCard(pos: ActivePosition, onRemove: () -> Unit, onClose: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("${pos.ticker} ${pos.strategy}", fontWeight = FontWeight.Bold)
                Text("${pos.contracts}x ${pos.strike} Exp: ${pos.expiry}", style = MaterialTheme.typography.bodySmall)
            }
            Text("$${pos.entryPremium}", fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp))
            IconButton(onClick = onClose) {
                Icon(Icons.Default.CheckCircle, contentDescription = "Close Position", tint = Color(0xFF388E3C))
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.Delete, contentDescription = "Delete Position", tint = MaterialTheme.colorScheme.error)
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
                Text("${pos.contracts}x ${pos.strike} closed on ${pos.exitDate}", style = MaterialTheme.typography.bodySmall)
            }
            Column(horizontalAlignment = Alignment.End) {
                val profit = (pos.exitPrice - pos.entryPremium) * pos.contracts * 100 // Approximation for options
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
                Text("Entry Premium: $${position.entryPremium}")
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = exitPrice,
                    onValueChange = { exitPrice = it },
                    label = { Text("Exit Price") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = exitDate,
                    onValueChange = { exitDate = it },
                    label = { Text("Exit Date (YYYY-MM-DD)") }
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(exitPrice, exitDate) }) { Text("Confirm Close") }
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
    var contracts by remember { mutableStateOf("1") }
    var strike by remember { mutableStateOf("") }
    var expiry by remember { mutableStateOf(java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())) }
    var entryPremium by remember { mutableStateOf("") }
    var isClosed by remember { mutableStateOf(false) }
    var exitPrice by remember { mutableStateOf("") }
    var exitDate by remember { mutableStateOf(java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Manual Position") },
        text = {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                item {
                    OutlinedTextField(value = ticker, onValueChange = { ticker = it.uppercase() }, label = { Text("Ticker") })
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(value = strategy, onValueChange = { strategy = it }, label = { Text("Strategy (CSP, Vertical, etc.)") })
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(value = strike, onValueChange = { strike = it }, label = { Text("Strike Price") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(value = contracts, onValueChange = { contracts = it }, label = { Text("Contracts") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(value = expiry, onValueChange = { expiry = it }, label = { Text("Expiry Date (YYYY-MM-DD)") })
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(value = entryPremium, onValueChange = { entryPremium = it }, label = { Text("Entry Premium") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = isClosed, onCheckedChange = { isClosed = it })
                        Text("Add as Closed Position")
                    }

                    if (isClosed) {
                        OutlinedTextField(value = exitPrice, onValueChange = { exitPrice = it }, label = { Text("Exit Price") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number) )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(value = exitDate, onValueChange = { exitDate = it }, label = { Text("Exit Date (YYYY-MM-DD)") })
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val trade = TradeEntry(
                        ticker = ticker,
                        strike = strike.toDoubleOrNull() ?: 0.0,
                        expiry = expiry,
                        trigger_price = 0.0,
                        entry_premium = entryPremium.toDoubleOrNull() ?: 0.0,
                        contracts = contracts.toIntOrNull() ?: 1,
                        strategy = strategy,
                        is_call = if (strategy.contains("Call", true)) 1 else 0,
                        is_buy = 0,
                        exit_price = if (isClosed) exitPrice.toDoubleOrNull() else null,
                        exit_date = if (isClosed) exitDate else null
                    )
                    onSave(trade)
                }
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = { onDismiss() }) { Text("Cancel") }
        }
    )
}
