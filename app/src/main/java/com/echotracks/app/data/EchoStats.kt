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
    data class DayBar(val label: String, val amount: Double, val dayStart: Long)
    fun perDay(txs: List<EchoTransaction>, days: Int = 14, direction: Direction = Direction.OUT): List<DayBar> {
        val cal = Calendar.getInstance()
        val fmt = java.text.SimpleDateFormat("EEE", Locale.US)
        data class K(val y: Int, val d: Int)
        val keys = mutableListOf<K>()
        val labels = mutableListOf<String>()
        val starts = mutableListOf<Long>()
        val map = LinkedHashMap<K, Double>()
        val now = System.currentTimeMillis()
        for (i in days - 1 downTo 0) {
            cal.timeInMillis = now
            cal.add(Calendar.DAY_OF_YEAR, -i)
            val k = K(cal.get(Calendar.YEAR), cal.get(Calendar.DAY_OF_YEAR))
            keys.add(k); labels.add(fmt.format(cal.time))
            starts.add(startOfDay(cal.timeInMillis)); map[k] = 0.0
        }
        for (t in txs) {
            if (t.direction != direction) continue
            cal.timeInMillis = t.dateMillis
            val k = K(cal.get(Calendar.YEAR), cal.get(Calendar.DAY_OF_YEAR))
            if (map.containsKey(k)) map[k] = map[k]!! + t.amount
        }
        return keys.mapIndexed { idx, k -> DayBar(labels[idx], map[k] ?: 0.0, starts[idx]) }
    }

    // 12-month history fuel: per-month totals (oldest->newest for charts)
    data class MonthBar(val label: String, val amount: Double, val monthStart: Long)
    fun perMonth(txs: List<EchoTransaction>, months: Int = 12, direction: Direction = Direction.OUT): List<MonthBar> {
        val cal = Calendar.getInstance()
        val fmt = java.text.SimpleDateFormat("MMM", Locale.US)
        data class K(val y: Int, val m: Int)
        val keys = mutableListOf<K>()
        val labels = mutableListOf<String>()
        val starts = mutableListOf<Long>()
        val map = LinkedHashMap<K, Double>()
        val now = System.currentTimeMillis()
        for (i in months - 1 downTo 0) {
            cal.timeInMillis = now
            cal.add(Calendar.MONTH, -i)
            cal.set(Calendar.DAY_OF_MONTH, 1)
            cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
            val k = K(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH))
            keys.add(k); labels.add(fmt.format(cal.time))
            starts.add(cal.timeInMillis); map[k] = 0.0
        }
        for (t in txs) {
            if (t.direction != direction) continue
            cal.timeInMillis = t.dateMillis
            val k = K(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH))
            if (map.containsKey(k)) map[k] = map[k]!! + t.amount
        }
        return keys.mapIndexed { idx, k -> MonthBar(labels[idx], map[k] ?: 0.0, starts[idx]) }
    }

    // bounds for the month `offset` back (0 = this month): start .. end inclusive
    fun monthRange(offset: Int): Pair<Long, Long> {
        val c = Calendar.getInstance()
        c.add(Calendar.MONTH, -offset)
        c.set(Calendar.DAY_OF_MONTH, 1)
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        val from = c.timeInMillis
        c.add(Calendar.MONTH, 1)
        return from to (c.timeInMillis - 1)
    }
    // bounds for an arbitrary month containing `monthStart` (from perMonth MonthBar).
    // Used by month drill-in: tap a bar in the 12-month chart -> filter to that month.
    fun monthBounds(monthStart: Long): Pair<Long, Long> {
        return try {
            val c = Calendar.getInstance()
            c.timeInMillis = monthStart
            c.set(Calendar.DAY_OF_MONTH, 1)
            c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0)
            c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
            val from = c.timeInMillis
            c.add(Calendar.MONTH, 1)
            from to (c.timeInMillis - 1)
        } catch (_: Exception) {
            monthStart to (monthStart + 31L * 24 * 60 * 60 * 1000 - 1)
        }
    }
    fun byCategoryMonth(txs: List<EchoTransaction>, offset: Int): List<Pair<Category, Double>> {
        val (from, to) = monthRange(offset)
        return byCategory(inRange(txs, from, to))
    }
    fun daysLeftInMonth(): Int {
        return try {
            val c = Calendar.getInstance()
            c.getActualMaximum(Calendar.DAY_OF_MONTH) - c.get(Calendar.DAY_OF_MONTH) + 1
        } catch (_: Exception) { 0 }
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
    // year history: distinct calendar years present in data (newest first)
    fun yearsOf(txs: List<EchoTransaction>): List<Int> {
        return try {
            val c = Calendar.getInstance()
            txs.map {
                c.timeInMillis = it.dateMillis
                c.get(Calendar.YEAR)
            }.distinct().sortedDescending()
        } catch (_: Exception) { emptyList() }
    }
    // bounds for one history year: Jan 1 00:00:00.000 .. Dec 31 23:59:59.999
    fun yearBounds(year: Int): Pair<Long, Long> {
        val c = Calendar.getInstance()
        c.set(year, Calendar.JANUARY, 1, 0, 0, 0); c.set(Calendar.MILLISECOND, 0)
        val from = c.timeInMillis
        c.set(year, Calendar.DECEMBER, 31, 23, 59, 59); c.set(Calendar.MILLISECOND, 999)
        return from to c.timeInMillis
    }
    // budget-goal bar: spend this calendar month (clean, OUT only)
    fun monthSpent(txs: List<EchoTransaction>): Double {
        val c = Calendar.getInstance()
        c.set(Calendar.DAY_OF_MONTH, 1); c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        val start = c.timeInMillis
        return txs.filter { it.dateMillis >= start && it.direction == Direction.OUT }.sumOf { it.amount }
    }
    data class BillDue(val name: String, val avgAmount: Double, val daysLeft: Int, val overdue: Boolean,
                      val key: String = "", val userMarked: Boolean = false)
    // bill reminders: recurring BILLS/RENT payees (2+ payments) -> next due ≈ last + 30d.
    // paid overrides (BillStore: user tapped "Paid") win as the new baseline.
    fun upcomingBills(txs: List<EchoTransaction>, paid: Map<String, Long> = emptyMap()): List<BillDue> {
        val bills = txs.filter {
            it.direction == Direction.OUT && (it.category == Category.BILLS || it.category == Category.RENT)
        }
        if (bills.isEmpty() && paid.isEmpty()) return emptyList()
        val now = System.currentTimeMillis()
        val fromTx = bills.groupBy { it.who }.mapNotNull { (who, list) ->
            if (list.size < 1) return@mapNotNull null
            val sorted = list.sortedBy { it.dateMillis }
            val last = sorted.last().dateMillis
            val avg = list.sumOf { it.amount } / list.size
            // repeat bill if seen 2+ times OR looks like utility (kplc/dstv/water/rent)
            val l = who.lowercase()
            val isUtility = listOf("kplc", "dstv", "gotv", "water", "rent", "nhif").any { it in l }
            if (list.size < 2 && !isUtility) return@mapNotNull null
            who to Triple(who, avg, last)
        }.toMap()
        val keys = (fromTx.keys.map { it.trim().lowercase() } + paid.keys).distinct()
        return keys.mapNotNull { key ->
            val entry = fromTx.entries.firstOrNull { it.key.trim().lowercase() == key }
            val who = entry?.key ?: key
            val avg = entry?.value?.second ?: 0.0
            val lastTx = entry?.value?.third ?: 0L
            val marked = paid[key]
            if (entry == null && marked == null) return@mapNotNull null
            // user-confirmed payment is the freshest baseline
            val base = maxOf(lastTx, marked ?: 0L)
            if (base <= 0) return@mapNotNull null
            val nextDue = base + 30L * 24 * 60 * 60 * 1000
            val daysLeft = ((nextDue - now) / (24 * 60 * 60 * 1000)).toInt()
            BillDue(who.take(30), avg, daysLeft, daysLeft < 0, key, marked != null)
        }.sortedBy { it.daysLeft }.take(6)
    }
}
