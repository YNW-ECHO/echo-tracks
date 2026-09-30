package com.echotracks.app.model

enum class Source { MPESA, BANK, CASH }
enum class Direction { OUT, IN }
enum class SpendType { REAL_SPEND, SELF_TRANSFER, FEE, LOAN, INCOME, UNKNOWN }
enum class Category { FARE, SHOPPING, FOOD, BILLS, AIRTIME, RENT, FEES, CASH, TRANSFER, UNCATEGORIZED }

data class EchoTransaction(
    val code: String,
    val amount: Double,
    val who: String,
    val dateMillis: Long,
    val source: Source,
    val direction: Direction,
    val category: Category,
    val spendType: SpendType,
    val balanceAfter: Double? = null,
    val rawSms: String = ""
)
