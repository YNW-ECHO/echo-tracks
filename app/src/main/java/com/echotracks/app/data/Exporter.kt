package com.echotracks.app.data

import android.content.Context
import android.os.Environment
import com.echotracks.app.model.EchoTransaction
import java.io.File

// Export to CSV (opens in Excel) + local backup. 100% offline.
object Exporter {
    fun toCsv(txs: List<EchoTransaction>): String {
        val sb = StringBuilder("code,date,who,amount,direction,source,category,spendType\n")
        val f = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
        for (t in txs) {
            val who = "\"${t.who.replace("\"", "\"\"")}\""
            sb.append("${t.code},${f.format(java.util.Date(t.dateMillis))},$who,${t.amount},${t.direction},${t.source},${t.category},${t.spendType}\n")
        }
        return sb.toString()
    }
    fun savePublic(ctx: Context, name: String, csv: String): File {
        // Android 10+: scoped storage — use MediaStore so file actually lands in Downloads
        // and is clickable/openable from Files/Excel. Falls back to legacy + app dir.
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/csv")
                    put(
                        android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                        android.os.Environment.DIRECTORY_DOWNLOADS
                    )
                }
                val uri = ctx.contentResolver.insert(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                )
                if (uri != null) {
                    ctx.contentResolver.openOutputStream(uri)?.use { it.write(csv.toByteArray()) }
                    // return a File handle for the toast message (real bytes live in MediaStore)
                    val fb = File(ctx.getExternalFilesDir(null), name)
                    try { fb.writeText(csv) } catch (_: Exception) {}
                    return fb
                }
            }
        } catch (_: Exception) { }
        val dir = try {
            android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS
            )
        } catch (_: Exception) { ctx.getExternalFilesDir(null) }
        try { dir?.mkdirs() } catch (_: Exception) { }
        val file = File(dir, name)
        try { file.writeText(csv) } catch (_: Exception) {
            val fb = File(ctx.getExternalFilesDir(null), name); fb.writeText(csv); return fb
        }
        return file
    }
}
