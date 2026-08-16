package com.alnemer.spend.parse

import com.alnemer.spend.data.Direction
import com.alnemer.spend.data.TxnType
import com.alnemer.spend.parse.Amounts.NUM

/** One parsed statement row, ready for merge-ingestion. */
data class StmtRow(
    val bankRef: String?,
    val occurredAtMillis: Long?,
    val dayPrecision: Boolean,          // true = date only, no time (SAB CC)
    val direction: Direction,
    val amountMinor: Long,
    val txnType: TxnType,
    val merchantRaw: String? = null,
    val cardHolder: String? = null,
    val instrumentMask: String? = null,
    val balanceMinor: Long? = null,
    val originalAmountMilli: Long? = null,
    val originalCurrency: String? = null,
    val fxFeesMinor: Long? = null,
    val rawDetail: String? = null,      // filtered original statement text — shown verbatim in Review
)

data class StmtParse(
    val extractorId: String,
    val accountHint: String,            // display-name or accountRef hint for account resolution
    val rows: List<StmtRow>,
    val notes: List<String>,
    val periodStartMillis: Long? = null,
    val periodEndMillis: Long? = null,
    val reconciled: Boolean? = null,    // true = rows proven against the statement's own totals
)

/**
 * SAB Premier current-account statement — Arabic RTL, Eastern numerals.
 *
 * Extractors emit this layout in three orientations (logical / fully mirrored /
 * Arabic-logical with mirrored Latin+digit runs — the pdfbox one). All three are
 * normalized first. Direction can NOT be inferred from the type text (the same
 * Arabic label serves incoming and outgoing transfers): a reconciliation solver
 * assigns DEBIT/CREDIT so running balance, totals and row counts match the
 * statement's own summary line exactly. No consistent assignment -> refuse to
 * import (loud failure beats silent wrong-direction rows).
 * Validated against 6 real statements (Jan–Jun 2026) x pdfbox + poppler extractions.
 */
object SabCaStmt {
    private const val ID = "SAB-CA-AR-2026.08"

    // ---------- normalization ----------
    private fun western(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) sb.append(when (c) {
            in '٠'..'٩' -> ('0' + (c - '٠'))
            in '۰'..'۹' -> ('0' + (c - '۰'))
            '٬' -> ','
            else -> c
        })
        return sb.toString()
    }
    private fun normalize(raw: String): String =
        western(TextNorm.clean(java.text.Normalizer.normalize(raw, java.text.Normalizer.Form.NFKC)))
    private fun noSpace(s: String) = s.filter { !it.isWhitespace() }
    private fun hasArabic(s: String) = s.any { it in '؀'..'ۿ' }
    private fun countOf(s: String, sub: String): Int {
        var i = 0; var n = 0
        while (true) { i = s.indexOf(sub, i); if (i < 0) return n; n++; i += sub.length }
    }
    private fun money(minor: Long): String =
        String.format(java.util.Locale.US, "%,d.%02d", minor / 100, minor % 100)

    /** Mixed mode: reverse each maximal non-Arabic run; the amount-reh between digits belongs to the run. */
    private fun fixRuns(line: String): String {
        val out = StringBuilder(); val run = StringBuilder()
        fun isRtl(i: Int, c: Char): Boolean {
            if (c !in '؀'..'ۿ') return false
            if (c == 'ر' && i > 0 && i < line.length - 1 && line[i - 1].isDigit() && line[i + 1].isDigit()) return false
            return true
        }
        for (i in line.indices) {
            val c = line[i]
            if (isRtl(i, c)) {
                if (run.isNotEmpty()) { out.append(run.reverse()); run.setLength(0) }
                out.append(c)
            } else run.append(c)
        }
        if (run.isNotEmpty()) out.append(run.reverse())
        return out.toString()
    }

    private fun orient(text0: String): String {
        var text = text0
        var ns = noSpace(text)
        if (countOf(ns, "باسح") > countOf(ns, "حساب")) {       // fully mirrored (visual order)
            text = text.lines().joinToString("\n") { if (hasArabic(it)) it.reversed() else it }
            ns = noSpace(text)
        }
        if (countOf(ns, "FER") > countOf(ns, "REF") || countOf(ns, "BIH") > countOf(ns, "HIB")) {
            text = text.lines().joinToString("\n") { fixRuns(it) }   // mixed runs (pdfbox 1.x style)
            ns = noSpace(text)
        }
        if (countOf(ns, "حسابكشف") > countOf(ns, "كشفحساب"))
            text = text.lines().joinToString("\n") { unmirror(it) }  // word-order mirrored (pdfbox 2.x — the on-device engine)
        return text
    }

    /** pdfbox 2.x emits visual word order: words internally logical, sequence mirrored.
     *  Reverse token order, then restore the internal order of consecutive LTR runs
     *  (amount tokens count as LTR — their reh separator is not a word letter here). */
    private val amountReh = Regex("(?<=\\d)ر(?=\\d)")
    private fun tokRtl(t: String) = hasArabic(amountReh.replace(t, ""))
    private fun unmirror(l: String): String {
        if (!hasArabic(l)) return l
        val out = mutableListOf<String>()
        val run = mutableListOf<String>()
        for (t in l.split(" ").reversed()) {
            if (t.isNotEmpty() && tokRtl(t)) {
                if (run.isNotEmpty()) { out.addAll(run.reversed()); run.clear() }
                out.add(t)
            } else run.add(t)
        }
        if (run.isNotEmpty()) out.addAll(run.reversed())
        return out.joinToString(" ")
    }

    // ---------- grammar ----------
    private val tokFracFirst = Regex("(\\d{2})ر(\\d{1,3}(?:,\\d{3})*)")
    private val tokIntFirst = Regex("(\\d{1,3}(?:,\\d{3})*)ر(\\d{2})")
    private val refRe = Regex("REF\\s*([A-Z0-9]{4})-(\\d{5})")
    private val dateCand = Regex("(\\d{1,2})/(\\d{2})")
    private val gregFull = Regex("(20\\d{2})/ *(\\d{1,2})/(\\d{1,2})")
    private val embeddedTs = Regex("(\\d{2})([A-Z]{3})(\\d{2})\\s*(\\d{1,2}):(\\d{2}):(\\d{2})")
    private val trnDate = Regex("TRN\\s*DATE\\s*:\\s*(20\\d{2})-(\\d{2})-(\\d{2})")
    private val refTime = Regex("REF\\s*[A-Z0-9]{4}-\\d{5}\\s*(\\d{1,2}):(\\d{2}):(\\d{2})")
    private val pan16 = Regex("(?<!\\d)(\\d{16})(?!\\d)")
    private val benfId = Regex("(?:BENF|REMIT)\\s*ID\\s*:?\\s*B?\\s*(\\d{6,12})")
    private val acctNoRe = Regex("(\\d{3})-(\\d{6})-(\\d{3})")
    private val smallInt = Regex("(?<!\\d)(\\d{1,2})(?!\\d)")
    private val fromName = Regex("من\\s*:\\s*([^\\n]{2,40})")
    private val toAcctName = Regex("الى[^\\n]*?\\d{3}-\\d{6}-\\d{3}[^\\n]*\\n\\s*([^\\n]{2,40})")
    private val leadJunk = Regex("^[\\d/\\- ]+")
    private val months = mapOf("JAN" to 1,"FEB" to 2,"MAR" to 3,"APR" to 4,"MAY" to 5,"JUN" to 6,
        "JUL" to 7,"AUG" to 8,"SEP" to 9,"OCT" to 10,"NOV" to 11,"DEC" to 12)
    private const val K_SUMMARY = "حسابالجاري"
    private const val K_EJAR = "منصهايجار"
    private val nameStopWords = listOf("تحويل","حواله","حساب","الافراد","شخصي","اخر","هديه",
        "تفاصيل","العمليه","التاريخ","الرجاء","البنك","سحوبات","ايداعات","رصيد","السلعوالخدمات","شراء")
    // high-volume checking-account rows: POS purchases and ATM withdrawals (Spec: 836-131300-001)
    private const val K_POS = "نقاطبيع"
    private const val K_ATM = "سحبنقدي"
    private val madaCard = Regex("بطاقهمدىرقم\\*+(\\d{3,4})")
    private val boilerplateLine = listOf("دفععبرنقاطالبيع", "دفع)مدىاثير(", "مدىاثير", K_POS,
        "شراءعنطريقتطبييق", "دفعمدى", "بطاقهمدى")
    private fun isBoilerplate(line: String): Boolean {
        val ns = noSpace(line)
        return ns.isEmpty() || boilerplateLine.any { ns.contains(it) } || ns.all { it.isDigit() }
    }
    // universal boilerplate that carries no per-transaction information — filtered out of
    // the raw-detail text shown in Review so the signal (city, terminal id, names) stands out
    private val detailNoise = listOf("InternetBankingRiyadh", "TRNDATE", "PERSONALTRANSFERTOIND",
        "PERSONALTRANSFER", noSpace("الرجاء فحص"), noSpace("من افصاح"), "8001166866", "1439",
        "300002613110003", noSpace("الخدمات الشخصية"), noSpace("نامل منكم"), noSpace("الخاصه ببطاقات"))
    private fun rawDetail(meta: String): String? {
        val lines = meta.lines().map { it.trim() }.filter { it.isNotEmpty() }
            .filter { l -> detailNoise.none { n -> noSpace(l).contains(n) } }
        val text = lines.joinToString(" · ")
        return text.take(240).ifEmpty { null }
    }

    /** merchant name sits on the line just before the mada-card line, skipping repeated
     *  type-label lines; extraction pads/truncates merchant+city with "×" as a separator. */
    private fun merchantBeforeMadaLine(meta: String): String? {
        val body = meta.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val cardIdx = body.indexOfFirst { noSpace(it).contains("بطاقهمدى") }
        if (cardIdx <= 0) return null
        var j = cardIdx - 1
        while (j >= 0 && isBoilerplate(body[j])) j--
        if (j < 0) return null
        val seg = body[j].split(Regex("[×xX]+")).firstOrNull()?.trim() ?: return null
        return seg.ifEmpty { null }?.takeIf { it.length >= 3 }
    }

    private fun toksOf(line: String, fracFirst: Boolean): List<Long> {
        val rx = if (fracFirst) tokFracFirst else tokIntFirst
        return rx.findAll(line).mapNotNull { m ->
            val frac = if (fracFirst) m.groupValues[1] else m.groupValues[2]
            val whole = if (fracFirst) m.groupValues[2] else m.groupValues[1]
            val w = whole.replace(",", "").toLongOrNull() ?: return@mapNotNull null
            val f = frac.toLongOrNull() ?: return@mapNotNull null
            w * 100 + f
        }.toList()
    }

    private class RowSolution(val credit: Boolean, val amount: Long, val balanceAfter: Long?)

    // ---------- solver ----------
    private fun solve(
        blockAmounts: List<List<Long>>, closing: Long, depTotal: Long, wTotal: Long, depCnt: Int, wCnt: Int,
    ): List<RowSolution>? {
        val n = blockAmounts.size
        if (n != depCnt + wCnt) return null
        val opening = closing - depTotal + wTotal
        val solutions = mutableListOf<List<RowSolution>>()

        fun dfs(i: Int, bal: Long, dSum: Long, cSum: Long, dN: Int, cN: Int, acc: MutableList<RowSolution>) {
            if (solutions.size > 1) return
            if (i == n) {
                if (bal == closing && dSum == wTotal && cSum == depTotal && dN == wCnt && cN == depCnt)
                    solutions += acc.toList()
                return
            }
            val toks = blockAmounts[i]
            for (aIdx in toks.indices) {
                val a = toks[aIdx]
                if (a <= 0L) continue
                for (credit in listOf(true, false)) {
                    val nb = if (credit) bal + a else bal - a
                    if (nb < 0) continue
                    val nd = if (credit) dSum else dSum + a
                    val nc = if (credit) cSum + a else cSum
                    val ndN = if (credit) dN else dN + 1
                    val ncN = if (credit) cN + 1 else cN
                    if (nd > wTotal || nc > depTotal || ndN > wCnt || ncN > depCnt) continue
                    val balTok: Long?
                    if (toks.size == 1) balTok = null
                    else {
                        if (nb != toks[0]) continue          // first token of a multi-token row is the balance
                        if (aIdx == 0 && toks[0] != toks[1]) continue
                        balTok = nb
                    }
                    acc += RowSolution(credit, a, balTok)
                    dfs(i + 1, nb, nd, nc, ndN, ncN, acc)
                    acc.removeAt(acc.size - 1)
                }
            }
        }
        dfs(0, opening, 0L, 0L, 0, 0, mutableListOf())
        val distinct = solutions.map { s -> s.map { it.credit to it.amount } }.distinct()
        return if (distinct.size == 1) solutions[0] else null
    }

    // ---------- one attempt for a (grammar, meta-side) combination ----------
    private fun attempt(
        lines: List<String>, fracFirst: Boolean, metaAfter: Boolean,
        pStart: java.time.LocalDate?, pEnd: java.time.LocalDate?, zone: java.time.ZoneId,
    ): Pair<List<StmtRow>, Long>? {
        var summary: List<Long>? = null
        var counts: List<Int>? = null
        var summaryIdx = -1
        for ((i, l) in lines.withIndex()) {
            if (!noSpace(l).contains(K_SUMMARY)) continue
            val amts = toksOf(l, fracFirst)
            if (amts.size < 3) continue
            val stripped = (if (fracFirst) tokFracFirst else tokIntFirst).replace(l, " ")
            val ints = smallInt.findAll(stripped).map { it.groupValues[1].toInt() }.toList()
            if (ints.size >= 2) { summary = amts.take(3); counts = ints.take(2); summaryIdx = i; break }
        }
        val sum3 = summary ?: return null
        val cnt2 = counts ?: return null

        val amtIdx = lines.indices.filter { i ->
            i > summaryIdx && !noSpace(lines[i]).contains(K_SUMMARY) && toksOf(lines[i], fracFirst).isNotEmpty()
        }
        if (amtIdx.isEmpty()) return null

        class Chunk(val before: String, val line: String, val after: String, val amounts: List<Long>)
        val chunks = amtIdx.mapIndexed { j, k ->
            val lo = if (j > 0) amtIdx[j - 1] + 1 else summaryIdx + 1
            val hi = if (j + 1 < amtIdx.size) amtIdx[j + 1] else lines.size
            Chunk(lines.subList(lo, k).joinToString("\n"), lines[k],
                lines.subList(k + 1, hi).joinToString("\n"), toksOf(lines[k], fracFirst))
        }

        val perms = listOf(
            Triple(sum3[0], sum3[1], sum3[2]), Triple(sum3[0], sum3[2], sum3[1]),
            Triple(sum3[1], sum3[0], sum3[2]), Triple(sum3[1], sum3[2], sum3[0]),
            Triple(sum3[2], sum3[0], sum3[1]), Triple(sum3[2], sum3[1], sum3[0]))
        val found = mutableListOf<Pair<List<RowSolution>, Long>>()
        for (perm in perms) {
            for (cnt in listOf(cnt2[0] to cnt2[1], cnt2[1] to cnt2[0])) {
                val s = solve(chunks.map { it.amounts }, perm.first, perm.second, perm.third, cnt.first, cnt.second)
                if (s != null) found += s to perm.first
            }
        }
        // one row-level interpretation only — two different consistent readings means refuse
        val vecs = found.map { f -> f.first.map { it.credit to it.amount } }.distinct()
        if (vecs.size != 1) return null
        val (sol, closing) = found[0]

        val rows = mutableListOf<StmtRow>()
        for ((i, ch) in chunks.withIndex()) {
            val r = sol[i]
            val meta = (if (metaAfter) ch.after else ch.before) + "\n" + ch.line
            val ns = noSpace(meta)
            val flat = meta.replace(Regex("\\s+"), " ")

            var dayOnly = false
            var at: Long? = embeddedTs.find(meta)?.let { e ->
                try {
                    java.time.LocalDateTime.of(2000 + e.groupValues[3].toInt(), months[e.groupValues[2]] ?: 1,
                        e.groupValues[1].toInt(), e.groupValues[4].toInt(), e.groupValues[5].toInt(), e.groupValues[6].toInt())
                        .atZone(zone).toInstant().toEpochMilli()
                } catch (_: Exception) { null }
            }
            if (at == null) at = trnDate.find(meta)?.let { t ->
                try {
                    val time = refTime.find(meta)
                    java.time.LocalDateTime.of(t.groupValues[1].toInt(), t.groupValues[2].toInt(), t.groupValues[3].toInt(),
                        time?.groupValues?.get(1)?.toInt() ?: 0, time?.groupValues?.get(2)?.toInt() ?: 0,
                        time?.groupValues?.get(3)?.toInt() ?: 0).atZone(zone).toInstant().toEpochMilli()
                } catch (_: Exception) { null }
            }
            if (at == null) {
                dayOnly = true
                var best: java.time.LocalDate? = null
                for (candLine in (ch.before + "\n" + ch.line).lines()) {
                    for (src in listOf(candLine, candLine.reversed())) {
                        for (dm in dateCand.findAll(src)) {
                            val mo = dm.groupValues[1].toInt(); val dy = dm.groupValues[2].toInt()
                            if (mo !in 1..12 || dy !in 1..31) continue
                            for (yr in setOfNotNull(pStart?.year, pEnd?.year)) {
                                val dt = try { java.time.LocalDate.of(yr, mo, dy) } catch (_: Exception) { continue }
                                if (pStart != null && pEnd != null && !dt.isBefore(pStart) && !dt.isAfter(pEnd)) best = dt
                            }
                        }
                    }
                }
                at = best?.atStartOfDay(zone)?.toInstant()?.toEpochMilli()
            }

            val refM = refRe.find((if (metaAfter) ch.after else ch.before) + "\n" + ch.line)
            val bankRef = refM?.let { "SABCA-" + it.groupValues[1] + it.groupValues[2] }
            val pan = pan16.find(flat)?.groupValues?.get(1)

            var mask: String? = null
            val counterparty: String?
            val type: TxnType
            if (r.credit) {
                val isSalary = ns.contains("رواتب") || ns.contains("PayrollMessage")
                type = if (isSalary) TxnType.INCOME_SALARY else TxnType.TRANSFER_IN
                counterparty = when {
                    isSalary -> "Salary"
                    ns.contains("BUPA") -> "BUPA Arabia (insurance claim)"
                    ns.contains(K_EJAR) || ns.contains("INCOMINGPAYMENT") -> "Ejar rent platform"
                    else -> fromName.find(meta)?.groupValues?.get(1)?.trim()?.replace(leadJunk, "")
                        ?: benfId.find(flat)?.let { "sender B" + it.groupValues[1] }
                }
            } else if (ns.contains(K_ATM)) {
                // POS/ATM rows carry a 16-digit terminal reference that can collide with a
                // card PAN — these two checks MUST run before the card-payment check below.
                type = TxnType.CASH_WITHDRAWAL
                counterparty = "ATM cash withdrawal"
                mask = madaCard.find(ns)?.groupValues?.get(1)
            } else if (ns.contains(K_POS)) {
                type = TxnType.PURCHASE
                counterparty = merchantBeforeMadaLine(meta)
                mask = madaCard.find(ns)?.groupValues?.get(1)
            } else if (pan != null || ns.contains("TransfertoOwnCard") || ns.contains("MastercardPremier")) {
                type = TxnType.CREDIT_CARD_PAYMENT
                mask = pan?.takeLast(4) ?: "9249"
                counterparty = "Own credit card payment"
            } else {
                type = TxnType.TRANSFER_OUT
                counterparty = toAcctName.find(meta)?.groupValues?.get(1)?.trim()?.replace(leadJunk, "")
                    ?: meta.lines().map { it.trim() }
                        .firstOrNull { cand -> cand.length in 4..40
                            && cand.all { c -> c == ' ' || c in 'ء'..'ي' }
                            && nameStopWords.none { noSpace(cand).contains(it) } }
                        ?.replace(leadJunk, "")
                    ?: benfId.find(flat)?.let { "beneficiary B" + it.groupValues[1] }
            }

            rows += StmtRow(bankRef = bankRef, occurredAtMillis = at, dayPrecision = dayOnly,
                direction = if (r.credit) Direction.CREDIT else Direction.DEBIT,
                amountMinor = r.amount, txnType = type, merchantRaw = counterparty,
                instrumentMask = mask, balanceMinor = r.balanceAfter, rawDetail = rawDetail(meta))
        }
        return rows to closing
    }

    // ---------- main ----------
    fun parse(rawText: String): StmtParse {
        val text = orient(normalize(rawText))
        val lines = text.lines()
        val zone = java.time.ZoneId.of("Asia/Riyadh")

        val acctM = acctNoRe.find(text)
        val hint = acctM?.let {
            val a = it.groupValues[1]; val b = it.groupValues[2]; val c = it.groupValues[3]
            // extraction may mirror the dash-separated segments — offer both orders for routing
            "$a-$b-$c|$c-$b-$a"
        } ?: "SAB Current"

        val headerEnd = lines.indexOfFirst { noSpace(it).contains(K_SUMMARY) }
        val headerText = if (headerEnd >= 0) lines.subList(0, headerEnd + 1).joinToString("\n") else text
        val greg = gregFull.findAll(headerText).mapNotNull {
            try { java.time.LocalDate.of(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt()) }
            catch (_: Exception) { null }
        }.toList()
        val pStart = greg.minOrNull()
        val pEnd = greg.maxOrNull()
        val pStartMs = pStart?.atStartOfDay(zone)?.toInstant()?.toEpochMilli()
        val pEndMs = pEnd?.atStartOfDay(zone)?.toInstant()?.toEpochMilli()

        // try both amount grammars and both metadata sides; the solver validates each attempt.
        // Prefer the attempt with complete, unique bank references.
        var best: Pair<List<StmtRow>, Long>? = null
        var bestScore = -1
        for (fracFirst in listOf(true, false)) {
            for (metaAfter in listOf(false, true)) {
                val a = attempt(lines, fracFirst, metaAfter, pStart, pEnd, zone) ?: continue
                val refs = a.first.map { it.bankRef }
                val score = refs.count { it != null } +
                    (if (refs.all { it != null } && refs.distinct().size == refs.size) 1000 else 0)
                if (score > bestScore) { best = a; bestScore = score }
            }
        }
        val ok = best
            ?: return StmtParse(ID, hint, emptyList(),
                listOf("Rows could not be verified against the statement totals — NOT imported, to avoid wrong debit/credit rows. Send this file to the developer."),
                pStartMs, pEndMs, reconciled = false)

        val notes = mutableListOf(
            "Reconciled against statement totals — closing balance " + money(ok.second) + " SAR, exact match")
        return StmtParse(ID, hint, ok.first, notes, pStartMs, pEndMs, reconciled = true)
    }
}

object StatementDetector {
    private fun normalizedNoSpace(s: String): String =
        TextNorm.clean(java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFKC))
            .filter { !it.isWhitespace() }
    fun detect(text: String): String? {
        val ns = normalizedNoSpace(text)
        val nsRev = ns.reversed()
        fun any(key: String) = ns.contains(key) || nsRev.contains(key)
        return when {
            text.contains("SAB Credit Card Statement") -> "SAB_CC"
            text.contains("barq", true) && text.contains("Account Statement") -> "BARQ"
            text.contains("Transaction History") && text.contains("card.sp") -> "MOBILY"
            // word order varies by extractor: match the title word and the account label in any order
            any("بريمير") -> "SAB_CA"
            any("رقمالحساب") || any("الحسابرقم") -> "SAB_CA"
            else -> null
        }
    }
}

/** Barq Account Statement — ID prefixes are the authoritative type source (Spec §14). */
object BarqStmt {
    private val row = Regex(
        "(\\d{1,2}\\s+\\w{3}\\s+\\d{4})\\s+((?:P2P|CHPE|CHW2|CIN2|HPT|LYS)\\w*-\\s*\\w+)\\s+(.+?)\\s+([\\d,]+\\.\\d{2})\\s+([\\d,]+\\.\\d{2})",
        RegexOption.DOT_MATCHES_ALL)
    private val months = mapOf("Jan" to 1,"Feb" to 2,"Mar" to 3,"Apr" to 4,"May" to 5,"Jun" to 6,
        "Jul" to 7,"Aug" to 8,"Sep" to 9,"Oct" to 10,"Nov" to 11,"Dec" to 12)

    fun parse(text: String): StmtParse {
        val rows = mutableListOf<StmtRow>()
        val notes = mutableListOf<String>()
        for (m in row.findAll(text)) {
            val ref = m.groupValues[2].replace(Regex("\\s"), "")
            val details = m.groupValues[3].replace(Regex("\\s+"), " ").trim()
            val amount = Amounts.toMinor(m.groupValues[4])
            val balance = Amounts.toMinor(m.groupValues[5])
            val d = Regex("(\\d{1,2})\\s+(\\w{3})\\s+(\\d{4})").find(m.groupValues[1])!!.groupValues
            val at = try {
                java.time.LocalDate.of(d[3].toInt(), months[d[2]] ?: 1, d[1].toInt())
                    .atStartOfDay(java.time.ZoneId.of("Asia/Riyadh")).toInstant().toEpochMilli()
            } catch (_: Exception) { null }
            val (type, dir) = when {
                ref.startsWith("CHPE") -> TxnType.PURCHASE to Direction.DEBIT
                ref.startsWith("CHW2") -> TxnType.REFUND to Direction.CREDIT
                ref.startsWith("CIN2") -> TxnType.WALLET_TOPUP_RECEIVED to Direction.CREDIT
                ref.startsWith("P2P") -> TxnType.TRANSFER_OUT to Direction.DEBIT
                ref.startsWith("HPT") -> TxnType.TRANSFER_OUT to Direction.DEBIT
                ref.startsWith("LYS") -> TxnType.REBATE to Direction.CREDIT
                else -> TxnType.UNKNOWN to Direction.DEBIT
            }
            rows += StmtRow(bankRef = ref, occurredAtMillis = at, dayPrecision = true,
                direction = dir, amountMinor = amount, txnType = type,
                merchantRaw = if (type == TxnType.PURCHASE) null else details.take(60),
                balanceMinor = balance)
        }
        if (rows.isEmpty()) notes += "No rows matched — layout may have changed"
        return StmtParse("BARQ-STMT-EN-2026.07", "Barq", rows, notes)
    }
}

/** Mobily Pay export — order-agnostic, keyed on 10-digit Transaction ID (Spec §11). */
object MobilyStmt {
    private val row = Regex(
        "(\\d{10})\\s+(Cashback|CUSTOM_CARD_MADAPAY_DEBIT|Internet Purchase|Apple Pay Transaction|Reversal)\\s+([\\d,]+\\.\\d{2})")
    private val ts = Regex("(\\d{2}-\\d{2}-\\d{4})\\s+(\\d{1,2}:\\d{2})\\s*([AP]M)")

    fun parse(text: String): StmtParse {
        val seen = HashSet<String>()
        val rows = mutableListOf<StmtRow>()
        for (m in row.findAll(text)) {
            val id = m.groupValues[1]
            if (!seen.add(id)) continue // text layer emits each row twice
            val window = text.substring(m.range.first, minOf(text.length, m.range.last + 120))
            val at = ts.find(window)?.let { When.parse(it.value) }
            val (type, dir) = when (m.groupValues[2]) {
                "Cashback" -> TxnType.REBATE to Direction.CREDIT
                "Reversal" -> TxnType.REVERSAL to
                    (if (window.contains("card.sp")) Direction.CREDIT else Direction.DEBIT)
                else -> TxnType.PURCHASE to Direction.DEBIT
            }
            val channel = when (m.groupValues[2]) {
                "Internet Purchase" -> "Online"
                "Apple Pay Transaction" -> "Apple Pay"
                "CUSTOM_CARD_MADAPAY_DEBIT" -> "mada card"
                else -> null
            }
            rows += StmtRow(bankRef = "MOB-$id", occurredAtMillis = at, dayPrecision = at == null,
                direction = dir, amountMinor = Amounts.toMinor(m.groupValues[3]), txnType = type,
                merchantRaw = null, instrumentMask = null,
            ).let { if (channel != null) it.copy(merchantRaw = null) else it }
        }
        return StmtParse("MOBILYPAY-PDF-2026.07", "Mobily Pay", rows,
            if (rows.isEmpty()) listOf("No rows matched") else emptyList())
    }
}

/** SAB Credit Card statement — EN tabular, multi-card sections, CR credits (Spec §2). */
object SabCcStmt {
    private val panHeader = Regex("X{6,}(\\d{6})")
    private val dateLine = Regex("^(\\d{2}/\\d{2}/\\d{4})\\s+(.*)$")
    private val tail = Regex("([\\d,]+\\.\\d{2})\\s*(CR)?\\s*$")
    private val fx = Regex("([\\d,]+\\.\\d{2,3})\\s+([A-Z]{3})\\s+([\\d.]+)")

    fun parse(text: String): StmtParse {
        val rows = mutableListOf<StmtRow>()
        val notes = mutableListOf<String>()
        var holder: String? = null
        var mask: String? = null
        var pending: Pair<String, String>? = null // date, accumulated text

        fun flush() {
            val (date, blob0) = pending ?: return
            pending = null
            val blob = blob0.replace(Regex("\\s+"), " ").trim()
            val t = tail.find(blob) ?: return
            val credit = t.groupValues[2] == "CR"
            val amount = Amounts.toMinor(t.groupValues[1])
            var body = blob.substring(0, t.range.first).trim()
            var origMilli: Long? = null; var origCur: String? = null; var fees: Long? = null
            fx.find(body)?.let { f ->
                if (f.groupValues[2] != "SAR") {
                    origMilli = Amounts.toMilli(f.groupValues[1]); origCur = f.groupValues[2]
                }
                body = body.substring(0, f.range.first).trim()
            }
            val lower = body.lowercase()
            val (type, dir) = when {
                "payment received" in lower -> TxnType.CARD_PAYMENT_RECEIVED to Direction.CREDIT
                "fee" in lower && "vat" in lower -> TxnType.FEE to Direction.DEBIT
                "aqsat" in lower -> TxnType.INSTALLMENT_BILLING to Direction.DEBIT
                "tawaruq" in lower || "tawarruq" in lower -> TxnType.TAWARRUQ to (if (credit) Direction.CREDIT else Direction.DEBIT)
                lower.startsWith("barq") || lower.startsWith("stc pay") || lower.startsWith("mobily pay") ->
                    TxnType.TRANSFER_TO_WALLET to Direction.DEBIT
                credit -> TxnType.REFUND to Direction.CREDIT
                else -> TxnType.PURCHASE to Direction.DEBIT
            }
            val at = try {
                val p = date.split("/")
                java.time.LocalDate.of(p[2].toInt(), p[1].toInt(), p[0].toInt())
                    .atStartOfDay(java.time.ZoneId.of("Asia/Riyadh")).toInstant().toEpochMilli()
            } catch (_: Exception) { null }
            rows += StmtRow(bankRef = null, occurredAtMillis = at, dayPrecision = true,
                direction = dir, amountMinor = amount, txnType = type,
                merchantRaw = if (type == TxnType.PURCHASE || type == TxnType.REFUND || type == TxnType.TRANSFER_TO_WALLET) body else body.take(60),
                cardHolder = holder, instrumentMask = mask,
                originalAmountMilli = origMilli, originalCurrency = origCur)
        }

        for (raw in text.lines()) {
            val line = raw.trim()
            panHeader.find(line)?.let { flush(); mask = it.groupValues[1].takeLast(4); return@let }
            if (line.matches(Regex("^[A-Z ]{6,}$")) && mask != null && !line.contains("SAB")) { holder = line.trim(); continue }
            val d = dateLine.find(line)
            if (d != null) { flush(); pending = d.groupValues[1] to d.groupValues[2] }
            else if (pending != null && line.isNotEmpty() && !line.contains("Statement", true))
                pending = pending!!.first to (pending!!.second + " " + line)
        }
        flush()
        if (rows.isEmpty()) notes += "No rows matched — layout may have changed"
        notes += "Control-total reconciliation: best-effort in this version"
        return StmtParse("SAB-CC-EN-2026.01", "SAB Mastercard", rows, notes)
    }
}
