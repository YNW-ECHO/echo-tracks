package com.echotracks.app.data

import android.content.Context

// Bill reminders: user-confirmed "paid" dates so next-due learns the real cycle.
// 100% offline (SharedPrefs), crash-safe, survives updates.
object BillStore {
    private const val PREF = "echo_bills"

    fun norm(who: String): String = who.trim().lowercase()

    fun lastPaid(ctx: Context, who: String): Long? = try {
        val v = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getLong("paid_" + norm(who), -1L)
        if (v > 0) v else null
    } catch (_: Exception) { null }

    fun setPaid(ctx: Context, who: String, millis: Long = System.currentTimeMillis()) = try {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putLong("paid_" + norm(who), millis).apply()
    } catch (_: Exception) { Unit }

    fun clearPaid(ctx: Context, who: String) = try {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().remove("paid_" + norm(who)).apply()
    } catch (_: Exception) { Unit }

    fun all(ctx: Context): Map<String, Long> = try {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).all.mapNotNull { (k, v) ->
            if (!k.startsWith("paid_")) null
            else {
                val t = (v as? Long) ?: (v as? Int)?.toLong() ?: return@mapNotNull null
                if (t > 0) k.removePrefix("paid_") to t else null
            }
        }.toMap()
    } catch (_: Exception) { emptyMap() }
}
