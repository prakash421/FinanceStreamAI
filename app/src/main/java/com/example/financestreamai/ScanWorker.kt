package com.example.financestreamai

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ScanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val sharedPrefs = applicationContext.getSharedPreferences("AlphaStreamPrefs", Context.MODE_PRIVATE)
            val watchlist = sharedPrefs.getString("watchlist", null)?.split(",")?.filter { it.isNotBlank() } ?: PORTFOLIO_WATCHLIST_DEFAULT

            // Using the new apiService and models from MainActivity.kt
            val results = apiService.getScanResults()
            
            // Filter by watchlist and check for CSP (Sell Puts) strategy matches
            val matches = results.filter { item ->
                watchlist.contains(item.ticker) && !item.csps.isNullOrEmpty()
            }

            if (matches.isNotEmpty()) {
                sendNotification(matches)
                Log.d("ScanWorker", "Found ${matches.size} matches. Notifications sent.")
            }

            Result.success()
        } catch (e: Exception) {
            Log.e("ScanWorker", "Error during scheduled scan: ${e.message}")
            Result.retry()
        }
    }

    private fun sendNotification(matches: List<ScanResultItem>) {
        val channelId = "alpha_stream_alerts"
        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Alpha Stream Alerts", NotificationManager.IMPORTANCE_HIGH)
            notificationManager.createNotificationChannel(channel)
        }

        val tickerList = matches.take(5).joinToString(", ") { it.ticker }
        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("New AlphaStream Matches!")
            .setContentText("Found potential trades for: $tickerList")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    }
}
