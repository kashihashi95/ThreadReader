package com.redder.tts

/** Says numbers as words. */
internal object NumberWords {
    private val ONES =
        listOf(
            "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve",
            "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen",
        )
    private val TENS = listOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")
    private val SCALES =
        listOf(1_000_000_000_000L to "trillion", 1_000_000_000L to "billion", 1_000_000L to "million", 1_000L to "thousand")
    private const val MAX_DIGITS = 15

    fun cardinal(digits: String): String {
        if (digits.length > MAX_DIGITS || (digits.length > 1 && digits.startsWith("0"))) return digits(digits)
        return cardinal(digits.toLong())
    }

    fun digits(digits: String): String = digits.map { ONES[it - '0'] }.joinToString(" ")

    fun money(
        amount: String,
        suffix: String,
    ): String {
        val plain = amount.replace(",", "")
        val whole = plain.substringBefore('.')
        val cents = plain.substringAfter('.', "")
        val spokenWhole = cardinal(whole)
        val scaled = if (suffix.isEmpty()) spokenWhole else "$spokenWhole ${scale(suffix)}"
        val unit = if (whole == "1" && suffix.isEmpty()) "dollar" else "dollars"
        return if (cents.isEmpty()) "$scaled $unit" else "$scaled $unit and ${cardinal(cents.padEnd(2, '0').take(2))} cents"
    }

    private fun scale(suffix: String): String =
        when (suffix.lowercase()) {
            "k" -> "thousand"
            "m" -> "million"
            else -> "billion"
        }

    private fun cardinal(value: Long): String {
        if (value < 20) return ONES[value.toInt()]
        if (value < 100) {
            val tens = TENS[(value / 10).toInt()]
            val ones = value % 10
            return if (ones == 0L) tens else "$tens ${ONES[ones.toInt()]}"
        }
        if (value < 1000) {
            val rest = value % 100
            val hundreds = "${ONES[(value / 100).toInt()]} hundred"
            return if (rest == 0L) hundreds else "$hundreds ${cardinal(rest)}"
        }
        val (size, name) = SCALES.first { value >= it.first }
        val rest = value % size
        val head = "${cardinal(value / size)} $name"
        return if (rest == 0L) head else "$head ${cardinal(rest)}"
    }
}
