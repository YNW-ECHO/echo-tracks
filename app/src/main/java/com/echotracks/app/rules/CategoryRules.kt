package com.echotracks.app.rules

import com.echotracks.app.model.Category
import com.echotracks.app.model.SpendType

object CategoryRules {
    private val map = listOf(
        listOf("kplc","kplc token","electricity","nairobi water","dstv","gotv","startimes","nhif","rent","school fees") to Category.BILLS,
        listOf("java house","kfc","pizza","restaurant","cafe","food","eats","chips","nyama","java") to Category.FOOD,
        listOf("bolt","uber","little ride","matatu","nduthi","fare","ride","fuel","shell","totalenergies") to Category.FARE,
        listOf("naivas","carrefour","quickmart","chandarana","supermarket","naivas deli") to Category.SHOPPING,
        listOf("airtime","bundles","data bundles","safaricom data") to Category.AIRTIME,
        listOf("atm","withdrawal of","withdrawal at","agent withdrawal","cash withdrawal") to Category.CASH,
    )
    private val personRx = Regex("""07\d{8}|01\d{8}|\+254\d{9}""")

    fun categorize(who: String, raw: String): Category {
        val t = "$who $raw".lowercase()
        for ((keys, cat) in map) if (keys.any { it in t }) return cat
        if ("till" in t || "paybill" in t || "pochi" in t) return Category.SHOPPING
        if (personRx.containsMatchIn(who) && ("sent to" in t || "send money" in t)) return Category.TRANSFER
        return Category.UNCATEGORIZED
    }

    fun spendType(raw: String, direction: String): SpendType {
        val t = raw.lowercase()
        if ("fuliza" in t || "m-shwari" in t || "mshwari" in t || "overdraft" in t ||
            ("loan" in t && ("disbursed" in t || "repayment" in t || "kcb m-pesa" in t))) return SpendType.LOAN
        if ("reversal" in t || "reversed" in t || "refund" in t) return SpendType.UNKNOWN
        val isPayment = listOf("paid to","sent to","you have received","received ksh","received kes",
            "airtime purchased","deposit of","debit of","withdrawal of","credit of").any { it in t }
        if (isPayment) return if (direction == "IN") SpendType.INCOME else SpendType.REAL_SPEND
        if ("transaction cost" in t || "transaction charge" in t) return SpendType.FEE
        if (Regex("""\bfee\b|\bcharges?\b|\blevy\b""").containsMatchIn(t) &&
            ("deducted" in t || "charged" in t || "cost" in t)) return SpendType.FEE
        return if (direction == "IN") SpendType.INCOME else SpendType.REAL_SPEND
    }
}
