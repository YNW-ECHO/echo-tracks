package com.echotracks.app.data

// Rotating money-wisdom footer: text + author + year said.
data class MoneyQuote(val text: String, val author: String, val year: String)

object MoneyQuotes {
    val all = listOf(
        MoneyQuote("Do not save what is left after spending, but spend what is left after saving.", "Warren Buffett", "1997"),
        MoneyQuote("A budget is telling your money where to go instead of wondering where it went.", "Dave Ramsey", "2003"),
        MoneyQuote("Beware of little expenses; a small leak will sink a great ship.", "Benjamin Franklin", "1758"),
        MoneyQuote("The art is not in making money, but in keeping it.", "Proverb", "1875"),
        MoneyQuote("Wealth consists not in having great possessions, but in having few wants.", "Epictetus", "108"),
        MoneyQuote("Money is a terrible master but an excellent servant.", "P.T. Barnum", "1880"),
        MoneyQuote("Financial peace isn't the acquisition of stuff. It's learning to live on less than you make.", "Dave Ramsey", "2011"),
        MoneyQuote("Every time you borrow money, you're robbing your future self.", "Nathan W. Morris", "2008"),
        MoneyQuote("Save first, spend the rest — pay yourself before the shops pay themselves.", "Echo proverb", "2024"),
        MoneyQuote("Small amounts saved daily turn into security you can feel monthly.", "Echo proverb", "2024"),
    )
    fun ofDay(): MoneyQuote {
        val day = (System.currentTimeMillis() / 86_400_000L).toInt()
        return all[Math.floorMod(day, all.size)]
    }
    fun greeting(): Triple<String, String, String> {
        val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return when (h) {
            in 5..11 -> Triple("Good morning", "Fresh ledger, fresh chances.", "☀️")
            in 12..16 -> Triple("Good afternoon", "Midday check — is spend on track?", "🌤️")
            in 17..21 -> Triple("Good evening", "Wind down, review today's echoes.", "🌙")
            else -> Triple(" burning the midnight oil", "Quiet hours — plan tomorrow's budget.", "✨")
        }.let {
            // fix leading space typo for night case
            if (it.first.startsWith(" ")) Triple("Good night", it.second, it.third) else it
        }
    }
}
