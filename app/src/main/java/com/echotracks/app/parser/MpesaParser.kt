package com.echotracks.app.parser

// sender + body aware (fixes shortcode / till SMS misclassified as UNKNOWN)
object MpesaParser {
    fun detectSource(sender: String, body: String = ""): String {
        val s = sender.uppercase()
        val b = body.uppercase()
        if ("M-PESA" in s || "MPESA" in s) return "MPESA"
        if ("M-PESA" in b && "CONFIRMED" in b) return "MPESA"
        if (listOf("KCB","EQUITY","CO-OP","COOP","STANBIC","ABSA","FAMILY BANK","DTB","NCBA")
                .any { it in s }) return "BANK"
        if (Regex("""\b(KCB|EQUITY|CO-?OP|STANBIC|ABSA|BALANCE|ACCOUNT \d)\b""").containsMatchIn(b)
            && "CONFIRMED" !in b) return "BANK"
        return "UNKNOWN"
    }
}
