package com.echotracks.app.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.echotracks.app.model.Direction
import java.util.concurrent.TimeUnit

// Daily background rescan: reads SMS inbox, caches new TXs in Room,
// notifies only when fresh spend arrived. Offline-safe, never crashes.
class RescanWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result {
        return try {
            val raw = SmsReader.readAll(applicationContext, 2000)
            if (raw.isEmpty()) return Result.success()
            val txs = SmsReader.toTransactions(raw)
            if (txs.isEmpty()) return Result.success()
            val db = EchoDb.get(applicationContext)
            val entities = txs.map {
                EchoEntity(
                    it.code, it.amount, it.who, it.dateMillis,
                    it.source.name, it.direction.name,
                    it.category.name, it.spendType.name, it.rawSms.take(500)
                )
            }
            db.dao().insertAll(entities)
            // notify on fresh OUT spend in last 24h (max 1/day, quiet otherwise)
            val dayAgo = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
            val fresh = txs.count { it.direction == Direction.OUT && it.dateMillis >= dayAgo }
            if (fresh > 0) {
                NotifyHelper.show(
                    applicationContext, "echo_rescan", "Echo Tracks",
                    "$fresh new spend echoes saved."
                )
            }
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "echo_daily_rescan"
        fun schedule(ctx: Context) {
            try {
                val req = PeriodicWorkRequestBuilder<RescanWorker>(24, TimeUnit.HOURS)
                    .addTag(TAG).build()
                WorkManager.getInstance(ctx.applicationContext)
                    .enqueueUniquePeriodicWork(TAG, ExistingPeriodicWorkPolicy.KEEP, req)
            } catch (_: Exception) {}
        }
    }
}
