package com.echotracks.app.security

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object AppLock {
    private const val PREF = "echo_lock"
    private const val KEY_PIN = "pin"
    private const val KEY_TIMEOUT = "last_bg"

    private fun prefs(ctx: Context) = try {
        EncryptedSharedPreferences.create(
            ctx, PREF, MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (_: Exception) {
        // robust fallback: still locks, just unencrypted (never crash on devices without keystore)
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    }

    fun setPin(ctx: Context, pin: String) { prefs(ctx).edit().putString(KEY_PIN, pin).apply() }
    fun hasPin(ctx: Context) = prefs(ctx).getString(KEY_PIN, null) != null
    fun checkPin(ctx: Context, pin: String) = prefs(ctx).getString(KEY_PIN, null) == pin
    fun canUseBiometric(ctx: Context): Boolean {
        val m = BiometricManager.from(ctx)
        return m.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }
    fun markBackground(ctx: Context) { prefs(ctx).edit().putLong(KEY_TIMEOUT, System.currentTimeMillis()).apply() }
    fun needsLock(ctx: Context, timeoutMin: Int = 2): Boolean {
        val last = prefs(ctx).getLong(KEY_TIMEOUT, 0L)
        return hasPin(ctx) && (System.currentTimeMillis() - last > timeoutMin * 60_000L)
    }
}
