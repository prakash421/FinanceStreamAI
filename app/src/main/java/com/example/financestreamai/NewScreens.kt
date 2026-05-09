package com.example.financestreamai

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ==========================================
// SECTOR ROTATION SCREEN
// ==========================================
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SectorRotationScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var data by remember { mutableStateOf<SectorRotationResponse?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var selectedPeriod by remember { mutableStateOf("3mo") }
    val periods = listOf("1mo", "3mo", "6mo")
    // Backend accepts only "1mo" / "3mo" / "6mo" — verified May 2026.
    // (Earlier code converted these to "1m"/"3m"/"6m" which returned HTTP 400.)

    fun loadData() {
        scope.launch {
            try {
                isLoading = true
                errorMessage = null
                data = withContext(Dispatchers.IO) { apiService.getSectorRotation(selectedPeriod) }
            } catch (e: Exception) {
                errorMessage = e.message ?: "Failed to load sector rotation data"
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(selectedPeriod) { loadData() }

    Scaffold(
        topBar = {
            @OptIn(ExperimentalMaterial3Api::class)
            TopAppBar(
                title = { Text("Sector Rotation") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Period selector
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                    periods.forEach { period ->
                        FilterChip(
                            selected = selectedPeriod == period,
                            onClick = { selectedPeriod = period },
                            label = { Text(period.uppercase()) }
                        )
                    }
                }
            }

            if (isLoading) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }

            if (errorMessage != null) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Text(errorMessage!!, modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }

            // Rotation signals
            if (data?.rotationSignals != null) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1565C0).copy(alpha = 0.08f)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text("Market Rotation Signals", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Color(0xFF1565C0))
                            Spacer(modifier = Modifier.height(6.dp))
                            data?.rotationSignals?.forEach { signal ->
                                val icon = when {
                                    signal.contains("INTO") -> "🟢"
                                    signal.contains("OUT OF") -> "🔴"
                                    signal.contains("Defensive") || signal.contains("⚠") -> "⚠️"
                                    signal.contains("Risk-on") -> "📈"
                                    else -> "•"
                                }
                                Text("$icon $signal", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp))
                            }
                        }
                    }
                }
            }

            // Top / Bottom sectors summary
            if (data?.topSectors != null || data?.bottomSectors != null) {
                item {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (data?.topSectors != null) {
                            Card(
                                modifier = Modifier.weight(1f),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF2E7D32).copy(alpha = 0.08f)),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text("Top Sectors", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Color(0xFF2E7D32))
                                    data?.topSectors?.forEachIndexed { i, s ->
                                        Text("${i + 1}. $s", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                        if (data?.bottomSectors != null) {
                            Card(
                                modifier = Modifier.weight(1f),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFFC62828).copy(alpha = 0.08f)),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text("Bottom Sectors", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Color(0xFFC62828))
                                    data?.bottomSectors?.forEachIndexed { i, s ->
                                        Text("${i + 1}. $s", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Sector table
            if (data?.sectors != null) {
                items(data!!.sectors) { sector ->
                    SectorCard(sector)
                }
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SectorCard(sector: SectorData) {
    val returnColor = if (sector.returnPeriod >= 0) Color(0xFF2E7D32) else Color(0xFFC62828)
    val flowColor = when (sector.moneyFlow) {
        "inflow" -> Color(0xFF2E7D32)
        "outflow" -> Color(0xFFC62828)
        else -> Color.Gray
    }
    val flowIcon = when (sector.moneyFlow) {
        "inflow" -> "💰"
        "outflow" -> "📤"
        else -> "➖"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("#${sector.rank}", style = MaterialTheme.typography.labelMedium, color = Color.Gray)
                        Text(sector.sector, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Card(colors = CardDefaults.cardColors(containerColor = Color.Gray.copy(alpha = 0.12f)), shape = RoundedCornerShape(4.dp)) {
                            Text(sector.etf, modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp), style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                        }
                    }
                }
                Card(colors = CardDefaults.cardColors(containerColor = returnColor.copy(alpha = 0.12f)), shape = RoundedCornerShape(8.dp)) {
                    Text(
                        "${if (sector.returnPeriod >= 0) "+" else ""}${"%.1f".format(sector.returnPeriod)}%",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = returnColor
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Card(colors = CardDefaults.cardColors(containerColor = flowColor.copy(alpha = 0.10f)), shape = RoundedCornerShape(6.dp)) {
                    Text("$flowIcon ${sector.moneyFlow}", modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = flowColor)
                }
                val recentColor = if (sector.returnRecent >= 0) Color(0xFF2E7D32) else Color(0xFFC62828)
                Card(colors = CardDefaults.cardColors(containerColor = recentColor.copy(alpha = 0.10f)), shape = RoundedCornerShape(6.dp)) {
                    Text("Recent: ${"%+.1f".format(sector.returnRecent)}%", modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = recentColor)
                }
                val volColor = if (sector.volumeChangePct > 5) Color(0xFFEF6C00) else Color.Gray
                Card(colors = CardDefaults.cardColors(containerColor = volColor.copy(alpha = 0.10f)), shape = RoundedCornerShape(6.dp)) {
                    Text("Vol: ${"%+.1f".format(sector.volumeChangePct)}%", modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = volColor)
                }
            }
        }
    }
}

// ==========================================
// AI LEARNINGS SCREEN
// ==========================================
@Composable
fun AiLearningsScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var stats by remember { mutableStateOf<RecommendationStats?>(null) }
    var learnings by remember { mutableStateOf<LearningsResponse?>(null) }
    var history by remember { mutableStateOf<List<RecommendationItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var selectedTab by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        isLoading = true
        try {
            // Stats and history are mandatory — surface their errors.
            val statsResult = withContext(Dispatchers.IO) { apiService.getRecommendationStats() }
            stats = statsResult
            val historyResult = withContext(Dispatchers.IO) { apiService.getRecommendationHistory(days = 90, limit = 200) }
            history = historyResult
            // /recommendations/learnings is optional — backend may 404 it. If so,
            // synthesise a learnings payload locally from the history+stats so
            // the Signals tab still has actionable content.
            learnings = try {
                withContext(Dispatchers.IO) { apiService.getLearnings() }
            } catch (e: Exception) {
                android.util.Log.w("AiLearnings", "/recommendations/learnings unavailable (${e.message}); deriving locally")
                LocalLearnings.derive(historyResult, statsResult)
            }
        } catch (e: Exception) {
            errorMessage = e.message ?: "Failed to load AI data"
        } finally {
            isLoading = false
        }
    }

    Scaffold(
        topBar = {
            @OptIn(ExperimentalMaterial3Api::class)
            TopAppBar(
                title = { Text("AI Learnings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Tab Row
            TabRow(selectedTabIndex = selectedTab) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Stats") })
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("Signals") })
                Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }, text = { Text("History") })
            }

            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (errorMessage != null) {
                Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Warning, contentDescription = null, modifier = Modifier.size(48.dp), tint = Color.Gray)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(errorMessage!!, style = MaterialTheme.typography.bodyMedium, color = Color.Gray, textAlign = TextAlign.Center)
                    }
                }
            } else if (stats?.enabled == false && learnings?.enabled == false) {
                Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.School, contentDescription = null, modifier = Modifier.size(56.dp), tint = Color(0xFF7C3AED).copy(alpha = 0.5f))
                        Text("Learning in progress", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            "The AI learning module hasn't accumulated enough data yet. Run daily scans and check back after a week — the system evaluates recommendations every Monday.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.Gray,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                when (selectedTab) {
                    0 -> StatsTab(stats)
                    1 -> SignalsTab(learnings)
                    2 -> HistoryTab(history)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StatsTab(stats: RecommendationStats?) {
    if (stats == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No stats available yet", color = Color.Gray)
        }
        return
    }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Total Recommendations", style = MaterialTheme.typography.labelLarge)
                    Text("${stats.totalRecommendations ?: 0}", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text("Last ${stats.horizonDays ?: 90} days", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
            }
        }

        // By Strategy
        if (stats.byStrategy != null) {
            item {
                Text("Win Rate by Strategy", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
            }
            stats.byStrategy.entries.sortedByDescending { it.value.winRate }.forEach { (strategy, s) ->
                item {
                    WinRateCard(label = strategy.uppercase(), winning = s.winning, losing = s.losing, total = s.total, winRate = s.winRate)
                }
            }
        }

        // By Verdict
        if (stats.byVerdict != null) {
            item {
                Text("Win Rate by Verdict", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
            }
            stats.byVerdict.entries.sortedByDescending { it.value.winRate }.forEach { (verdict, s) ->
                item {
                    WinRateCard(label = verdict, winning = s.winning, losing = s.losing, total = s.total, winRate = s.winRate)
                }
            }
        }
    }
}

@Composable
fun WinRateCard(label: String, winning: Int, losing: Int, total: Int, winRate: Double) {
    val winColor = when {
        winRate >= 70 -> Color(0xFF2E7D32)
        winRate >= 50 -> Color(0xFFEF6C00)
        else -> Color(0xFFC62828)
    }
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp)) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(label, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                Text("$winning W / $losing L / $total total", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
            Card(colors = CardDefaults.cardColors(containerColor = winColor.copy(alpha = 0.12f)), shape = RoundedCornerShape(8.dp)) {
                Text("${"%.1f".format(winRate)}%", modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp), fontWeight = FontWeight.Bold, color = winColor, style = MaterialTheme.typography.titleSmall)
            }
        }
    }
}

@Composable
fun SignalsTab(learnings: LearningsResponse?) {
    if (learnings == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No learnings available yet", color = Color.Gray)
        }
        return
    }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Verdict baselines
        if (!learnings.verdictBaselines.isNullOrEmpty()) {
            item { Text("Verdict Performance", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            learnings.verdictBaselines.forEach { vb ->
                item {
                    WinRateCard(label = "${vb.strategy.uppercase()} / ${vb.verdict}", winning = vb.winning, losing = vb.total - vb.winning, total = vb.total, winRate = vb.winRate)
                }
            }
        }

        // Top winning signals
        if (!learnings.topWinningSignals.isNullOrEmpty()) {
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text("🏆 Top Winning Signals", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color(0xFF2E7D32))
            }
            learnings.topWinningSignals.forEach { sig ->
                item {
                    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF2E7D32).copy(alpha = 0.06f))) {
                        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(sig.signal, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                                if (sig.strategy != null) Text(sig.strategy.uppercase(), style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                            }
                            Text("${"%.0f".format(sig.winRate)}% (${sig.total})", fontWeight = FontWeight.Bold, color = Color(0xFF2E7D32), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }

        // Top losing signals
        if (!learnings.topLosingSignals.isNullOrEmpty()) {
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text("⚠ Top Losing Signals", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color(0xFFC62828))
            }
            learnings.topLosingSignals.forEach { sig ->
                item {
                    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFFC62828).copy(alpha = 0.06f))) {
                        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(sig.signal, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                                if (sig.strategy != null) Text(sig.strategy.uppercase(), style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                            }
                            Text("${"%.0f".format(sig.winRate)}% (${sig.total})", fontWeight = FontWeight.Bold, color = Color(0xFFC62828), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }

        // Suggested adjustments
        if (!learnings.suggestedAdjustments.isNullOrEmpty()) {
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text("AI Adjustments", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            learnings.suggestedAdjustments.forEach { adj ->
                item {
                    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
                        Text(adj, modifier = Modifier.padding(10.dp), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}

@Composable
fun HistoryTab(history: List<RecommendationItem>) {
    if (history.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No recommendation history yet", color = Color.Gray)
        }
        return
    }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(history) { rec ->
            val verdictColor = when {
                rec.verdict?.contains("STRONG BUY", true) == true -> Color(0xFF1B5E20)
                rec.verdict?.contains("BUY", true) == true -> Color(0xFF2E7D32)
                rec.verdict?.contains("SELL", true) == true -> Color(0xFFC62828)
                rec.verdict?.contains("HOLD", true) == true -> Color(0xFFEF6C00)
                else -> Color.Gray
            }
            val statusColor = when (rec.finalStatus) {
                "winning" -> Color(0xFF2E7D32)
                "losing" -> Color(0xFFC62828)
                else -> Color.Gray
            }
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(rec.ticker, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                            if (rec.strategy != null) {
                                Card(colors = CardDefaults.cardColors(containerColor = Color.Gray.copy(alpha = 0.12f)), shape = RoundedCornerShape(4.dp)) {
                                    Text(rec.strategy.uppercase(), modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp), style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                                }
                            }
                        }
                        if (rec.verdict != null) {
                            Card(colors = CardDefaults.cardColors(containerColor = verdictColor.copy(alpha = 0.12f)), shape = RoundedCornerShape(6.dp)) {
                                Text(rec.verdict, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = verdictColor)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (rec.entryPrice != null) Text("Entry: $${"%.2f".format(rec.entryPrice)}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        if (rec.scanDate != null) Text(rec.scanDate, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        if (rec.closed) {
                            Card(colors = CardDefaults.cardColors(containerColor = statusColor.copy(alpha = 0.12f)), shape = RoundedCornerShape(4.dp)) {
                                Text(rec.finalStatus?.uppercase() ?: "CLOSED", modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = statusColor)
                            }
                        } else {
                            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1565C0).copy(alpha = 0.12f)), shape = RoundedCornerShape(4.dp)) {
                                Text("ACTIVE", modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color(0xFF1565C0))
                            }
                        }
                    }
                    // Outcome history
                    if (!rec.outcomeHistory.isNullOrEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            rec.outcomeHistory.forEach { outcome ->
                                val oc = when (outcome.status) {
                                    "winning" -> Color(0xFF2E7D32)
                                    "losing" -> Color(0xFFC62828)
                                    else -> Color.Gray
                                }
                                Card(colors = CardDefaults.cardColors(containerColor = oc.copy(alpha = 0.12f)), shape = RoundedCornerShape(4.dp)) {
                                    Text(
                                        "W${outcome.week}: ${"%+.1f".format(outcome.priceChangePct ?: 0.0)}%",
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = oc
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}




// ==========================================
// LOCAL LEARNINGS DERIVATION
// ==========================================
/**
 * The backend's /recommendations/learnings endpoint is not deployed (returns
 * 404 as of May 2026). To keep the AI Learnings -> Signals tab actionable, we
 * derive an equivalent payload client-side from the rich /recommendations/history
 * data and the /recommendations/stats summary.
 */
object LocalLearnings {
    fun derive(history: List<RecommendationItem>, stats: RecommendationStats?): LearningsResponse {
        val tally = mutableMapOf<Pair<String, String>, IntArray>()

        for (rec in history) {
            val strategy = rec.strategy ?: continue
            val signals = extractSignals(rec)
            val outcomes = rec.outcomeHistory ?: continue
            for (signal in signals) {
                val key = signal to strategy
                val arr = tally.getOrPut(key) { IntArray(3) }
                for (o in outcomes) {
                    when (o.status) {
                        "winning" -> arr[0]++
                        "losing" -> arr[1]++
                        else -> arr[2]++
                    }
                }
            }
        }

        val signalStats = tally.map { (k, v) ->
            val total = v[0] + v[1] + v[2]
            val winRate = if (total > 0) v[0] * 100.0 / total else 0.0
            SignalStat(strategy = k.second, signal = k.first, winning = v[0], total = total, winRate = winRate)
        }
        val significant = signalStats.filter { it.total >= 6 }

        val topWinning = significant.sortedByDescending { it.winRate }.filter { it.winRate >= 60.0 }.take(10)
        val topLosing = significant.sortedBy { it.winRate }.filter { it.winRate < 50.0 }.take(10)

        val suggested = mutableListOf<String>()
        stats?.byStrategy?.forEach { (strat, s) ->
            if (s.total >= 30 && s.winRate < 50.0) {
                suggested += "Strategy '${strat.uppercase()}' has a ${"%.1f".format(s.winRate)}% win-rate over ${s.total} samples - raise its backtest / signal threshold."
            }
        }
        stats?.byVerdict?.let { v ->
            val buy = v["BUY"]
            val strong = v["STRONG BUY"]
            if (buy != null && strong != null && buy.total >= 50 && strong.total >= 50 &&
                strong.winRate - buy.winRate >= 10.0
            ) {
                suggested += "STRONG BUY beats BUY by ${"%.0f".format(strong.winRate - buy.winRate)} pts - consider only acting on STRONG BUY tier."
            }
        }
        if (topLosing.isNotEmpty()) {
            val worst = topLosing.first()
            suggested += "Worst signal: '${worst.signal}' on ${worst.strategy?.uppercase()} (${"%.0f".format(worst.winRate)}% over ${worst.total} obs)."
        }
        if (topWinning.isNotEmpty()) {
            val best = topWinning.first()
            suggested += "Best signal: '${best.signal}' on ${best.strategy?.uppercase()} (${"%.0f".format(best.winRate)}% over ${best.total} obs)."
        }

        return LearningsResponse(
            enabled = true,
            asOf = "Derived locally from history",
            verdictBaselines = null,
            topWinningSignals = topWinning,
            topLosingSignals = topLosing,
            suggestedAdjustments = suggested
        )
    }

    private fun extractSignals(rec: RecommendationItem): List<String> {
        val out = mutableListOf<String>()
        val summary = rec.stockSummary?.lowercase() ?: ""

        val rsiMatch = Regex("""rsi\s+(\d+)""").find(summary)
        if (rsiMatch != null) {
            val rsi = rsiMatch.groupValues[1].toIntOrNull()
            if (rsi != null) {
                out += when {
                    rsi < 30 -> "RSI <30 (oversold)"
                    rsi < 40 -> "RSI 30-40"
                    rsi < 50 -> "RSI 40-50"
                    rsi < 60 -> "RSI 50-60"
                    rsi < 70 -> "RSI 60-70"
                    else -> "RSI >=70 (overbought)"
                }
            }
        }

        when {
            "uptrend" in summary -> out += "Trend: uptrend"
            "downtrend" in summary -> out += "Trend: downtrend"
            "sideways" in summary -> out += "Trend: sideways"
        }

        val ddMatch = Regex("""(\d+)%\s+off\s+high""").find(summary)
        if (ddMatch != null) {
            val dd = ddMatch.groupValues[1].toIntOrNull()
            if (dd != null) {
                out += when {
                    dd < 5 -> "Near 52w high (<5% off)"
                    dd < 15 -> "5-15% off high"
                    dd < 30 -> "15-30% off high"
                    else -> ">=30% off high"
                }
            }
        }

        val breadthMatch = Regex("""(\d+)\s+bullish\s+vs\s+(\d+)\s+bearish""").find(summary)
        if (breadthMatch != null) {
            val bull = breadthMatch.groupValues[1].toIntOrNull() ?: 0
            val bear = breadthMatch.groupValues[2].toIntOrNull() ?: 0
            val breadth = bull - bear
            out += when {
                breadth >= 5 -> "Breadth >=+5"
                breadth >= 0 -> "Breadth 0..+5"
                breadth >= -5 -> "Breadth -5..0"
                else -> "Breadth <=-5"
            }
        }

        rec.matchDetail?.let { md ->
            val bt = md["bt"] as? String
            if (bt != null) {
                val pct = bt.replace("%", "").trim().toDoubleOrNull()
                if (pct != null) {
                    out += when {
                        pct >= 95 -> "BT >=95%"
                        pct >= 90 -> "BT 90-95%"
                        pct >= 80 -> "BT 80-90%"
                        else -> "BT <80%"
                    }
                }
            }
        }

        return out
    }
}

// ==========================================
// ACCOUNT SCREEN — current login info + sign-out
// ==========================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(onBack: () -> Unit, onSignOut: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val name = remember { GoogleAuthManager.getUserName(context) ?: "Unknown" }
    val email = remember { GoogleAuthManager.getUserEmail(context) ?: "—" }
    val photoUrl = remember { GoogleAuthManager.getUserPhoto(context) }
    val userId = remember { GoogleAuthManager.getUserId(context) ?: "—" }
    val isGuest = remember { userId.startsWith("guest-") }

    var showSignOutConfirm by remember { mutableStateOf(false) }

    if (showSignOutConfirm) {
        AlertDialog(
            onDismissRequest = { showSignOutConfirm = false },
            title = { Text(if (isGuest) "Exit guest session?" else "Sign out?") },
            text = {
                Text(
                    if (isGuest)
                        "You'll be returned to the sign-in screen. Your watchlist and portfolio stay on the backend keyed to this guest id."
                    else
                        "You'll be returned to the sign-in screen. Your data is kept on the backend and will be available again when you sign back in with $email."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showSignOutConfirm = false
                    onSignOut()
                }) { Text("Sign out", color = Color(0xFFDC2626)) }
            },
            dismissButton = {
                TextButton(onClick = { showSignOutConfirm = false }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Account") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(20.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Profile card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFEEF2FF)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Avatar — photo if available, else initial in a circle
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .background(Color(0xFF4338CA), shape = androidx.compose.foundation.shape.CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        if (!photoUrl.isNullOrBlank()) {
                            // Coil/Glide isn't a current dependency — show initial as a
                            // reliable fallback. (Photo URL is still surfaced below.)
                            Text(
                                text = name.take(1).uppercase(),
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.headlineMedium
                            )
                        } else {
                            Icon(
                                Icons.Default.AccountCircle,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(48.dp)
                            )
                        }
                    }
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        if (!isGuest) {
                            Text(email, style = MaterialTheme.typography.bodyMedium, color = Color(0xFF475569))
                        }
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (isGuest) Color(0xFFFEF3C7) else Color(0xFFDCFCE7)
                        ) {
                            Text(
                                if (isGuest) "Guest session" else "Signed in with Google",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isGuest) Color(0xFF92400E) else Color(0xFF166534)
                            )
                        }
                    }
                }
            }

            // Details card
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Account details", fontWeight = FontWeight.SemiBold, color = Color(0xFF334155))
                    AccountRow(label = "Display name", value = name)
                    if (!isGuest) AccountRow(label = "Email", value = email)
                    AccountRow(label = "User ID", value = userId, mono = true)
                    val configuredAi = remember { AiKeyManager.getConfiguredEngines(context) }
                    AccountRow(
                        label = "AI keys configured",
                        value = if (configuredAi.isEmpty()) "None" else configuredAi.joinToString(", ")
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Sign-out button
            Button(
                onClick = { showSignOutConfirm = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))
            ) {
                Icon(Icons.Default.ExitToApp, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (isGuest) "Exit guest session" else "Sign out")
            }
            Text(
                "Signing out keeps your data safe on the backend; sign back in any time to restore it.",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF64748B),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun AccountRow(label: String, value: String, mono: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            label,
            modifier = Modifier.weight(0.4f),
            color = Color(0xFF64748B),
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            value,
            modifier = Modifier.weight(0.6f),
            style = if (mono) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
            color = Color(0xFF0F172A)
        )
    }
}

// ==========================================
// GEMINI CHAT SCREEN — free-form follow-up Q&A
// ==========================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeminiChatScreen(onBack: () -> Unit) {
    // Hosts the chat panel inside a full-screen Scaffold with TopAppBar.
    // The actual chat UI lives in [GeminiChatPanel] so it can also be
    // embedded in split-screen overlays (e.g. on the scan results screen).
    val panelState = rememberGeminiChatPanelState()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ask Gemini") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (panelState.messages.isNotEmpty()) {
                        TextButton(onClick = { panelState.clear() }) { Text("Clear") }
                    }
                }
            )
        }
    ) { padding ->
        GeminiChatPanel(
            state = panelState,
            modifier = Modifier.padding(padding).fillMaxSize()
        )
    }
}

/** Hoisted state for [GeminiChatPanel] so multiple call sites (full screen,
 *  split overlay) can share or independently own a chat session. */
class GeminiChatPanelState {
    val messages = mutableStateListOf<GeminiChat.Message>()
    var input by mutableStateOf("")
    var sending by mutableStateOf(false)
    var lastError by mutableStateOf<String?>(null)
    var includeScanContext by mutableStateOf(LastScanContext.results.isNotEmpty())
    fun clear() { messages.clear(); lastError = null }
}

@Composable
fun rememberGeminiChatPanelState(): GeminiChatPanelState = remember { GeminiChatPanelState() }

/**
 * The reusable Gemini chat UI: context banner, message list, error banner,
 * input bar. Used standalone in [GeminiChatScreen] and embedded in the
 * split-screen overlay on the scan results screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeminiChatPanel(
    state: GeminiChatPanelState,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val keyConfigured = remember { GeminiChat.isEnabled(context) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()

    // Auto-scroll to the newest message after each addition.
    LaunchedEffect(state.messages.size, state.sending) {
        val lastIdx = (state.messages.size - 1 + (if (state.sending) 1 else 0)).coerceAtLeast(0)
        if (lastIdx > 0) listState.animateScrollToItem(lastIdx)
    }

    fun buildScanContext(): String? {
        val results = LastScanContext.results
        if (!state.includeScanContext || results.isEmpty()) return null
        // Compact view of the user's most recent scan + any AI verdicts the
        // user is currently looking at, so follow-ups like "tell me about
        // NVDA" or "why did AI flag it MIXED?" can reference real data.
        val top = results.take(20)
        val ai = LastScanContext.aiValidations
        val rows = top.joinToString("\n") { item ->
            val rsi = item.rsi?.let { "%.0f".format(it) } ?: "?"
            val rec = item.stockRecommendation ?: item.overall ?: "-"
            val sec = item.sector?.takeIf { it.isNotBlank() } ?: "-"
            val csp = item.csps?.firstOrNull()?.let { " csp:\$${"%.0f".format(it.strike)}@\$${"%.2f".format(it.premium)}" } ?: ""
            val leap = item.longLeaps?.firstOrNull()?.let { " leap:\$${"%.0f".format(it.strike)} exp=${it.expiry}" } ?: ""
            val aiTag = ai[item.ticker]?.let { " ai=${it.consensus}(${it.agreementPct}%)" } ?: ""
            "${item.ticker} \$${"%.2f".format(item.price)} RSI=$rsi sec=$sec rec=$rec$csp$leap$aiTag"
        }
        val filterNote = LastScanContext.activeFilter?.takeIf { it != "All" }?.let {
            "\n\nThe user has filtered the list to show only $it results."
        } ?: ""
        return """You are an in-app assistant for a retail options-trading app called StockWiz AI. The user has just run a scan; here are the top ${top.size} results currently visible on their screen (live data from the backend — do NOT invent prices or strikes from memory):

$rows$filterNote

Answer the user's questions concisely. When they reference a ticker that's in the list above, ground your answer in those numbers. When they ask something outside the scan, say so. Keep replies under 150 words unless asked for detail."""
    }

    fun send() {
        val text = state.input.trim()
        if (text.isEmpty() || state.sending) return
        state.input = ""
        state.lastError = null
        val historySnapshot = state.messages.toList()
        state.messages.add(GeminiChat.Message(GeminiChat.Role.USER, text))
        state.sending = true
        scope.launch {
            val reply = GeminiChat.ask(context, historySnapshot, text, systemContext = buildScanContext())
            state.sending = false
            when (reply) {
                is GeminiChat.Reply.Ok -> state.messages.add(GeminiChat.Message(GeminiChat.Role.MODEL, reply.text))
                is GeminiChat.Reply.Error -> state.lastError = reply.message
                GeminiChat.Reply.NoKey -> state.lastError = "Gemini key not configured. Add it in the AI keys dialog on first launch."
            }
        }
    }

    Column(modifier = modifier) {
        // Context banner — shows whether scan results are being shared.
        // Hidden in compact mode (split-screen overlay) since the embedding
        // header already advertises the context and exposes the toggle.
        if (!compact && LastScanContext.results.isNotEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = if (state.includeScanContext) Color(0xFFEFF6FF) else Color(0xFFF1F5F9)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = if (compact) 4.dp else 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (state.includeScanContext) Icons.Default.Link else Icons.Default.LinkOff,
                        contentDescription = null,
                        tint = if (state.includeScanContext) Color(0xFF2563EB) else Color(0xFF64748B),
                        modifier = Modifier.size(if (compact) 14.dp else 18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        if (state.includeScanContext)
                            "Sharing ${LastScanContext.results.size.coerceAtMost(20)} scan results with Gemini"
                        else
                            "Scan context off",
                        style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
                        color = Color(0xFF334155),
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = state.includeScanContext,
                        onCheckedChange = { state.includeScanContext = it }
                    )
                }
            }
        }

        // Message list / empty state
        if (state.messages.isEmpty() && !state.sending) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(if (compact) 12.dp else 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Default.Chat, contentDescription = null, tint = Color(0xFF93C5FD), modifier = Modifier.size(if (compact) 36.dp else 64.dp))
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    if (keyConfigured) "Ask Gemini about your scan results" else "Add your Gemini API key to chat",
                    style = if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF334155),
                    textAlign = TextAlign.Center
                )
                if (!compact) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        if (keyConfigured)
                            "Try: \"Which is safest?\", \"Why did AI flag NVDA?\", \"Compare TSLA vs AMD\""
                        else
                            "Open the More menu → first-launch AI keys prompt to paste your Gemini key.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF64748B),
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = if (compact) 6.dp else 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(state.messages) { msg -> ChatBubble(msg) }
                if (state.sending) item { TypingIndicator() }
            }
        }

        // Error banner
        state.lastError?.let { err ->
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                color = Color(0xFFFEE2E2),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    "Gemini error: $err",
                    modifier = Modifier.padding(10.dp),
                    color = Color(0xFF991B1B),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        // Input bar
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shadowElevation = 4.dp,
            color = MaterialTheme.colorScheme.surface
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = if (compact) 4.dp else 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = state.input,
                    onValueChange = { state.input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Ask anything…", style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium) },
                    enabled = !state.sending && keyConfigured,
                    maxLines = if (compact) 2 else 4,
                    textStyle = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                    shape = RoundedCornerShape(20.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                FilledIconButton(
                    onClick = ::send,
                    enabled = state.input.isNotBlank() && !state.sending && keyConfigured,
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFF2563EB)),
                    modifier = if (compact) Modifier.size(36.dp) else Modifier
                ) {
                    if (state.sending) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(if (compact) 16.dp else 20.dp),
                            strokeWidth = 2.dp,
                            color = Color.White
                        )
                    } else {
                        Icon(Icons.Default.Send, contentDescription = "Send", tint = Color.White, modifier = Modifier.size(if (compact) 16.dp else 24.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatBubble(msg: GeminiChat.Message) {
    val isUser = msg.role == GeminiChat.Role.USER
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            color = if (isUser) Color(0xFF2563EB) else Color(0xFFF1F5F9),
            shape = RoundedCornerShape(
                topStart = 16.dp, topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp
            ),
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Text(
                msg.text,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                color = if (isUser) Color.White else Color(0xFF0F172A),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun TypingIndicator() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        Surface(
            color = Color(0xFFF1F5F9),
            shape = RoundedCornerShape(16.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = Color(0xFF2563EB)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Gemini is thinking…", color = Color(0xFF64748B), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}



