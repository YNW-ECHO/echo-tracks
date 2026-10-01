package com.echotracks.app.data

import android.content.Context

// Menu → Settings persistence. Plain prefs, offline, crash-safe.
object AppPrefs {
    private const val PREF = "echo_prefs"

    const val THEME_SYSTEM = "SYSTEM"
    const val THEME_DARK = "DARK"
    const val THEME_LIGHT = "LIGHT"

    const val FONT_DEFAULT = "DEFAULT"
    const val FONT_SERIF = "SERIF"
    const val FONT_MONO = "MONO"

    fun theme(ctx: Context): String = try {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("theme", THEME_DARK) ?: THEME_DARK
    } catch (_: Exception) { THEME_DARK }

    fun setTheme(ctx: Context, v: String) = try {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString("theme", v).apply()
    } catch (_: Exception) { Unit }

    fun font(ctx: Context): String = try {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("font", FONT_DEFAULT) ?: FONT_DEFAULT
    } catch (_: Exception) { FONT_DEFAULT }

    fun setFont(ctx: Context, v: String) = try {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString("font", v).apply()
    } catch (_: Exception) { Unit }

    fun fontScale(ctx: Context): Float = try {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getFloat("font_scale", 1.0f)
    } catch (_: Exception) { 1.0f }

    fun setFontScale(ctx: Context, v: Float) = try {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putFloat("font_scale", v).apply()
    } catch (_: Exception) { Unit }

    fun bioEnabled(ctx: Context): Boolean = try {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean("bio_enabled", true)
    } catch (_: Exception) { true }

    fun setBioEnabled(ctx: Context, v: Boolean) = try {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean("bio_enabled", v).apply()
    } catch (_: Exception) { Unit }

    fun lockTimeoutMin(ctx: Context): Int = try {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getInt("lock_timeout", 2)
    } catch (_: Exception) { 2 }

    fun setLockTimeout(ctx: Context, v: Int) = try {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putInt("lock_timeout", v).apply()
    } catch (_: Exception) { Unit }
}
