package com.echotracks.app.data

import com.echotracks.app.model.*
import java.util.*

object EchoStats {
    fun inRange(txs: List<EchoTransaction>, from: Long?, to: Long?): List<EchoTransaction> =
        txs.filter { (from == null || it.dateMillis >= from) && (to == null || it.dateMillis <= to) }

    fun visible(txs: List<EchoTransaction>, hideNoise: Boolean): List<EchoTransaction> {
        if (!hideNoise) return txs
        return txs.filter { it.spendType == SpendType.REAL_SPEND || it.spendType == SpendType.INCOME }
    }
    fun search(txs: List<EchoTransaction>, q: String): List<EchoTransaction> {
        if (q.isBlank()) return txs
        val s = q.trim().lowercase()
        // Amount search: "5000" exact/contains, ">5000", ">=5000", "<1000", "<=1000",
        // "1000-5000" range, "5k" shorthand. Combines with text match via OR.
        parseAmountFilter(s)?.let { pred ->
            val textHit = txs.filter {
                it.who.lowercase().contains(s) || it.category.name.lowercase().contains(s)
            }
            return (txs.filter { pred(it.amount) } + textHit).distinctBy { it.code }
        }
        return txs.filter { it.who.lowercase().contains(s) || it.category.name.lowercase().contains(s) }
    }

    private fun parseNum(tok: String): Double? {
        val t = tok.trim().lowercase().replace(",", "")
        if (t.isBlank()) return null
        return when {
            t.endsWith("k") -> t.dropLast(1).toDoubleOrNull()?.times(1000)
            t.endsWith("m") -> t.dropLast(1).toDoubleOrNull()?.times(1_000_000)
            else -> t.toDoubleOrNull()
        }
    }

    private fun parseAmountFilter(s: String): ((Double) -> Boolean)? {
        val t = s.replace("ksh", "").replace("kes", "").replace("/-", "").trim()
        if (t.isBlank()) return null
        // range: "1000-5000" / "1k-5k"
        if (Regex("""^[\d.,km]+\s*-\s*[\d.,km]+$""").matches(t)) {
            val parts = t.split("-")
            val lo = parseNum(parts[0]) ?: return null
            val hi = parseNum(parts[1]) ?: return null
            val (a, b) = if (lo <= hi) lo to hi else hi to lo
            return { amt -> amt in a..b }
        }
        // comparators
        if (t.startsWith(">=")) { val n = parseNum(t.drop(2)) ?: return null; return { it >= n } }
        if (t.startsWith("<=")) { val n = parseNum(t.drop(2)) ?: return null; return { it <= n } }
        if (t.startsWith(">")) { val n = parseNum(t.drop(1)) ?: return null; return { it > n } }
        if (t.startsWith("<")) { val n = parseNum(t.drop(1)) ?: return null; return { it < n } }
        // plain number: exact match OR digit-substring (typing "500" finds 500, 1500, 5000)
        val n = parseNum(t) ?: return null
        if (n <= 0) return null
        return { amt -> amt == n || amt.toLong().toString().contains(n.toLong().toString()) }
    }
    fun totals(txs: List<EchoTransaction>): Pair<Double, Double> {
        val spent = txs.filter { it.direction == Direction.OUT }.sumOf { it.amount }
        val income = txs.filter { it.direction == Direction.IN }.sumOf { it.amount }
        return spent to income
    }
    fun byCategory(txs: List<EchoTransaction>): List<Pair<Category, Double>> =
        txs.filter { it.direction == Direction.OUT }.groupBy { it.category }
            .mapValues { (_, v) -> v.sumOf { it.amount } }.toList().sortedByDescending { it.second }

    fun topRecipients(txs: List<EchoTransaction>, n: Int = 8): List<Pair<String, Double>> =
        txs.filter { it.direction == Direction.OUT }.groupBy { it.who }
            .mapValues { (_, v) -> v.sumOf { it.amount } }.toList().sortedByDescending { it.second }.take(n)

    // best dashboard fuel: per-day totals for last N days (oldest->newest for charts)
    fun perDay(txs: List<EchoTransaction>, days: Int = 14): List<Pair<String, Double>> {
        val cal = Calendar.getInstance()
        val fmt = java.text.SimpleDateFormat("EEE", Locale.US)
        val map = LinkedHashMap<String, Double>()
        val keys = mutableListOf<String>(); val dayKeys = mutableListOf<String>()
        for (i in days - 1 downTo 0) {
            cal.timeInMillis = System.currentTimeMillis()
            cal.add(Calendar.DAY_OF_YEAR, -i)
            val key = "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.DAY_OF_YEAR)}"
            dayKeys.add(key); keys.add(fmt.format(cal.time))
            map[key] = 0.0
        }
        for (t in txs) {
            if (t.direction != Direction.OUT) continue
            cal.timeInMillis = t.dateMillis
            val key = "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.DAY_OF_YEAR)}"
            if (map.containsKey(key)) map[key] = map[key]!! + t.amount
        }
        return keys.zip(dayKeys.map { map[it] ?: 0.0 })
    }

    fun dailyStory(txs: List<EchoTransaction>): String {
        if (txs.isEmpty()) return "No echoes yet. Grant SMS permission to start tracking."
        val (spent, income) = totals(txs)
        val top = byCategory(txs).firstOrNull()
        val topTxt = if (top != null) ", biggest was ${top.first.name.lowercase()} at ${"%,.0f".format(top.second)}" else ""
        return "You spent ${"%,.0f".format(spent)} in ${txs.size} places$topTxt. Received ${"%,.0f".format(income)}."
    }

    fun insight(txs: List<EchoTransaction>): String {
        val byCat = byCategory(txs)
        if (byCat.isEmpty()) return "Nothing to analyze yet."
        val (topCat, topAmt) = byCat.first()
        val (_, income) = totals(txs)
        val rate = if (income > 0) (topAmt / income * 100).toInt() else 0
        return "${topCat.name.lowercase().replaceFirstChar { it.uppercase() }} takes the crown at ${"%,.0f".format(topAmt)}" +
            (if (income > 0) " ($rate% of income)" else "") + ". " +
            (when (topCat) {
                Category.FARE -> "Consider weekly matatu passes."
                Category.FOOD -> "Cooking twice a week beats this."
                Category.SHOPPING -> "Bulk-buy staples once a month."
                else -> "Cap it next month and watch savings grow."
            })
    }

    fun monthsAgo(m: Int): Long {
        val c = Calendar.getInstance(); c.add(Calendar.MONTH, -m); return c.timeInMillis
    }
    fun daysAgo(d: Int): Long {
        val c = Calendar.getInstance(); c.add(Calendar.DAY_OF_YEAR, -d); return c.timeInMillis
    }
    fun savingsRate(spent: Double, income: Double): Int {
        if (income <= 0) return 0
        return (((income - spent) / income * 100).toInt()).coerceIn(-100, 100)
    }
    fun thisMonthVsLast(txs: List<EchoTransaction>): Pair<Double, Double> {
        val c = Calendar.getInstance()
        c.set(Calendar.DAY_OF_MONTH, 1); c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        val startThis = c.timeInMillis
        c.add(Calendar.MONTH, -1); val startLast = c.timeInMillis
        val thisM = txs.filter { it.dateMillis >= startThis && it.direction == Direction.OUT }.sumOf { it.amount }
        val lastM = txs.filter { it.dateMillis in startLast..<startThis && it.direction == Direction.OUT }.sumOf { it.amount }
        return thisM to lastM
    }
    fun startOfDay(millis: Long): Long {
        val c = Calendar.getInstance(); c.timeInMillis = millis
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }
    // budget-goal bar: spend this calendar month (clean, OUT only)
    fun monthSpent(txs: List<EchoTransaction>): Double {
        val c = Calendar.getInstance()
        c.set(Calendar.DAY_OF_MONTH, 1); c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        val start = c.timeInMillis
        return txs.filter { it.dateMillis >= start && it.direction == Direction.OUT }.sumOf { it.amount }
    }
    data class BillDue(val name: String, val avgAmount: Double, val daysLeft: Int, val overdue: Boolean)
    // bill reminders: recurring BILLS payees (2+ payments) -> next due ≈ last + 30d
    fun upcomingBills(txs: List<EchoTransaction>): List<BillDue> {
        val bills = txs.filter { it.direction == Direction.OUT && it.category == Category.BILLS }
        if (bills.isEmpty()) return emptyList()
        val now = System.currentTimeMillis()
        return bills.groupBy { it.who }.mapNotNull { (who, list) ->
            if (list.size < 1) return@mapNotNull null
            val sorted = list.sortedBy { it.dateMillis }
            val last = sorted.last().dateMillis
            val avg = list.sumOf { it.amount } / list.size
            // repeat bill if seen 2+ times OR looks like utility (kplc/dstv/water/rent)
            val l = who.lowercase()
            val isUtility = listOf("kplc","dstv","gotv","water","rent","nhif").any { it in l }
            if (list.size < 2 && !isUtility) return@mapNotNull null
            val nextDue = last + 30L * 24 * 60 * 60 * 1000
            val daysLeft = ((nextDue - now) / (24 * 60 * 60 * 1000)).toInt()
            BillDue(who.take(30), avg, daysLeft, daysLeft < 0)
        }.sortedBy { it.daysLeft }.take(5)
    }
}
