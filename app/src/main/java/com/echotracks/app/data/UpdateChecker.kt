package com.echotracks.app.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

// In-app updater: checks GitHub Releases/latest, compares tag (v1.1) vs
// installed versionName. 100% offline-safe: timeouts + try/catch, never crashes.
object UpdateChecker {
    const val REPO = "YNW-ECHO/echo-tracks"
    const val API = "https://api.github.com/repos/YNW-ECHO/echo-tracks/releases/latest"
    const val PREF = "echo_update"
    const val KEY_DISMISSED = "dismissed_tag"

    data class UpdateInfo(
        val tag: String,
        val version: String,
        val url: String,
        val notes: String
    )

    private fun currentVersion(ctx: Context): String {
        return try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "1.0"
        } catch (_: Exception) { "1.0" }
    }

    fun norm(v: String): String = v.trim().removePrefix("v").removePrefix("V")

    // -1 current<latest, 0 equal, 1 current>latest
    fun compare(current: String, latest: String): Int {
        val c = norm(current).split(".", "-", "_")
        val l = norm(latest).split(".", "-", "_")
        val n = maxOf(c.size, l.size)
        for (i in 0 until n) {
            val a = c.getOrNull(i)?.toIntOrNull() ?: 0
            val b = l.getOrNull(i)?.toIntOrNull() ?: 0
            if (a != b) return a.compareTo(b)
        }
        return 0
    }

    /** Network on caller's thread — call from Dispatchers.IO. Returns null = no update. */
    fun check(ctx: Context): UpdateInfo? {
        return try {
            val conn = (URL(API).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000; readTimeout = 8000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "EchoTracks-App")
            }
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val j = JSONObject(body)
            val tag = j.optString("tag_name", "").ifBlank { return null }
            val htmlUrl = j.optString("html_url", "")
            val notes = j.optString("body", "")
            val latest = norm(tag)
            val current = norm(currentVersion(ctx))
            if (latest.isBlank() || compare(current, latest) >= 0) return null
            // user dismissed this exact tag?
            val dismissed = try {
                ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_DISMISSED, null)
            } catch (_: Exception) { null }
            if (dismissed == tag) return null
            // find apk asset url, fallback to release page
            var apkUrl = htmlUrl
            try {
                val assets = j.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val a = assets.getJSONObject(i)
                        val name = a.optString("name", "")
                        if (name.endsWith(".apk", true)) {
                            apkUrl = a.optString("browser_download_url", htmlUrl)
                            break
                        }
                    }
                }
            } catch (_: Exception) {}
            if (apkUrl.isBlank()) apkUrl = htmlUrl
            UpdateInfo(tag, latest, apkUrl, notes.take(2000))
        } catch (_: Exception) { null }
    }

    fun dismiss(ctx: Context, tag: String) {
        try {
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putString(KEY_DISMISSED, tag).apply()
        } catch (_: Exception) {}
    }

    fun openUpdate(ctx: Context, url: String) {
        try {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(i)
        } catch (_: Exception) {}
    }

    fun notify(ctx: Context, info: UpdateInfo) {
        try {
            val chId = "echo_updates"
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel(chId, "Echo Tracks updates", NotificationManager.IMPORTANCE_DEFAULT)
                )
            }
            // Android 13+: need runtime permission, else skip silently
            if (Build.VERSION.SDK_INT >= 33) {
                val ok = try {
                    androidx.core.content.ContextCompat.checkSelfPermission(
                        ctx, android.Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED
                } catch (_: Exception) { false }
                if (!ok) return
            }
            val pi = PendingIntent.getActivity(
                ctx, 7,
                Intent(Intent.ACTION_VIEW, Uri.parse(info.url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val n = NotificationCompat.Builder(ctx, chId)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Echo Tracks ${info.tag} available")
                .setContentText("Tap to download the new version.")
                .setStyle(NotificationCompat.BigTextStyle().bigText("Echo Tracks ${info.tag} is on GitHub. Tap to update."))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(ctx).notify(9001, n)
        } catch (_: Exception) {}
    }
}
