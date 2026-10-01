package com.echotracks.app.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.echotracks.app.model.Direction
import com.echotracks.app.model.EchoTransaction
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// Weekly PDF reports (last 7 days) + Monthly merge (4-5 weekly sections +
// category totals). Uses framework PdfDocument — no extra deps, offline.
object ReportPdf {
    private val dateFmt = SimpleDateFormat("dd MMM yyyy", Locale.US)
    private val dtFmt = SimpleDateFormat("dd MMM HH:mm", Locale.US)

    data class Week(val label: String, val from: Long, val to: Long, val txs: List<EchoTransaction>)

    fun lastWeeks(all: List<EchoTransaction>, n: Int = 5): List<Week> {
        val now = System.currentTimeMillis()
        return (0 until n).map { i ->
            val to = now - i * 7L * 24 * 60 * 60 * 1000
            val from = to - 7L * 24 * 60 * 60 * 1000
            val list = all.filter { it.dateMillis in from..to }.sortedByDescending { it.dateMillis }
            Week("Week ${n - i} • ${dateFmt.format(Date(from))} – ${dateFmt.format(Date(to))}", from, to, list)
        }.reversed()
    }

    private fun drawCentered(page: PdfDocument.Page, y: Float, text: String, size: Float, bold: Boolean, pdfW: Int, paint: Paint): Float {
        paint.textSize = size
        paint.typeface = if (bold) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
        val w = paint.measureText(text)
        page.canvas.drawText(text, (pdfW - w) / 2, y, paint)
        return y + size + 10
    }

    private fun buildDoc(title: String, sections: List<Triple<String, List<EchoTransaction>, Double>>): PdfDocument {
        val doc = PdfDocument()
        val pdfW = 595; val pdfH = 842 // A4 points
        val paint = Paint().apply { color = 0xFF111111.toInt(); isAntiAlias = true }
        var page = doc.startPage(PdfDocument.PageInfo.Builder(pdfW, pdfH, 1).create())
        var y = 60f
        y = drawCentered(page, y, "◉ Echo Tracks", 22f, true, pdfW, paint)
        y = drawCentered(page, y, title, 14f, true, pdfW, paint)
        y += 6
        for ((head, list, total) in sections) {
            val spent = list.filter { it.direction == Direction.OUT }.sumOf { it.amount }
            val inc = list.filter { it.direction == Direction.IN }.sumOf { it.amount }
            if (y > pdfH - 140) { doc.finishPage(page); page = doc.startPage(PdfDocument.PageInfo.Builder(pdfW, pdfH, 1).create()); y = 60f }
            paint.textSize = 13f; paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            page.canvas.drawText(head, 40f, y, paint); y += 20
            paint.textSize = 11f; paint.typeface = Typeface.DEFAULT
            page.canvas.drawText("Spent ${"%,.0f".format(spent)}  •  In ${"%,.0f".format(inc)}  •  ${list.size} echoes", 40f, y, paint); y += 18
            // top 8 rows per section to keep PDF tight
            for (t in list.take(8)) {
                if (y > pdfH - 60) { doc.finishPage(page); page = doc.startPage(PdfDocument.PageInfo.Builder(pdfW, pdfH, 1).create()); y = 60f }
                paint.textSize = 10f
                val sign = if (t.direction == Direction.OUT) "-" else "+"
                page.canvas.drawText(
                    "${dtFmt.format(Date(t.dateMillis))}  ${t.who.take(26)}  $sign ${"%,.0f".format(t.amount)}",
                    48f, y, paint
                ); y += 15
            }
            if (list.size > 8) {
                paint.textSize = 10f
                page.canvas.drawText("… +${list.size - 8} more (see CSV for full list)", 48f, y, paint); y += 18
            } else y += 8
            @Suppress("UNUSED_VARIABLE") val ignored = total
        }
        paint.textSize = 10f; paint.typeface = Typeface.DEFAULT
        page.canvas.drawText("Generated offline by Echo Tracks • ${dtFmt.format(Date())}", 40f, (pdfH - 30).toFloat(), paint)
        doc.finishPage(page)
        return doc
    }

    private fun saveDoc(ctx: Context, doc: PdfDocument, name: String): File {
        // Buffer bytes first so MediaStore + app-dir backup both get real content
        // (toast shows the backup path; the real PDF lives in Downloads).
        val bytes = try {
            val bos = java.io.ByteArrayOutputStream()
            doc.writeTo(bos)
            bos.toByteArray()
        } finally { try { doc.close() } catch (_: Exception) { } }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                }
            }
        } catch (_: Exception) { }
        val dir = try {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        } catch (_: Exception) { ctx.getExternalFilesDir(null) }
        val fb = try {
            File(ctx.getExternalFilesDir(null), name).apply { writeBytes(bytes) }
        } catch (_: Exception) { null }
        try {
            dir?.mkdirs()
            val f = File(dir, name)
            f.writeBytes(bytes)
            return f
        } catch (_: Exception) { }
        return fb ?: File(ctx.getExternalFilesDir(null), name)
    }

    fun weekly(ctx: Context, all: List<EchoTransaction>): File {
        val week = lastWeeks(all, 1).first()
        val doc = buildDoc(
            "Weekly report • ${dateFmt.format(Date(week.from))} – ${dateFmt.format(Date(week.to))}",
            listOf(Triple(week.label, week.txs, week.txs.sumOf { it.amount }))
        )
        return saveDoc(ctx, doc, "echo_weekly_${System.currentTimeMillis()}.pdf")
    }

    /** Monthly = merge of the last 4-5 weekly sections + category share. */
    fun monthly(ctx: Context, all: List<EchoTransaction>): File {
        val weeks = lastWeeks(all, 5)
        val c = Calendar.getInstance()
        val monthName = SimpleDateFormat("MMMM yyyy", Locale.US).format(c.time)
        val sections = weeks.map { w -> Triple(w.label, w.txs, w.txs.sumOf { it.amount }) }
        val doc = buildDoc("Monthly report • $monthName (merged weekly)", sections)
        return saveDoc(ctx, doc, "echo_monthly_${System.currentTimeMillis()}.pdf")
    }
}
