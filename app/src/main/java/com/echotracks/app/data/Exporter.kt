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
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val file = File(dir, name)
        try { file.writeText(csv) } catch (_: Exception) {
            val fb = File(ctx.getExternalFilesDir(null), name); fb.writeText(csv); return fb
        }
        return file
    }
}
