package com.example.financestreamai

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

// ============================================================
// Data models for AI Cross-Validation
// ============================================================

/** Result from a single AI engine */
data class AiEngineResult(
    val engine: String,          // "Claude", "Gemini", "ChatGPT", "Perplexity"
    val verdict: String,         // "BUY", "SELL", "HOLD", "AVOID"
    val confidence: String,      // "High", "Medium", "Low"
    val reasoning: String,       // 1-2 sentence explanation
    val error: String? = null    // non-null if call failed
)

/** Aggregated cross-validation result for a stock */
data class AiCrossValidation(
    val ticker: String,
    val engines: List<AiEngineResult>,
    val consensus: String,       // "STRONG BUY", "BUY", "HOLD", "AVOID", "MIXED"
    val agreementPct: Int,       // % of engines that agree with consensus
    val summary: String,         // e.g. "3/4 AI engines rate TSLA as BUY with High confidence"
    val timestamp: Long = System.currentTimeMillis()
)

// ============================================================
// API Key Manager (encrypted local storage + backup-friendly mirror)
// ============================================================
/**
 * Two-tier storage so user-supplied API keys survive uninstall / reinstall:
 *
 *  1. **Active store** ([PREFS_FILE]) — `EncryptedSharedPreferences` backed
 *     by Android Keystore. Most secure on-device but the keystore master
 *     key is destroyed on uninstall, so the file alone cannot be restored.
 *
 *  2. **Backup mirror** ([BACKUP_PREFS_FILE]) — regular `SharedPreferences`
 *     with each value lightly obfuscated (XOR + Base64). This file is
 *     included in Android Auto Backup ([backup_rules.xml] /
 *     [data_extraction_rules.xml]) so when the user reinstalls and signs
 *     into the same Google account the keys are restored automatically.
 *
 * On every read, if the active store is empty for a key but the backup
 * mirror has a value, we re-hydrate the active store transparently. So
 * the post-reinstall first read is the only one that touches the mirror.
 *
 * Security trade-off: the mirror's obfuscation is NOT cryptographic
 * protection (any attacker with adb access could reverse it). The real
 * defenses are (a) Android app sandboxing and (b) the user's Google
 * account encrypting the backup blob. This is the standard pattern for
 * persisting user-supplied secrets across reinstalls.
 */
object AiKeyManager {
    private const val PREFS_FILE = "ai_api_keys_encrypted"
    private const val BACKUP_PREFS_FILE = "ai_keys_backup"
    // Obfuscation pad — not a security boundary, just to defeat casual
    // grep/cat of the backed-up XML. 32 bytes is plenty.
    private val OBFUSCATION_PAD = byteArrayOf(
        0x4F, 0x70, 0x74, 0x69, 0x6F, 0x6E, 0x73, 0x57,
        0x69, 0x7A, 0x41, 0x49, 0x32, 0x30, 0x32, 0x36,
        0x46, 0x53, 0x41, 0x6B, 0x65, 0x79, 0x4D, 0x67,
        0x72, 0x76, 0x31, 0x37, 0x33, 0x39, 0x65, 0x4E
    )

    const val KEY_CLAUDE = "claude_api_key"
    const val KEY_GEMINI = "gemini_api_key"
    const val KEY_CHATGPT = "chatgpt_api_key"
    const val KEY_PERPLEXITY = "perplexity_api_key"
    const val KEY_GROK = "grok_api_key"
    private const val KEY_PROMPT_SHOWN = "ai_key_prompt_shown"

    private val ALL_KEYS = listOf(KEY_CLAUDE, KEY_GEMINI, KEY_CHATGPT, KEY_PERPLEXITY, KEY_GROK)

    private fun getPrefs(context: Context) = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        // Fallback to regular prefs if encrypted fails (rare, old devices)
        Log.e("AiKeyManager", "Encrypted prefs failed, using fallback", e)
        context.getSharedPreferences("ai_api_keys_fallback", Context.MODE_PRIVATE)
    }

    private fun getBackupPrefs(context: Context) =
        context.getSharedPreferences(BACKUP_PREFS_FILE, Context.MODE_PRIVATE)

    // -------- Obfuscation (XOR + Base64) --------
    internal fun obfuscate(value: String): String {
        if (value.isEmpty()) return ""
        val bytes = value.toByteArray(Charsets.UTF_8)
        val out = ByteArray(bytes.size)
        for (i in bytes.indices) out[i] = (bytes[i].toInt() xor OBFUSCATION_PAD[i % OBFUSCATION_PAD.size].toInt()).toByte()
        return android.util.Base64.encodeToString(out, android.util.Base64.NO_WRAP)
    }

    internal fun deobfuscate(stored: String): String? {
        if (stored.isEmpty()) return null
        return try {
            val raw = android.util.Base64.decode(stored, android.util.Base64.NO_WRAP)
            val out = ByteArray(raw.size)
            for (i in raw.indices) out[i] = (raw[i].toInt() xor OBFUSCATION_PAD[i % OBFUSCATION_PAD.size].toInt()).toByte()
            String(out, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w("AiKeyManager", "Failed to deobfuscate backup value: ${e.message}")
            null
        }
    }

    /**
     * Read the key, restoring from the backup mirror if the encrypted
     * store has lost it (e.g. fresh install after Auto Backup restore).
     */
    fun getKey(context: Context, keyName: String): String? {
        val active = getPrefs(context).getString(keyName, null)?.takeIf { it.isNotBlank() }
        if (active != null) return active

        // Try the backup mirror.
        val obfuscated = getBackupPrefs(context).getString(keyName, null)?.takeIf { it.isNotBlank() }
            ?: return null
        val restored = deobfuscate(obfuscated)?.takeIf { it.isNotBlank() } ?: return null
        // Re-hydrate the encrypted store so subsequent reads are fast.
        try {
            getPrefs(context).edit().putString(keyName, restored).apply()
            Log.i("AiKeyManager", "Restored $keyName from backup mirror after reinstall")
        } catch (e: Exception) {
            Log.w("AiKeyManager", "Could not re-hydrate encrypted store: ${e.message}")
        }
        return restored
    }

    /**
     * Write to BOTH the encrypted store and the backup mirror so the key
     * persists across uninstall/reinstall via Android Auto Backup.
     */
    fun setKey(context: Context, keyName: String, value: String) {
        val trimmed = value.trim()
        getPrefs(context).edit().putString(keyName, trimmed).apply()
        getBackupPrefs(context).edit().putString(keyName, obfuscate(trimmed)).apply()
    }

    fun clearKey(context: Context, keyName: String) {
        getPrefs(context).edit().remove(keyName).apply()
        getBackupPrefs(context).edit().remove(keyName).apply()
    }

    fun hasAnyKeys(context: Context): Boolean = ALL_KEYS.any { getKey(context, it) != null }

    fun getConfiguredEngines(context: Context): List<String> {
        val engines = mutableListOf<String>()
        if (getKey(context, KEY_CLAUDE) != null) engines.add("Claude")
        if (getKey(context, KEY_GEMINI) != null) engines.add("Gemini")
        if (getKey(context, KEY_CHATGPT) != null) engines.add("ChatGPT")
        if (getKey(context, KEY_PERPLEXITY) != null) engines.add("Perplexity")
        if (getKey(context, KEY_GROK) != null) engines.add("Grok")
        return engines
    }

    fun wasPromptShown(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_PROMPT_SHOWN, false)

    fun markPromptShown(context: Context) {
        getPrefs(context).edit().putBoolean(KEY_PROMPT_SHOWN, true).apply()
    }
}


// ============================================================
// AI Cross-Validator Engine
// ============================================================
object AiCrossValidator {
    private const val TAG = "AiCrossValidator"
    private val gson = Gson()
    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    // Cache results for 15 minutes to avoid repeated calls
    private val cache = mutableMapOf<String, AiCrossValidation>()
    private const val CACHE_TTL_MS = 15 * 60 * 1000L

    fun getCached(ticker: String): AiCrossValidation? {
        val cached = cache[ticker] ?: return null
        return if (System.currentTimeMillis() - cached.timestamp < CACHE_TTL_MS) cached else {
            cache.remove(ticker)
            null
        }
    }

    /**
     * Cross-validate a stock recommendation across configured AI engines.
     * @param ticker Stock symbol
     * @param price Current price
     * @param recommendation The app's recommendation (e.g. "STRONG BUY")
     * @param signals Bullish signals list
     * @param warnings Bearish signals list
     * @param levels Key price levels
     * @param sector Stock sector
     */
    suspend fun validate(
        context: Context,
        ticker: String,
        price: Double,
        recommendation: String,
        signals: List<String>,
        warnings: List<String>,
        levels: StockLevels?,
        sector: String?,
        strategies: String? = null
    ): AiCrossValidation {
        // Check cache first
        getCached(ticker)?.let { return it }

        val prompt = buildPrompt(ticker, price, recommendation, signals, warnings, levels, sector, strategies)

        val results = coroutineScope {
            val tasks = mutableListOf<kotlinx.coroutines.Deferred<AiEngineResult>>()

            AiKeyManager.getKey(context, AiKeyManager.KEY_CLAUDE)?.let { key ->
                tasks.add(async(Dispatchers.IO) { callClaude(key, prompt) })
            }
            AiKeyManager.getKey(context, AiKeyManager.KEY_GEMINI)?.let { key ->
                tasks.add(async(Dispatchers.IO) { callGemini(key, prompt) })
            }
            AiKeyManager.getKey(context, AiKeyManager.KEY_CHATGPT)?.let { key ->
                tasks.add(async(Dispatchers.IO) { callChatGPT(key, prompt) })
            }
            AiKeyManager.getKey(context, AiKeyManager.KEY_PERPLEXITY)?.let { key ->
                tasks.add(async(Dispatchers.IO) { callPerplexity(key, prompt) })
            }
            AiKeyManager.getKey(context, AiKeyManager.KEY_GROK)?.let { key ->
                tasks.add(async(Dispatchers.IO) { callGrok(key, prompt) })
            }

            tasks.awaitAll()
        }

        val validation = buildConsensus(ticker, results)
        cache[ticker] = validation
        return validation
    }

    private fun buildPrompt(
        ticker: String, price: Double, recommendation: String,
        signals: List<String>, warnings: List<String>,
        levels: StockLevels?, sector: String?, strategies: String?
    ): String {
        val levelsStr = if (levels != null) {
            val parts = mutableListOf<String>()
            levels.support?.let { parts.add("Support: $${"%.2f".format(it)}") }
            levels.resistance?.let { parts.add("Resistance: $${"%.2f".format(it)}") }
            levels.target?.let { parts.add("Target: $${"%.2f".format(it)}") }
            levels.stopLoss?.let { parts.add("Stop Loss: $${"%.2f".format(it)}") }
            levels.riskReward?.let { parts.add("Risk/Reward: ${"%.1f".format(it)}:1") }
            if (parts.isNotEmpty()) "\nKey Levels: ${parts.joinToString(", ")}" else ""
        } else ""

        val strategyStr = if (!strategies.isNullOrBlank()) "\nStrategies available: $strategies" else ""

        return """You are a stock market analyst. Evaluate this stock recommendation and provide your independent assessment.

Stock: $ticker
Current Price: $${"%.2f".format(price)}
Sector: ${sector ?: "Unknown"}
Our Recommendation: $recommendation

Bullish Signals: ${if (signals.isNotEmpty()) signals.joinToString("; ") else "None"}
Bearish Signals/Warnings: ${if (warnings.isNotEmpty()) warnings.joinToString("; ") else "None"}$levelsStr$strategyStr

Respond in EXACTLY this JSON format and nothing else:
{"verdict":"BUY or SELL or HOLD or AVOID","confidence":"High or Medium or Low","reasoning":"One or two sentences explaining your assessment"}"""
    }

    // -------- Claude (Anthropic) --------
    private fun callClaude(apiKey: String, prompt: String): AiEngineResult {
        return try {
            val body = gson.toJson(mapOf(
                "model" to "claude-sonnet-4-20250514",
                "max_tokens" to 200,
                "messages" to listOf(mapOf("role" to "user", "content" to prompt))
            ))
            val request = Request.Builder()
                .url("https://api.anthropic.com/v1/messages")
                .addHeader("x-api-key", apiKey)
                .addHeader("anthropic-version", "2023-06-01")
                .addHeader("content-type", "application/json")
                .post(body.toRequestBody(JSON_TYPE))
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                return AiEngineResult("Claude", "N/A", "N/A", "", error = "HTTP ${response.code}: ${responseBody.take(200)}")
            }
            parseClaudeResponse(responseBody)
        } catch (e: Exception) {
            Log.e(TAG, "Claude call failed", e)
            AiEngineResult("Claude", "N/A", "N/A", "", error = e.message ?: "Unknown error")
        }
    }

    private fun parseClaudeResponse(body: String): AiEngineResult {
        return try {
            val map = gson.fromJson(body, Map::class.java)
            val content = (map["content"] as? List<*>)?.firstOrNull() as? Map<*, *>
            val text = (content?.get("text") as? String) ?: ""
            parseAiJson("Claude", text)
        } catch (e: Exception) {
            AiEngineResult("Claude", "N/A", "N/A", "", error = "Parse error: ${e.message}")
        }
    }

    // -------- Google Gemini --------
    private fun callGemini(apiKey: String, prompt: String): AiEngineResult {
        // Try gemini-2.0-flash first; fall back to gemini-1.5-flash if the model is unavailable
        val models = listOf("gemini-2.0-flash", "gemini-1.5-flash")
        var lastError = ""
        for (model in models) {
            try {
                val body = gson.toJson(mapOf(
                    "contents" to listOf(mapOf(
                        "parts" to listOf(mapOf("text" to prompt))
                    )),
                    "generationConfig" to mapOf("maxOutputTokens" to 300)
                ))
                val request = Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey")
                    .addHeader("content-type", "application/json")
                    .post(body.toRequestBody(JSON_TYPE))
                    .build()

                val response = httpClient.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    lastError = "HTTP ${response.code} ($model): ${responseBody.take(300)}"
                    Log.w(TAG, "Gemini model $model failed: $lastError")
                    // 404 = model not found → try next; 400/401/403 = auth/key issue → stop
                    if (response.code in listOf(400, 401, 403)) {
                        return AiEngineResult("Gemini", "N/A", "N/A", "", error = lastError)
                    }
                    continue
                }
                return parseGeminiResponse(responseBody)
            } catch (e: Exception) {
                lastError = e.message ?: "Unknown error"
                Log.e(TAG, "Gemini call failed ($model)", e)
            }
        }
        return AiEngineResult("Gemini", "N/A", "N/A", "", error = lastError)
    }

    private fun parseGeminiResponse(body: String): AiEngineResult {
        return try {
            val map = gson.fromJson(body, Map::class.java)
            val candidates = map["candidates"] as? List<*>
            val content = (candidates?.firstOrNull() as? Map<*, *>)?.get("content") as? Map<*, *>
            val parts = content?.get("parts") as? List<*>
            val text = (parts?.firstOrNull() as? Map<*, *>)?.get("text") as? String ?: ""
            parseAiJson("Gemini", text)
        } catch (e: Exception) {
            AiEngineResult("Gemini", "N/A", "N/A", "", error = "Parse error: ${e.message}")
        }
    }

    // -------- OpenAI ChatGPT --------
    private fun callChatGPT(apiKey: String, prompt: String): AiEngineResult {
        return try {
            val body = gson.toJson(mapOf(
                "model" to "gpt-4o-mini",
                "messages" to listOf(mapOf("role" to "user", "content" to prompt)),
                "max_tokens" to 200
            ))
            val request = Request.Builder()
                .url("https://api.openai.com/v1/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("content-type", "application/json")
                .post(body.toRequestBody(JSON_TYPE))
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                return AiEngineResult("ChatGPT", "N/A", "N/A", "", error = "HTTP ${response.code}: ${responseBody.take(200)}")
            }
            parseChatGPTResponse(responseBody)
        } catch (e: Exception) {
            Log.e(TAG, "ChatGPT call failed", e)
            AiEngineResult("ChatGPT", "N/A", "N/A", "", error = e.message ?: "Unknown error")
        }
    }

    private fun parseChatGPTResponse(body: String): AiEngineResult {
        return try {
            val map = gson.fromJson(body, Map::class.java)
            val choices = map["choices"] as? List<*>
            val message = (choices?.firstOrNull() as? Map<*, *>)?.get("message") as? Map<*, *>
            val text = message?.get("content") as? String ?: ""
            parseAiJson("ChatGPT", text)
        } catch (e: Exception) {
            AiEngineResult("ChatGPT", "N/A", "N/A", "", error = "Parse error: ${e.message}")
        }
    }

    // -------- Perplexity --------
    private fun callPerplexity(apiKey: String, prompt: String): AiEngineResult {
        return try {
            val body = gson.toJson(mapOf(
                "model" to "sonar",
                "messages" to listOf(mapOf("role" to "user", "content" to prompt)),
                "max_tokens" to 200
            ))
            val request = Request.Builder()
                .url("https://api.perplexity.ai/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("content-type", "application/json")
                .post(body.toRequestBody(JSON_TYPE))
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                return AiEngineResult("Perplexity", "N/A", "N/A", "", error = "HTTP ${response.code}: ${responseBody.take(200)}")
            }
            parsePerplexityResponse(responseBody)
        } catch (e: Exception) {
            Log.e(TAG, "Perplexity call failed", e)
            AiEngineResult("Perplexity", "N/A", "N/A", "", error = e.message ?: "Unknown error")
        }
    }

    private fun parsePerplexityResponse(body: String): AiEngineResult {
        // Perplexity uses same format as OpenAI
        return try {
            val map = gson.fromJson(body, Map::class.java)
            val choices = map["choices"] as? List<*>
            val message = (choices?.firstOrNull() as? Map<*, *>)?.get("message") as? Map<*, *>
            val text = message?.get("content") as? String ?: ""
            parseAiJson("Perplexity", text)
        } catch (e: Exception) {
            AiEngineResult("Perplexity", "N/A", "N/A", "", error = "Parse error: ${e.message}")
        }
    }

    // -------- Grok (xAI) --------
    private fun callGrok(apiKey: String, prompt: String): AiEngineResult {
        return try {
            val body = gson.toJson(mapOf(
                "model" to "grok-3-mini-fast",
                "messages" to listOf(mapOf("role" to "user", "content" to prompt)),
                "max_tokens" to 200
            ))
            val request = Request.Builder()
                .url("https://api.x.ai/v1/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("content-type", "application/json")
                .post(body.toRequestBody(JSON_TYPE))
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                return AiEngineResult("Grok", "N/A", "N/A", "", error = "HTTP ${response.code}: ${responseBody.take(200)}")
            }
            parseGrokResponse(responseBody)
        } catch (e: Exception) {
            Log.e(TAG, "Grok call failed", e)
            AiEngineResult("Grok", "N/A", "N/A", "", error = e.message ?: "Unknown error")
        }
    }

    private fun parseGrokResponse(body: String): AiEngineResult {
        // Grok uses OpenAI-compatible format
        return try {
            val map = gson.fromJson(body, Map::class.java)
            val choices = map["choices"] as? List<*>
            val message = (choices?.firstOrNull() as? Map<*, *>)?.get("message") as? Map<*, *>
            val text = message?.get("content") as? String ?: ""
            parseAiJson("Grok", text)
        } catch (e: Exception) {
            AiEngineResult("Grok", "N/A", "N/A", "", error = "Parse error: ${e.message}")
        }
    }

    // -------- Common JSON parser for AI responses --------
    private fun parseAiJson(engine: String, text: String): AiEngineResult {
        // Extract JSON from response (may have markdown or text around it)
        val jsonMatch = Regex("""\{[^{}]*"verdict"[^{}]*\}""", RegexOption.DOT_MATCHES_ALL).find(text)
        val jsonStr = jsonMatch?.value ?: text.trim()
        return try {
            val parsed = gson.fromJson(jsonStr, Map::class.java)
            AiEngineResult(
                engine = engine,
                verdict = (parsed["verdict"] as? String)?.uppercase()?.trim() ?: "N/A",
                confidence = (parsed["confidence"] as? String)?.trim() ?: "N/A",
                reasoning = (parsed["reasoning"] as? String)?.trim() ?: ""
            )
        } catch (e: Exception) {
            // Try to extract verdict/reasoning from plain text
            val verdictMatch = Regex("""(?i)(BUY|SELL|HOLD|AVOID)""").find(text)
            AiEngineResult(
                engine = engine,
                verdict = verdictMatch?.value?.uppercase() ?: "N/A",
                confidence = "Low",
                reasoning = text.take(200).trim()
            )
        }
    }

    // -------- Consensus builder --------
    private fun buildConsensus(ticker: String, results: List<AiEngineResult>): AiCrossValidation {
        val successful = results.filter { it.error == null && it.verdict != "N/A" }
        val total = results.size

        if (successful.isEmpty()) {
            return AiCrossValidation(
                ticker = ticker,
                engines = results,
                consensus = "UNAVAILABLE",
                agreementPct = 0,
                summary = "AI cross-validation failed — check API keys"
            )
        }

        // Count verdicts
        val verdictCounts = successful.groupBy { normalizeVerdict(it.verdict) }
            .mapValues { it.value.size }
            .toList()
            .sortedByDescending { it.second }

        val topVerdict = verdictCounts.first().first
        val topCount = verdictCounts.first().second
        val agreement = (topCount * 100) / successful.size

        // Determine consensus
        val consensus = when {
            agreement >= 75 && (topVerdict == "BUY") -> "STRONG BUY"
            agreement >= 75 -> topVerdict
            agreement >= 50 -> topVerdict
            else -> "MIXED"
        }

        // Weight by confidence
        val highConfBuys = successful.count { normalizeVerdict(it.verdict) == "BUY" && it.confidence.equals("High", true) }
        val adjustedConsensus = if (consensus == "BUY" && highConfBuys >= (successful.size * 0.75)) "STRONG BUY" else consensus

        val engineNames = successful.joinToString(", ") { it.engine }
        val summary = "$topCount/${successful.size} AI engines ($engineNames) rate $ticker as $adjustedConsensus" +
                if (results.size > successful.size) " (${results.size - successful.size} engine(s) unavailable)" else ""

        return AiCrossValidation(
            ticker = ticker,
            engines = results,
            consensus = adjustedConsensus,
            agreementPct = agreement,
            summary = summary
        )
    }

    private fun normalizeVerdict(verdict: String): String = when {
        verdict.contains("STRONG", true) && verdict.contains("BUY", true) -> "BUY"
        verdict.contains("BUY", true) -> "BUY"
        verdict.contains("SELL", true) -> "SELL"
        verdict.contains("HOLD", true) -> "HOLD"
        verdict.contains("AVOID", true) -> "AVOID"
        else -> verdict.uppercase()
    }

    fun clearCache() {
        cache.clear()
    }
}


// ============================================================
// Gemini Gate — pre-flight sanity check before recommending
// ============================================================
/**
 * Single-engine, pre-flight gate that runs every backend recommendation
 * through Gemini before it reaches the user. The user explicitly asked for
 * Gemini to be the gatekeeper because their Gemini Advanced subscription
 * gives them generous Gemini API quota.
 *
 * Decisions:
 *   - APPROVE     : pass the recommendation through unchanged
 *   - VETO        : drop the recommendation entirely (do NOT show the user)
 *   - UNAVAILABLE : Gemini key not configured OR API call failed -> fall
 *                   back to backend recommendation (fail-open, never blocks
 *                   the user when AI is offline).
 *
 * The gate caches per-ticker decisions for 30 minutes so a single daily run
 * issues at most one Gemini call per ticker (free tier handles this easily:
 * 15 RPM, 1500 RPD).
 */
object GeminiGate {
    private const val TAG = "GeminiGate"
    private val gson = Gson()
    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
    private const val CACHE_TTL_MS = 30 * 60 * 1000L

    enum class Decision { APPROVE, VETO, UNAVAILABLE }

    data class Result(
        val ticker: String,
        val decision: Decision,
        val reasoning: String,
        val timestamp: Long = System.currentTimeMillis()
    ) {
        val approved: Boolean get() = decision == Decision.APPROVE || decision == Decision.UNAVAILABLE
        val vetoed: Boolean get() = decision == Decision.VETO
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val cache = mutableMapOf<String, Result>()

    fun cachedFor(ticker: String): Result? {
        val r = cache[ticker.uppercase()] ?: return null
        return if (System.currentTimeMillis() - r.timestamp < CACHE_TTL_MS) r else {
            cache.remove(ticker.uppercase()); null
        }
    }

    fun isEnabled(context: Context): Boolean =
        AiKeyManager.getKey(context, AiKeyManager.KEY_GEMINI) != null

    /**
     * Gate a single recommendation. Always returns a Result; never throws.
     * If [strategy] is null we describe the underlying stock only; otherwise
     * we name the strategy (CSP / Vertical / etc.) in the prompt.
     */
    suspend fun gate(
        context: Context,
        item: ScanResultItem,
        strategy: String? = null
    ): Result = withContext(Dispatchers.IO) {
        val key = AiKeyManager.getKey(context, AiKeyManager.KEY_GEMINI)
            ?: return@withContext Result(item.ticker, Decision.UNAVAILABLE, "Gemini key not configured")

        cachedFor(item.ticker)?.let { return@withContext it }

        val prompt = buildGatePrompt(item, strategy)
        val parsed = callGemini(key, prompt)
        val result = Result(
            ticker = item.ticker,
            decision = parsed.first,
            reasoning = parsed.second
        )
        cache[item.ticker.uppercase()] = result
        result
    }

    /**
     * Gate many recommendations concurrently. Returns a map keyed by upper-case
     * ticker. Tickers with no Gemini decision (UNAVAILABLE) are still in the
     * map so callers can distinguish "skipped" from "vetoed".
     */
    suspend fun gateAll(
        context: Context,
        items: List<ScanResultItem>,
        strategy: String? = null
    ): Map<String, Result> = coroutineScope {
        if (items.isEmpty()) return@coroutineScope emptyMap()
        items.distinctBy { it.ticker.uppercase() }
            .map { item -> async { gate(context, item, strategy) } }
            .awaitAll()
            .associateBy { it.ticker.uppercase() }
    }

    internal fun buildGatePrompt(item: ScanResultItem, strategy: String?): String {
        val rec = item.stockRecommendation ?: item.overall ?: "BUY"
        val rsi = item.rsi?.let { "RSI ${"%.0f".format(it)}" } ?: "RSI unknown"
        val sector = item.sector?.takeIf { it.isNotBlank() } ?: "Unknown"
        val analyst = item.analystTarget?.upsidePct?.let { "Analyst upside ${"%.1f".format(it)}%" }
        val bull = item.bullishSignals?.take(6)?.joinToString("; ") ?: "None listed"
        val bear = item.bearishSignals?.take(6)?.joinToString("; ") ?: "None listed"
        val strategyLine = if (strategy != null) "Strategy on offer: $strategy\n" else ""

        // Backend-derived market data — Gemini does NOT have live quotes or
        // option chains, so we ship every relevant feature the backend
        // already computed for this ticker. This keeps Gemini's reasoning
        // grounded in the actual numbers that drive the recommendation.
        val priceCtx = buildString {
            item.sma50?.let { append(" · SMA50 \$${"%.2f".format(it)}") }
            item.sma200?.let { append(" · SMA200 \$${"%.2f".format(it)}") }
            item.beta?.let { append(" · β ${"%.2f".format(it)}") }
            item.ivRank?.let { append(" · IVR $it") }
            item.discountFromHigh?.let { append(" · off-high $it") }
            item.changePercent?.let { append(" · today ${"%+.2f".format(it)}%") }
        }
        val levels = item.levels?.let { lv ->
            val parts = mutableListOf<String>()
            lv.support?.let { parts += "support \$${"%.2f".format(it)}" }
            lv.resistance?.let { parts += "resistance \$${"%.2f".format(it)}" }
            lv.swingLow60d?.let { parts += "60d-low \$${"%.2f".format(it)}" }
            lv.swingHigh60d?.let { parts += "60d-high \$${"%.2f".format(it)}" }
            lv.high52w?.let { parts += "52w-high \$${"%.2f".format(it)}" }
            lv.atr?.let { parts += "ATR \$${"%.2f".format(it)}" }
            lv.stopLoss?.let { parts += "stop \$${"%.2f".format(it)}" }
            lv.target?.let { parts += "target \$${"%.2f".format(it)}" }
            lv.riskReward?.let { parts += "R:R ${"%.2f".format(it)}" }
            if (parts.isEmpty()) "" else "Levels: " + parts.joinToString(" · ") + "\n"
        }.orEmpty()
        val earningsLine = item.nextEarningsDate?.takeIf { it.isNotBlank() }
            ?.let { "Next earnings: $it\n" }.orEmpty()

        // Compact option-chain summary — backend already filtered to the
        // best 1-2 contracts per strategy, so this is small (< 300 tokens).
        val chain = buildString {
            item.csps?.take(2)?.forEachIndexed { i, c ->
                val exp = c.expiry?.let { " exp $it" } ?: ""
                append("  CSP#${i + 1}: \$${"%.2f".format(c.strike)} strike, \$${"%.2f".format(c.premium)} prem, Δ${"%.2f".format(c.delta)}$exp")
                c.bt?.let { append(", BT $it") }
                c.roc?.let { append(", ROC $it") }
                append("\n")
            }
            item.longLeaps?.take(2)?.forEachIndexed { i, l ->
                val exp = l.expiry.let { " exp $it" }
                append("  LEAP#${i + 1}: \$${"%.2f".format(l.strike)} strike, \$${"%.2f".format(l.premium)} prem, Δ${"%.2f".format(l.delta)}$exp")
                l.bt?.let { append(", BT $it") }
                l.leverage?.let { append(", lev $it") }
                append("\n")
            }
            item.diagonals?.take(1)?.forEach { d ->
                append("  DIAG: long ${d.longLeg ?: "?"} / short ${d.shortLeg ?: "?"}, debit \$${"%.2f".format(d.netDebt)}")
                d.expiry?.let { append(" exp $it") }
                d.bt?.let { append(", BT $it") }
                d.yieldRatio?.let { append(", yield $it") }
                append("\n")
            }
            item.verticals?.take(1)?.forEach { v ->
                append("  VERT: ${v.strikes ?: "?"}, debit \$${"%.2f".format(v.netDebit)}")
                v.expiry?.let { append(" exp $it") }
                v.bt?.let { append(", BT $it") }
                append("\n")
            }
        }
        val chainLine = if (chain.isNotBlank()) "Backend option chain (live, not estimated):\n$chain" else ""

        return """You are a strict risk gatekeeper for a retail options-trading app. A backend has produced a recommendation; your job is to either APPROVE it for delivery to the user, or VETO it because the trade thesis looks weak, contradicted by the data, or unsuitable for the next 4-12 weeks.

IMPORTANT: You do not have live market access. Use ONLY the backend-supplied data below — do NOT invent prices, premiums, or strikes from memory.

Ticker: ${item.ticker}
Sector: $sector
Price: $${"%.2f".format(item.price)}$priceCtx
Backend recommendation: $rec
$strategyLine$rsi${analyst?.let { " · $it" } ?: ""}
$earningsLine$levels$chainLine
Bullish signals: $bull
Bearish/risk signals: $bear

Decision rules:
- VETO if the bearish signals materially undermine the bullish thesis (e.g. confirmed downtrend, deteriorating fundamentals, RSI overbought combined with negative momentum, sector lagging, etc.).
- VETO if the recommendation is BUY-side but RSI > 78 (overbought) without an offsetting catalyst.
- VETO if backend backtest score (BT) < 60% on every contract shown.
- VETO if price is within 1 ATR of resistance / 52w-high AND the strategy is bullish breakout-dependent.
- VETO if next earnings is within 7 days AND the strategy expiry straddles earnings (option IV crush risk).
- APPROVE otherwise — even if you have minor reservations.

Respond with ONLY a single JSON object on one line, no markdown, no commentary:
{"decision":"APPROVE or VETO","reasoning":"one sentence of 25 words or fewer"}"""
    }

    /** Returns (Decision, reasoning). On any failure returns (UNAVAILABLE, errorMessage). */
    private fun callGemini(apiKey: String, prompt: String): Pair<Decision, String> {
        val models = listOf("gemini-2.0-flash", "gemini-1.5-flash")
        var lastErr = "no response"
        for (model in models) {
            try {
                val body = gson.toJson(mapOf(
                    "contents" to listOf(mapOf("parts" to listOf(mapOf("text" to prompt)))),
                    "generationConfig" to mapOf(
                        "maxOutputTokens" to 120,
                        "temperature" to 0.2
                    )
                ))
                val req = Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey")
                    .addHeader("content-type", "application/json")
                    .post(body.toRequestBody(JSON_TYPE))
                    .build()
                val resp = httpClient.newCall(req).execute()
                val raw = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    lastErr = "HTTP ${resp.code} ($model)"
                    Log.w(TAG, "$lastErr: ${raw.take(200)}")
                    if (resp.code in listOf(400, 401, 403)) break  // auth/key failure -> stop trying other models
                    continue
                }
                return parseDecision(raw)
            } catch (e: Exception) {
                lastErr = e.message ?: "unknown"
                Log.e(TAG, "Gemini gate call failed ($model)", e)
            }
        }
        return Decision.UNAVAILABLE to lastErr
    }

    internal fun parseDecision(geminiBody: String): Pair<Decision, String> {
        val text = try {
            val map = gson.fromJson(geminiBody, Map::class.java)
            val candidates = map["candidates"] as? List<*>
            val content = (candidates?.firstOrNull() as? Map<*, *>)?.get("content") as? Map<*, *>
            val parts = content?.get("parts") as? List<*>
            (parts?.firstOrNull() as? Map<*, *>)?.get("text") as? String ?: ""
        } catch (e: Exception) {
            return Decision.UNAVAILABLE to "Parse error: ${e.message}"
        }
        if (text.isBlank()) return Decision.UNAVAILABLE to "Empty Gemini response"

        // Try to pull a JSON object out of the response.
        val jsonMatch = Regex("""\{[^{}]*"decision"[^{}]*\}""", RegexOption.DOT_MATCHES_ALL).find(text)
        if (jsonMatch != null) {
            try {
                val obj = gson.fromJson(jsonMatch.value, Map::class.java)
                val decisionStr = (obj["decision"] as? String)?.trim()?.uppercase().orEmpty()
                val reasoning = (obj["reasoning"] as? String)?.trim().orEmpty()
                val decision = when {
                    decisionStr.contains("VETO") -> Decision.VETO
                    decisionStr.contains("APPROVE") -> Decision.APPROVE
                    else -> Decision.UNAVAILABLE
                }
                return decision to reasoning.ifBlank { decisionStr }
            } catch (_: Exception) { /* fall through */ }
        }
        // Fallback: keyword scan.
        return when {
            Regex("""\bVETO\b""", RegexOption.IGNORE_CASE).containsMatchIn(text) ->
                Decision.VETO to text.take(160).trim()
            Regex("""\bAPPROVE\b""", RegexOption.IGNORE_CASE).containsMatchIn(text) ->
                Decision.APPROVE to text.take(160).trim()
            else -> Decision.UNAVAILABLE to text.take(160).trim()
        }
    }

    fun clearCache() = cache.clear()
}


// ============================================================
// Gemini Advisor — independent watchlist ranker
// ============================================================
/**
 * Companion to [GeminiGate]. Where the gate is reactive (vetoes a backend
 * pick), the advisor is *proactive*: we hand Gemini the entire scan universe
 * (or a watchlist) along with a compact set of features per ticker and ask
 * it to nominate its own top picks for the next 4–12 weeks. Two uses:
 *
 *   1. Cross-reference  -> when a Gemini pick also appears in our backend
 *                          recommendation list we mark it ⭐ as high
 *                          conviction (two independent processes agreed).
 *   2. Discovery        -> Gemini picks the backend missed are surfaced as
 *                          "🤖 Gemini also flagged" so the user sees
 *                          ideas we wouldn't otherwise show.
 *
 * Cached for 60 minutes per universe-hash to limit token spend.
 */
object GeminiAdvisor {
    private const val TAG = "GeminiAdvisor"
    private val gson = Gson()
    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
    private const val CACHE_TTL_MS = 60 * 60 * 1000L
    // Hard cap so we never ship a 50 KB prompt; Gemini-flash handles ~30k tokens
    // in but free-tier latency suffers above ~150 tickers.
    private const val MAX_TICKERS_IN_PROMPT = 150

    data class Pick(
        val ticker: String,
        val rank: Int,           // 1-based
        val conviction: String,  // HIGH / MEDIUM / LOW
        val thesis: String       // one-sentence reasoning
    )

    data class Result(
        val picks: List<Pick>,
        val available: Boolean,
        val error: String? = null,
        val timestamp: Long = System.currentTimeMillis()
    )

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    private val cache = mutableMapOf<String, Result>()

    fun isEnabled(context: Context): Boolean =
        AiKeyManager.getKey(context, AiKeyManager.KEY_GEMINI) != null

    /**
     * Ask Gemini to pick the top [topN] tickers from [items] for the next
     * 4–12 weeks. Returns an empty Result with available=false when no key
     * is configured or the API call fails (so the caller falls back to the
     * backend recommendation untouched).
     */
    suspend fun rankUniverse(
        context: Context,
        items: List<ScanResultItem>,
        topN: Int = 5
    ): Result = withContext(Dispatchers.IO) {
        val key = AiKeyManager.getKey(context, AiKeyManager.KEY_GEMINI)
            ?: return@withContext Result(emptyList(), available = false, error = "Gemini key not configured")
        if (items.isEmpty()) return@withContext Result(emptyList(), available = true)

        // Trim & deterministically order so cache key is stable.
        val trimmed = items
            .distinctBy { it.ticker.uppercase() }
            .sortedBy { it.ticker.uppercase() }
            .take(MAX_TICKERS_IN_PROMPT)

        val cacheKey = "${topN}|" + trimmed.joinToString(",") { it.ticker.uppercase() }
        cache[cacheKey]?.let { cached ->
            if (System.currentTimeMillis() - cached.timestamp < CACHE_TTL_MS) return@withContext cached
            cache.remove(cacheKey)
        }

        val prompt = buildAdvisorPrompt(trimmed, topN)
        val parsed = callGemini(key, prompt)
        val result = if (parsed.first != null) {
            Result(picks = parsed.first!!.take(topN), available = true)
        } else {
            Result(emptyList(), available = false, error = parsed.second)
        }
        if (result.available) cache[cacheKey] = result
        result
    }

    internal fun buildAdvisorPrompt(items: List<ScanResultItem>, topN: Int): String {
        val rows = items.joinToString("\n\n") { item ->
            val rsi = item.rsi?.let { "%.0f".format(it) } ?: "?"
            val rec = item.stockRecommendation ?: item.overall ?: "-"
            val sec = item.sector?.takeIf { it.isNotBlank() } ?: "-"
            val chg = item.changePercent?.let { "%+.1f%%".format(it) } ?: "-"
            val upside = item.analystTarget?.upsidePct?.let { "%+.0f%%".format(it) } ?: "-"
            val bull = item.bullishSignals?.size ?: 0
            val bear = item.bearishSignals?.size ?: 0
            val ivr = item.ivRank?.let { "|ivr=$it" } ?: ""
            val off = item.discountFromHigh?.let { "|off=$it" } ?: ""
            val sma = buildString {
                item.sma50?.let { append("|sma50=\$${"%.0f".format(it)}") }
                item.sma200?.let { append("|sma200=\$${"%.0f".format(it)}") }
            }
            val earn = item.nextEarningsDate?.takeIf { it.isNotBlank() }?.let { "|earn=$it" } ?: ""

            // Compact extras line — best CSP/LEAP and key levels — keeps
            // Gemini grounded in real backend numbers (no live API access).
            val extras = mutableListOf<String>()
            item.csps?.firstOrNull()?.let { c ->
                val exp = c.expiry?.let { " exp=$it" } ?: ""
                val bt = c.bt?.let { " bt=$it" } ?: ""
                extras += "csp:\$${"%.0f".format(c.strike)}@\$${"%.2f".format(c.premium)} Δ${"%.2f".format(c.delta)}$exp$bt"
            }
            item.longLeaps?.firstOrNull()?.let { l ->
                val bt = l.bt?.let { " bt=$it" } ?: ""
                extras += "leap:\$${"%.0f".format(l.strike)}@\$${"%.2f".format(l.premium)} exp=${l.expiry}$bt"
            }
            item.diagonals?.firstOrNull()?.let { d ->
                val bt = d.bt?.let { " bt=$it" } ?: ""
                extras += "diag:debit\$${"%.2f".format(d.netDebt)}$bt"
            }
            item.verticals?.firstOrNull()?.let { v ->
                val bt = v.bt?.let { " bt=$it" } ?: ""
                extras += "vert:debit\$${"%.2f".format(v.netDebit)}$bt"
            }
            item.levels?.let { lv ->
                val lvParts = mutableListOf<String>()
                lv.support?.let { lvParts += "sup=\$${"%.0f".format(it)}" }
                lv.resistance?.let { lvParts += "res=\$${"%.0f".format(it)}" }
                lv.atr?.let { lvParts += "atr=\$${"%.2f".format(it)}" }
                lv.high52w?.let { lvParts += "52wH=\$${"%.0f".format(it)}" }
                if (lvParts.isNotEmpty()) extras += "lv:" + lvParts.joinToString(",")
            }

            val mainLine = "${item.ticker}|\$${"%.2f".format(item.price)}|RSI=$rsi|chg=$chg|sec=$sec|rec=$rec|up=$upside|+$bull/-$bear$ivr$off$sma$earn"
            if (extras.isEmpty()) mainLine else mainLine + "\n  " + extras.joinToString(" · ")
        }

        return """You are an independent equities analyst picking for a retail options-trading app. From the table below, choose the TOP $topN tickers most likely to outperform over the next 4–12 weeks. Treat this as your own selection — the "rec" column is just one input from another model.

IMPORTANT: You do not have live market access. Use ONLY the data below — do NOT invent prices, premiums, strikes, or fundamentals from training-data memory. Every figure (price, RSI, IV rank, option strikes/premiums, support/resistance, 52-week high) is the backend's live snapshot.

Each row format (line 1): TICKER|PRICE|RSI|chg=DAILY%|sec=SECTOR|rec=BACKEND_REC|up=ANALYST_UPSIDE|+BULLISH/-BEARISH|ivr=IV_RANK|off=OFF_HIGH|sma50/sma200|earn=NEXT_EARNINGS
Each row format (line 2, optional): csp/leap/diag/vert option chain summary · lv:support/resistance/ATR/52w-high

$rows

Selection rules:
- Prefer constructive trend (price > 0) with RSI in 35–75 (avoid overbought >78 and capitulation <30).
- Prefer positive analyst upside and more bullish than bearish signals.
- Prefer high IV-rank when CSP premium is the income source (good for sellers).
- Prefer price meaningfully below 52-week high but above support (room to run).
- Avoid tickers with earnings inside the option expiry window (IV crush risk).
- Diversify across sectors when possible.
- Do NOT just copy the backend rec column; you should be willing to disagree.

Respond with ONLY a single JSON object on one line, no markdown:
{"picks":[{"ticker":"AAA","rank":1,"conviction":"HIGH or MEDIUM or LOW","thesis":"<= 20 words referencing concrete numbers from the data above"}, ...]}"""
    }

    /** Returns (parsed picks or null, error message). */
    private fun callGemini(apiKey: String, prompt: String): Pair<List<Pick>?, String> {
        val models = listOf("gemini-2.0-flash", "gemini-1.5-flash")
        var lastErr = "no response"
        for (model in models) {
            try {
                val body = gson.toJson(mapOf(
                    "contents" to listOf(mapOf("parts" to listOf(mapOf("text" to prompt)))),
                    "generationConfig" to mapOf(
                        "maxOutputTokens" to 800,
                        "temperature" to 0.3
                    )
                ))
                val req = Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey")
                    .addHeader("content-type", "application/json")
                    .post(body.toRequestBody(JSON_TYPE))
                    .build()
                val resp = httpClient.newCall(req).execute()
                val raw = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    lastErr = "HTTP ${resp.code} ($model)"
                    Log.w(TAG, "$lastErr: ${raw.take(200)}")
                    if (resp.code in listOf(400, 401, 403)) break
                    continue
                }
                val picks = parsePicks(raw)
                if (picks != null) return picks to ""
                lastErr = "Could not parse picks from response"
            } catch (e: Exception) {
                lastErr = e.message ?: "unknown"
                Log.e(TAG, "Gemini advisor call failed ($model)", e)
            }
        }
        return null to lastErr
    }

    internal fun parsePicks(geminiBody: String): List<Pick>? {
        val text = try {
            val map = gson.fromJson(geminiBody, Map::class.java)
            val candidates = map["candidates"] as? List<*>
            val content = (candidates?.firstOrNull() as? Map<*, *>)?.get("content") as? Map<*, *>
            val parts = content?.get("parts") as? List<*>
            (parts?.firstOrNull() as? Map<*, *>)?.get("text") as? String ?: ""
        } catch (e: Exception) {
            Log.w(TAG, "advisor wrapper parse failed: ${e.message}")
            return null
        }
        if (text.isBlank()) return null

        val jsonMatch = Regex("""\{[^{}]*"picks"\s*:\s*\[.*?\][^{}]*\}""", RegexOption.DOT_MATCHES_ALL).find(text)
            ?: Regex("""\[\s*\{.*?"ticker".*?\}\s*\]""", RegexOption.DOT_MATCHES_ALL).find(text)
            ?: return null
        val payload = jsonMatch.value
        return try {
            // payload may be either {"picks":[...]} or just [...]
            val list: List<*> = if (payload.trimStart().startsWith("[")) {
                gson.fromJson(payload, List::class.java)
            } else {
                val obj = gson.fromJson(payload, Map::class.java)
                (obj["picks"] as? List<*>) ?: return null
            }
            list.mapIndexedNotNull { idx, raw ->
                val m = raw as? Map<*, *> ?: return@mapIndexedNotNull null
                val ticker = (m["ticker"] as? String)?.trim()?.uppercase() ?: return@mapIndexedNotNull null
                if (ticker.isBlank()) return@mapIndexedNotNull null
                val rank = (m["rank"] as? Number)?.toInt() ?: (idx + 1)
                val convRaw = (m["conviction"] as? String)?.trim()?.uppercase().orEmpty()
                val conviction = when {
                    convRaw.contains("HIGH") -> "HIGH"
                    convRaw.contains("MED") -> "MEDIUM"
                    convRaw.contains("LOW") -> "LOW"
                    else -> "MEDIUM"
                }
                val thesis = (m["thesis"] as? String)?.trim().orEmpty()
                Pick(ticker, rank, conviction, thesis)
            }.distinctBy { it.ticker }.sortedBy { it.rank }
        } catch (e: Exception) {
            Log.w(TAG, "advisor JSON parse failed: ${e.message}")
            null
        }
    }

    fun clearCache() = cache.clear()
}


// ============================================================
// Gemini Chat — free-form follow-up queries
// ============================================================
/**
 * Conversational Gemini interface for the in-app chat screen. Unlike
 * [GeminiGate] (single-turn pre-flight check) and [GeminiAdvisor]
 * (single-turn ranker), this maintains full multi-turn conversation
 * history so the user can ask follow-up questions like
 *   "tell me more about NVDA",
 *   "what's the risk on that CSP?",
 *   "compare it to AMD".
 *
 * The caller can optionally seed the conversation with a system context
 * string (e.g. "Today's scan returned: ...") so Gemini reasons about the
 * user's current data instead of generic stock knowledge.
 */
object GeminiChat {
    private const val TAG = "GeminiChat"
    private val gson = Gson()
    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
    // Cap turns we keep in memory so the prompt size stays bounded.
    private const val MAX_TURNS = 16

    enum class Role { USER, MODEL }
    data class Message(val role: Role, val text: String, val timestamp: Long = System.currentTimeMillis())

    sealed class Reply {
        data class Ok(val text: String) : Reply()
        data class Error(val message: String) : Reply()
        data object NoKey : Reply()
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    fun isEnabled(context: Context): Boolean =
        AiKeyManager.getKey(context, AiKeyManager.KEY_GEMINI) != null

    /**
     * Send the next user message and return Gemini's reply. [history] should
     * already contain the entire prior conversation (user + model turns) but
     * NOT the [userMessage] itself. We append it before sending.
     *
     * [systemContext] is optional grounding text prepended as a system
     * instruction (Gemini's `system_instruction` field) — use it to inject
     * the user's current scan results, watchlist, or portfolio so Gemini
     * answers questions about THEIR data instead of memorized facts.
     */
    suspend fun ask(
        context: Context,
        history: List<Message>,
        userMessage: String,
        systemContext: String? = null
    ): Reply = withContext(Dispatchers.IO) {
        val key = AiKeyManager.getKey(context, AiKeyManager.KEY_GEMINI) ?: return@withContext Reply.NoKey
        if (userMessage.isBlank()) return@withContext Reply.Error("Empty message")

        val trimmed = (history + Message(Role.USER, userMessage.trim())).takeLast(MAX_TURNS)
        callGemini(key, trimmed, systemContext)
    }

    internal fun buildRequestBody(history: List<Message>, systemContext: String?): String {
        val contents = history.map { msg ->
            mapOf(
                "role" to (if (msg.role == Role.USER) "user" else "model"),
                "parts" to listOf(mapOf("text" to msg.text))
            )
        }
        val payload = mutableMapOf<String, Any>(
            "contents" to contents,
            "generationConfig" to mapOf(
                "maxOutputTokens" to 600,
                "temperature" to 0.4
            )
        )
        if (!systemContext.isNullOrBlank()) {
            payload["system_instruction"] = mapOf(
                "parts" to listOf(mapOf("text" to systemContext))
            )
        }
        return gson.toJson(payload)
    }

    private fun callGemini(apiKey: String, history: List<Message>, systemContext: String?): Reply {
        val models = listOf("gemini-2.0-flash", "gemini-1.5-flash")
        var lastErr = "no response"
        val body = buildRequestBody(history, systemContext)
        for (model in models) {
            try {
                val req = Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey")
                    .addHeader("content-type", "application/json")
                    .post(body.toRequestBody(JSON_TYPE))
                    .build()
                val resp = httpClient.newCall(req).execute()
                val raw = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    lastErr = "HTTP ${resp.code} ($model)"
                    Log.w(TAG, "$lastErr: ${raw.take(200)}")
                    if (resp.code in listOf(400, 401, 403)) break
                    continue
                }
                val text = parseReplyText(raw)
                if (!text.isNullOrBlank()) return Reply.Ok(text)
                lastErr = "Empty Gemini reply"
            } catch (e: Exception) {
                lastErr = e.message ?: "unknown"
                Log.e(TAG, "Gemini chat call failed ($model)", e)
            }
        }
        return Reply.Error(lastErr)
    }

    internal fun parseReplyText(geminiBody: String): String? {
        return try {
            val map = gson.fromJson(geminiBody, Map::class.java)
            val candidates = map["candidates"] as? List<*>
            val content = (candidates?.firstOrNull() as? Map<*, *>)?.get("content") as? Map<*, *>
            val parts = content?.get("parts") as? List<*>
            (parts?.firstOrNull() as? Map<*, *>)?.get("text") as? String
        } catch (e: Exception) {
            Log.w(TAG, "chat reply parse failed: ${e.message}")
            null
        }
    }
}
