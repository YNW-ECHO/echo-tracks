package com.echotracks.app.data

import android.content.Context
import com.echotracks.app.model.Category

// Per-category monthly caps. Plain prefs, offline, crash-safe.
object CategoryBudgets {
    private const val PREF = "echo_cat_budget"

    fun get(ctx: Context, c: Category): Double {
        return try {
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getFloat("cap_" + c.name, 0f).toDouble()
        } catch (_: Exception) { 0.0 }
    }

    fun set(ctx: Context, c: Category, v: Double) {
        try {
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putFloat("cap_" + c.name, v.toFloat()).apply()
        } catch (_: Exception) {}
    }

    fun all(ctx: Context): Map<Category, Double> {
        return try {
            val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            Category.values().associateWith { (p.getFloat("cap_" + it.name, 0f)).toDouble() }
        } catch (_: Exception) { emptyMap() }
    }
}
