package com.alnemer.spend.parse

import com.alnemer.spend.data.Direction
import com.alnemer.spend.data.TxnType

/** Normalized output of any message parser. Amounts in halalas (minor units). */
data class ParsedTxn(
    val extractorId: String,
    val institution: String,
    val direction: Direction,
    val amountMinor: Long,
    val occurredAtMillis: Long?,
    val txnType: TxnType,
    val merchantRaw: String? = null,
    val beneficiary: String? = null,
    val channel: String? = null,
    val instrumentMask: String? = null,
    val accountRefHint: String? = null,   // "**2280", "XX1701", "CC" = credit-card product
    val balanceMinor: Long? = null,
    val counterpartyBank: String? = null,
    val originalAmountMilli: Long? = null, // FX original, thousandths
    val originalCurrency: String? = null,
    val fxRateMicro: Long? = null,
    val fxFeesMinor: Long? = null,
)

sealed class ParseResult {
    data class Transaction(val txn: ParsedTxn) : ParseResult()
    data class Ignored(val reason: String) : ParseResult()
    object NoMatch : ParseResult()
}

object Amounts {
    // Accepts: 13,703.99 · 2100.00 · 2000.0 · 15 · .50
    val NUM = "(\\d[\\d,]*(?:\\.\\d+)?|\\.\\d+)"
    fun toMinor(s: String): Long {
        var c = s.replace(",", "").trim()
        if (c.startsWith(".")) c = "0$c"
        val parts = c.split(".")
        val whole = parts[0].ifEmpty { "0" }.toLong()
        val frac = (parts.getOrNull(1) ?: "").padEnd(2, '0').take(2).toLong()
        return whole * 100 + frac
    }
    fun toMilli(s: String): Long {
        var c = s.replace(",", "").trim()
        if (c.startsWith(".")) c = "0$c"
        val parts = c.split(".")
        val whole = parts[0].ifEmpty { "0" }.toLong()
        val frac = (parts.getOrNull(1) ?: "").padEnd(3, '0').take(3).toLong()
        return whole * 1000 + frac
    }
    fun rateMicro(s: String): Long {
        val parts = s.trim().split(".")
        val whole = parts[0].ifEmpty { "0" }.toLong()
        val frac = (parts.getOrNull(1) ?: "").padEnd(6, '0').take(6).toLong()
        return whole * 1_000_000 + frac
    }
}

object When {
    private data class P(val re: Regex, val kind: String)
    private val patterns = listOf(
        P(Regex("(\\d{4})-(\\d{2})-(\\d{2})[ T](\\d{1,2}):(\\d{2})(?::(\\d{2}))?"), "ymd"),
        P(Regex("(\\d{2})-(\\d{2})-(\\d{4}),?\\s+(\\d{1,2}):(\\d{2})\\s*(AM|PM)", RegexOption.IGNORE_CASE), "dmy12"),
        P(Regex("(\\d{2})-(\\d{2})-(\\d{4})\\s+(\\d{1,2}):(\\d{2})(?::(\\d{2}))?"), "dmy"),
        P(Regex("(\\d{1,2}):(\\d{2}):(\\d{2})\\s+(\\d{2})-(\\d{2})-(\\d{4})"), "tdmy"),
        P(Regex("(\\d{2})/(\\d{2})/(\\d{2})\\s+(\\d{1,2}):(\\d{2})"), "dmy2"), // SNB: DD/MM/YY (2-digit year)
    )
    fun parse(text: String): Long? {
        for (p in patterns) {
            val m = p.re.find(text) ?: continue
            val g = m.groupValues
            try {
                var (y, mo, d, h, mi, s) = when (p.kind) {
                    "ymd"   -> listOf(g[1], g[2], g[3], g[4], g[5], g[6].ifEmpty { "0" }).map { it.toInt() }
                    "dmy"   -> listOf(g[3], g[2], g[1], g[4], g[5], g[6].ifEmpty { "0" }).map { it.toInt() }
                    "dmy12" -> listOf(g[3], g[2], g[1], g[4], g[5], "0").map { it.toInt() }
                    "dmy2"  -> listOf("20${g[3]}", g[2], g[1], g[4], g[5], "0").map { it.toInt() }
                    else    -> listOf(g[6], g[5], g[4], g[1], g[2], g[3]).map { it.toInt() }
                }
                if (p.kind == "dmy12") {
                    val pm = g[6].equals("PM", true)
                    if (pm && h < 12) h += 12
                    if (!pm && h == 12) h = 0
                }
                return java.time.LocalDateTime.of(y, mo, d, h, mi, s)
                    .atZone(java.time.ZoneId.of("Asia/Riyadh")).toInstant().toEpochMilli()
            } catch (_: Exception) { }
        }
        return null
    }
    private operator fun <T> List<T>.component6(): T = this[5]
}
