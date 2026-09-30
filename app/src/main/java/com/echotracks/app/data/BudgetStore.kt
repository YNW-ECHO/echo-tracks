package com.echotracks.app.data

import android.content.Context

// monthly budget goal, plain prefs (not secret) + robust fallback
object BudgetStore {
    private const val PREF = "echo_budget"
    private const val KEY = "monthly_goal"
    fun get(ctx: Context): Double {
        return try {
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getFloat(KEY, 30000f).toDouble()
        } catch (_: Exception) { 30000.0 }
    }
    fun set(ctx: Context, v: Double) {
        try {
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putFloat(KEY, v.toFloat()).apply()
        } catch (_: Exception) {}
    }
}
