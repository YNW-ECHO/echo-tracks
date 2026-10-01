package com.echotracks.app.data

import android.content.Context
import com.echotracks.app.model.Category

// Teach Echo: user labels one UNCATEGORIZED transaction ("that was food"),
// we remember keyword -> category and auto-apply to all similar ones.
// 100% offline (SharedPrefs), crash-safe, survives updates.
object LearnStore {
    private const val PREF = "echo_learned_cats"

    fun keyFor(who: String, raw: String): String {
        // stable keyword: prefer merchant name, fallback to first meaningful words
        val w = who.trim().lowercase()
        if (w.isNotBlank() && w != "unknown") return w.take(40)
        val words = raw.lowercase()
            .replace(Regex("[^a-z0-9 ]"), " ")
            .split(" ").filter { it.length >= 4 }
            .filterNot { it in setOf("confirmed", "ksh", "received", "sent", "paid", "from", "have") }
        return words.take(3).joinToString(" ").take(40).ifBlank { "unknown" }
    }

    fun get(ctx: Context, key: String): Category? {
        return try {
            val n = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getString("cat_" + key.lowercase(), null) ?: return null
            try { Category.valueOf(n) } catch (_: Exception) { null }
        } catch (_: Exception) { null }
    }

    fun set(ctx: Context, key: String, cat: Category) {
        try {
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putString("cat_" + key.lowercase(), cat.name).apply()
        } catch (_: Exception) { }
    }

    fun all(ctx: Context): Map<String, Category> {
        return try {
            val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            p.all.mapNotNull { (k, v) ->
                if (!k.startsWith("cat_")) null
                else try {
                    k.removePrefix("cat_") to Category.valueOf(v as String)
                } catch (_: Exception) { null }
            }.toMap()
        } catch (_: Exception) { emptyMap() }
    }

    fun count(ctx: Context): Int = try {
        all(ctx).size
    } catch (_: Exception) { 0 }

    /** Apply learned overrides on top of rule-based categories. */
    fun applyLearned(
        ctx: Context,
        who: String, raw: String, current: Category
    ): Category {
        if (current != Category.UNCATEGORIZED) return current
        return try {
            val t = "$who $raw".lowercase()
            val learned = all(ctx)
            for ((k, c) in learned) {
                if (k.isNotBlank() && k in t) return c
            }
            // direct key hit
            get(ctx, keyFor(who, raw)) ?: current
        } catch (_: Exception) { current }
    }
}
