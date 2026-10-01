package com.echotracks.app.data

import android.content.Context

// Overspend alerts: fires once per month at 80% (warning) and 100% (over).
// Offline-safe, never spams — remembers last fired threshold per calendar month.
object OverspendAlerter {
    private const val PREF = "echo_overspend"
    private const val KEY = "fired_for_month"

    private fun monthKey(): String {
        val c = java.util.Calendar.getInstance()
        return "${c.get(java.util.Calendar.YEAR)}-${c.get(java.util.Calendar.MONTH)}"
    }

    fun check(ctx: Context, monthSpend: Double, goal: Double) {
        try {
            if (goal <= 0 || monthSpend <= 0) return
            val pct = monthSpend / goal
            val level = when {
                pct >= 1.0 -> 100
                pct >= 0.8 -> 80
                else -> return // quiet zone
            }
            val key = monthKey() + "_$level"
            val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            if (p.getString(KEY, null) == key ||
                (level == 80 && p.getString(KEY, null) == monthKey() + "_100")
            ) return // already fired (100 implies 80 seen)
            p.edit().putString(KEY, key).apply()
            if (level >= 100) {
                NotifyHelper.show(
                    ctx, "echo_budget",
                    "⚠ Over budget!",
                    "Spent ${"%,.0f".format(monthSpend)} of ${"%,.0f".format(goal)} — freeze non-essentials.",
                    id = 8101
                )
            } else {
                NotifyHelper.show(
                    ctx, "echo_budget",
                    "Caution: 80% of budget used",
                    "Spent ${"%,.0f".format(monthSpend)} of ${"%,.0f".format(goal)} this month.",
                    id = 8100
                )
            }
        } catch (_: Exception) { }
    }
}
