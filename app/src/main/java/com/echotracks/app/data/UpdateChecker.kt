package com.echotracks.app.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    // ---------- IN-APP UPDATE (download + install, no browser, no manual APK) ----------

    /** Where the update APK is staged (app-private cache, wiped by the OS when needed). */
    fun apkFile(ctx: Context): File {
        val dir = File(ctx.cacheDir, "updates")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "echo-tracks-update.apk")
    }

    /**
     * Download the release APK straight into the app. onProgress(0..100).
     * Returns the staged file, or null if anything went wrong.
     */
    suspend fun download(ctx: Context, url: String, onProgress: suspend (Int) -> Unit): File? =
        withContext(Dispatchers.IO) {
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20000
                    readTimeout = 60000
                    setRequestProperty("User-Agent", "EchoTracks-App")
                    instanceFollowRedirects = true
                }
                if (conn.responseCode !in 200..299) {
                    conn.disconnect()
                    return@withContext null
                }
                val total = conn.contentLengthLong
                val out = apkFile(ctx)
                conn.inputStream.use { input ->
                    out.outputStream().use { o ->
                        val buf = ByteArray(64 * 1024)
                        var read = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            o.write(buf, 0, n)
                            read += n
                            if (total > 0) {
                                onProgress(((read * 100) / total).toInt().coerceIn(0, 99))
                            }
                        }
                    }
                }
                conn.disconnect()
                // a real APK is way bigger than 100 KB — guards against HTML error pages
                if (out.length() < 100_000L) {
                    out.delete()
                    return@withContext null
                }
                onProgress(100)
                out
            } catch (_: Exception) {
                null
            }
        }

    /**
     * Hand the staged APK to the Android installer.
     * Returns true if the installer opened, false if the user still has to allow
     * "install unknown apps" (we open that settings screen for them).
     */
    fun install(ctx: Context, file: File): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                !ctx.packageManager.canRequestPackageInstalls()
            ) {
                val s = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${ctx.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(s)
                return false
            }
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", file)
            val i = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(i)
            true
        } catch (_: Exception) {
            false
        }
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
                Intent(ctx, com.echotracks.app.MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra("open_update", true)
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
