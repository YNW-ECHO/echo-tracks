package com.echotracks.app.data

import android.content.Context
import android.net.Uri
import com.echotracks.app.model.*
import com.echotracks.app.parser.MpesaParser
import com.echotracks.app.rules.CategoryRules

object SmsReader {
    data class RawSms(val sender: String, val body: String, val date: Long)

    fun readAll(ctx: Context, limit: Int = 10000): List<RawSms> {
        val out = mutableListOf<RawSms>()
        try {
            val uri = Uri.parse("content://sms/inbox")
            val cols = arrayOf("address", "body", "date")
            ctx.contentResolver.query(uri, cols, null, null, "date DESC")?.use { c ->
                val ai = c.getColumnIndexOrThrow("address")
                val bi = c.getColumnIndexOrThrow("body")
                val di = c.getColumnIndexOrThrow("date")
                var n = 0
                while (c.moveToNext() && n < limit) {
                    try {
                        val body = c.getString(bi) ?: ""
                        if (body.isBlank()) continue
                        out.add(RawSms(c.getString(ai) ?: "", body, c.getLong(di)))
                        n++
                    } catch (_: Exception) { continue }
                }
            }
        } catch (_: Exception) {}
        return out
    }

    fun toTransactions(raw: List<RawSms>): List<EchoTransaction> {
        val seen = HashSet<String>()
        val txs = mutableListOf<EchoTransaction>()
        for (r in raw) {
            try {
                val src = MpesaParser.detectSource(r.sender, r.body)
                if (src == "UNKNOWN") continue
                val parsed = parseBody(r.body, r.date, src) ?: continue
                val key = "${parsed.code}|${parsed.dateMillis}|${parsed.amount}"
                if (seen.add(key)) txs.add(parsed) // FIX: dedupe re-reads
            } catch (_: Exception) { continue }
        }
        return txs.sortedByDescending { it.dateMillis }
    }

    // public for tests
    fun parseBody(body: String, date: Long, src: String): EchoTransaction? {
        if (body.isBlank()) return null
        val amtRx = Regex("""(?:Ksh|KES)\s?([\d,]+(?:\.\d{2})?)""", RegexOption.IGNORE_CASE)
        val first = amtRx.find(body) ?: return null
        val amt = first.groupValues[1].replace(",", "").toDoubleOrNull() ?: return null
        if (amt <= 0 || amt > 50_000_000) return null
        val codeRx = Regex("""^([A-Z0-9]{10})\s+Confirmed""")
        // FIX: bank collisions — hash body+date instead of date%100000
        val code = codeRx.find(body.trim())?.groupValues?.get(1)
            ?: ("B" + (kotlin.math.abs((body + date).hashCode())).toString())
        val lower = body.lowercase()
        val isIn = "you have received" in lower || "received ksh" in lower ||
            "received kes" in lower || "deposit of" in lower || "credit of" in lower ||
            ("reversal" in lower && "from" in lower)
        val isOut = !isIn
        val who = extractWho(body)
        val source = if (src == "MPESA") Source.MPESA else Source.BANK
        val dir = when {
            "withdrawal of" in lower || "debit of" in lower -> Direction.OUT
            isIn -> Direction.IN
            else -> Direction.OUT
        }
        val cat = CategoryRules.categorize(who, body)
        // transfers to person keep TRANSFER category even if keyword missed
        val st = CategoryRules.spendType(body, if (dir == Direction.IN) "IN" else "OUT")
        // balanceAfter: last Ksh amount in msg (not first) — best effort, never crash
        var balance: Double? = null
        try {
            val all = amtRx.findAll(body).map { it.groupValues[1].replace(",", "").toDouble() }.toList()
            if (all.size >= 2) balance = all.last()
        } catch (_: Exception) {}
        if (isOut && amt <= 0) return null
        return EchoTransaction(code, amt, who, date, source, dir, cat, st, balance, body)
    }

    private fun extractWho(body: String): String {
        // order matters: till/paybill/pochi carry merchant after them
        Regex("""paid to (.+?)\s+(till|paybill|pochi)[^\.]*\.?\s*on""", RegexOption.IGNORE_CASE)
            .find(body)?.let { return it.groupValues[1].trim().take(60) }
        Regex("""paid to (.+?)\.\s*on""", RegexOption.IGNORE_CASE)
            .find(body)?.let { return it.groupValues[1].trim().take(60) }
        // sent to "JOHN KAMAU 0722..." — keep name, drop trailing " on date"
        Regex("""sent to (.+?)\s+on\s+\d""", RegexOption.IGNORE_CASE)
            .find(body)?.let { return it.groupValues[1].trim().take(60) }
        Regex("""(?:reversal of.+?from|you have received.+?from|received.+?from)\s*(.+?)\s+on\s+\d""", RegexOption.IGNORE_CASE)
            .find(body)?.let { return it.groupValues[1].trim().take(60) }
        Regex("""airtime purchased for (\d+)""", RegexOption.IGNORE_CASE)
            .find(body)?.let { return "Airtime ${it.groupValues[1]}" }
        Regex("""(?:debit of.+?for|purchase of.+?at|withdrawal of.+?at)\s*([A-Z][A-Z0-9 .&'-]{2,40})""", RegexOption.IGNORE_CASE)
            .find(body)?.let { return it.groupValues[1].trim().trimEnd('.').take(60) }
        Regex("""(?:at|from|to)\s+([A-Z][A-Z0-9 .&'-]{3,40})\s+on\s+\d""", RegexOption.IGNORE_CASE)
            .find(body)?.let { return it.groupValues[1].trim().take(60) }
        return "Unknown"
    }
}
