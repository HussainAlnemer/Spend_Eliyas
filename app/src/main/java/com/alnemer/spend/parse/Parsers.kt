package com.alnemer.spend.parse

import com.alnemer.spend.data.Direction
import com.alnemer.spend.data.TxnType
import com.alnemer.spend.parse.Amounts.NUM

/**
 * Text normalization for reliable matching: strips invisible bidi control marks,
 * converts non-breaking/thin spaces to plain spaces, folds Arabic letter variants
 * (ta marbuta -> heh, alef variants -> bare alef). Arabic literals in patterns
 * are written in normalized form with \s+ between words.
 */
object TextNorm {
    private val bidi = Regex("[\u200E\u200F\u061C\u202A-\u202E\u2066-\u2069]")
    private val spaces = Regex("[\u00A0\u2007\u202F\u2009]")
    fun clean(s: String): String = spaces.replace(bidi.replace(s, ""), " ")
        .replace('\u0629', '\u0647')
        .replace(Regex("[\u0623\u0625\u0622]"), "\u0627")
}

/** Stage 0: OTP / promo / informational filtering. */
object PreClassifier {
    private val otp = listOf("OTP:", "do not share", "لا تشارك", "رمز التحقق")
    fun isOtp(body: String) = otp.any { body.contains(it, ignoreCase = true) }
    fun infoReason(body: String): String? = when {
        body.contains("ignore if already paid", true) ||
        (body.contains("Payment on your", true) && body.contains("is due", true)) ->
            "Payment-due reminder — informational; the actual payment is recorded when it happens"
        body.contains("تذكير") && body.contains("سداد") -> "Payment reminder — informational"
        else -> null
    }
}

interface SmsParser {
    val extractorId: String
    fun parse(body: String): ParseResult
}

private fun amt(body: String, vararg labels: String): String? {
    for (l in labels) Regex("$l:?\\s*$NUM", RegexOption.IGNORE_CASE).find(body)?.let { return it.groupValues[1] }
    return null
}

/** SAB — bilingual templates (Parser Spec §13, extended per field testing 10 Jul). */
object SabSms : SmsParser {
    override val extractorId = "SAB-SMS-BI-2026.08"

    private val ccFx = Regex(
        "Credit Card\\s*\\((?:\\*+)?(\\d{4})\\)\\s*was\\s*used at\\s+(.+?)\\s+for\\s+([A-Z]{3})\\s*$NUM\\s+in\\s+([A-Za-z ]+)",
        RegexOption.DOT_MATCHES_ALL)
    private val ccPurchase = Regex(
        "Credit Card\\s*\\((?:\\*+)?(\\d{4})\\)\\s*was\\s*used at\\s+(.+?)\\s+for SAR\\s*$NUM(?:\\s*via\\s+([^\\n]+))?",
        RegexOption.DOT_MATCHES_ALL)
    private val ccCredited = Regex(
        "Credit Card\\s*\\((?:\\*+)?(\\d{4})\\)\\s*was\\s*credited with SAR\\s*$NUM", RegexOption.DOT_MATCHES_ALL)
    private val billPayment = Regex("Bill Payment", RegexOption.IGNORE_CASE)
    private val fundIn = Regex("Fund Transfer Credited", RegexOption.IGNORE_CASE)
    private val fundOut = Regex("Fund Transfer Accepted", RegexOption.IGNORE_CASE)
    private val arPos = Regex("شراء\\s+عبر\\s+نقاط\\s+البيع")
    private val arOut = Regex("حواله\\s+صادره")
    private val arSalary = Regex("حواله\\s+راتب")
    private val arCardPaid = Regex("بطاقه.*تسديد|تسديد.*بطاقه", RegexOption.DOT_MATCHES_ALL)

    private fun bal(body: String) = amt(body, "Balance:\\s*SAR", "Balance")?.let(Amounts::toMinor)

    override fun parse(body: String): ParseResult {
        ccFx.find(body)?.let { m ->
            val totalSar = amt(body, "Total amount in SAR") ?: amt(body, "Amount in SAR") ?: return ParseResult.NoMatch
            return ParseResult.Transaction(ParsedTxn(
                extractorId, "SAB", Direction.DEBIT, Amounts.toMinor(totalSar), When.parse(body), TxnType.PURCHASE,
                merchantRaw = m.groupValues[2].trim(), instrumentMask = m.groupValues[1], accountRefHint = "CC",
                balanceMinor = bal(body),
                originalAmountMilli = Amounts.toMilli(m.groupValues[4]), originalCurrency = m.groupValues[3],
                fxRateMicro = Regex("Exchange rate:\\s*([\\d.]+)").find(body)?.groupValues?.get(1)?.let(Amounts::rateMicro),
                fxFeesMinor = amt(body, "International Fees in SAR")?.let(Amounts::toMinor),
            ))
        }
        ccPurchase.find(body)?.let { m ->
            return ParseResult.Transaction(ParsedTxn(
                extractorId, "SAB", Direction.DEBIT, Amounts.toMinor(m.groupValues[3]), When.parse(body), TxnType.PURCHASE,
                merchantRaw = m.groupValues[2].trim(), channel = m.groupValues[4].trim().ifEmpty { null },
                instrumentMask = m.groupValues[1], accountRefHint = "CC", balanceMinor = bal(body),
            ))
        }
        ccCredited.find(body)?.let { m ->
            return ParseResult.Transaction(ParsedTxn(
                extractorId, "SAB", Direction.CREDIT, Amounts.toMinor(m.groupValues[2]), When.parse(body),
                TxnType.CARD_PAYMENT_RECEIVED, instrumentMask = m.groupValues[1], accountRefHint = "CC",
                balanceMinor = bal(body),
            ))
        }
        if (billPayment.containsMatchIn(body)) {
            val a = Regex("SAR\\s*$NUM\\s+was paid to biller").find(body)?.groupValues?.get(1) ?: return ParseResult.NoMatch
            val biller = Regex(";\\s*([A-Z][A-Z &]+?)\\s+from\\s", RegexOption.IGNORE_CASE).find(body)?.groupValues?.get(1)?.trim()
            val mask = Regex("\\((\\d{4})\\)").find(body)?.groupValues?.get(1)
            val hint = if (body.contains("Credit card", true)) "CC" else null
            return ParseResult.Transaction(ParsedTxn(
                extractorId, "SAB", Direction.DEBIT, Amounts.toMinor(a), When.parse(body), TxnType.BILL_PAYMENT_SADAD,
                merchantRaw = biller, instrumentMask = mask, accountRefHint = hint, balanceMinor = bal(body),
            ))
        }
        if (fundIn.containsMatchIn(body)) {
            val a = amt(body, "Amount:\\s*SAR", "Amount") ?: return ParseResult.NoMatch
            return ParseResult.Transaction(ParsedTxn(
                extractorId, "SAB", Direction.CREDIT, Amounts.toMinor(a), When.parse(body), TxnType.TRANSFER_IN,
                beneficiary = Regex("From:\\s*([^\\n]+)").find(body)?.groupValues?.get(1)?.trim(),
                counterpartyBank = Regex("(Riyad Bank|Saudi National Bank|SAUDI BRITISH BANK|Alrajhi[^\\n]*)", RegexOption.IGNORE_CASE)
                    .find(body)?.groupValues?.get(1),
            ))
        }
        if (fundOut.containsMatchIn(body)) {
            val a = amt(body, "Amount:\\s*SAR", "Amount") ?: return ParseResult.NoMatch
            return ParseResult.Transaction(ParsedTxn(
                extractorId, "SAB", Direction.DEBIT, Amounts.toMinor(a), When.parse(body), TxnType.TRANSFER_OUT,
                beneficiary = Regex("To:\\s*([^\\n]+)").find(body)?.groupValues?.get(1)?.trim(),
                counterpartyBank = Regex("(Riyad Bank|Saudi National Bank|SNB|Alrajhi[^\\n]*)", RegexOption.IGNORE_CASE)
                    .find(body)?.groupValues?.get(1),
            ))
        }
        if (arSalary.containsMatchIn(body)) {
            val a = Regex("SAR\\s*$NUM").find(body)?.groupValues?.get(1) ?: return ParseResult.NoMatch
            return ParseResult.Transaction(ParsedTxn(
                extractorId, "SAB", Direction.CREDIT, Amounts.toMinor(a), When.parse(body), TxnType.INCOME_SALARY,
                merchantRaw = "Salary",
            ))
        }
        if (arCardPaid.containsMatchIn(body)) {
            val a = Regex("SAR\\s*$NUM").find(body)?.groupValues?.get(1) ?: return ParseResult.NoMatch
            val mask = Regex("(\\d{4})\\*{2,}").find(body)?.groupValues?.get(1)
            return ParseResult.Transaction(ParsedTxn(
                extractorId, "SAB", Direction.CREDIT, Amounts.toMinor(a), When.parse(body), TxnType.CARD_PAYMENT_RECEIVED,
                instrumentMask = mask, accountRefHint = "CC",
            ))
        }
        if (arPos.containsMatchIn(body)) {
            val a = Regex("SAR\\s*$NUM").find(body)?.groupValues?.get(1) ?: return ParseResult.NoMatch
            return ParseResult.Transaction(ParsedTxn(
                extractorId, "SAB", Direction.DEBIT, Amounts.toMinor(a), When.parse(body), TxnType.PURCHASE,
                merchantRaw = Regex("(?:لدى|لدي)\\s*:?\\s*([^\\n]+)").find(body)?.groupValues?.get(1)?.trim(),
                channel = if (body.contains("mada Pay")) "mada Pay" else "mada POS",
                instrumentMask = Regex("(\\d{4})\\*{2,}").find(body)?.groupValues?.get(1),
            ))
        }
        if (body.contains("Card Purchase", true) && body.contains("Current balance", true)) {
            val a = amt(body, "Amount") ?: return ParseResult.NoMatch
            val mask = Regex("Card Number:?\\s*[A-Z]*\\*{2,}(\\d{4})", RegexOption.IGNORE_CASE).find(body)?.groupValues?.get(1)
            return ParseResult.Transaction(ParsedTxn(
                extractorId, "SAB", Direction.DEBIT, Amounts.toMinor(a), When.parse(body), TxnType.PURCHASE,
                merchantRaw = Regex("At:?\\s*([^\\n]+)", RegexOption.IGNORE_CASE).find(body)?.groupValues?.get(1)?.trim(),
                instrumentMask = mask, accountRefHint = "VISA-CC",
                balanceMinor = amt(body, "Current balance")?.let(Amounts::toMinor),
            ))
        }
        if (arOut.containsMatchIn(body)) {
            val a = Regex("SAR\\s*$NUM").find(body)?.groupValues?.get(1) ?: return ParseResult.NoMatch
            return ParseResult.Transaction(ParsedTxn(
                extractorId, "SAB", Direction.DEBIT, Amounts.toMinor(a), When.parse(body), TxnType.TRANSFER_OUT,
                beneficiary = Regex("(?:الى)\\s*:?\\s*([^\\n]+)").find(body)?.groupValues?.get(1)?.trim(),
            ))
        }
        return ParseResult.NoMatch
    }
}

/** Barq — key:value English with colon-less variants (Parser Spec §10, extended). */
object BarqSms : SmsParser {
    override val extractorId = "BARQ-SMS-EN-2026.08"

    override fun parse(body: String): ParseResult {
        if (PreClassifier.isOtp(body)) return ParseResult.Ignored("OTP — amounts inside OTPs are never transactions")
        val a = amt(body, "Amount", "amount") ?: return ParseResult.NoMatch
        val hasBarqMarkers = body.contains("Barq", true) || body.contains("A/C", true) ||
            Regex("(?:Mada|VISA|Visa) card", RegexOption.IGNORE_CASE).containsMatchIn(body) ||
            body.contains("Debit Transfer", true)
        if (!hasBarqMarkers) return ParseResult.NoMatch

        val mask = Regex("(?:Mada|VISA|Visa) card:?\\s*\\*{2}(\\d{4})", RegexOption.IGNORE_CASE).find(body)?.groupValues?.get(1)
        val acc = Regex("A/C:?\\s*(\\*{2}\\d{4})").findAll(body).lastOrNull()?.groupValues?.get(1)
        val at = Regex("At:?\\s*([^\\n]*)", RegexOption.IGNORE_CASE).find(body)?.groupValues?.get(1)?.trim()?.ifEmpty { null }
        val to = Regex("To:?\\s+([^,\\n]+)").find(body)?.groupValues?.get(1)?.trim()
        val bal = amt(body, "Balance")?.let(Amounts::toMinor)
        val channel = if (body.contains("Apple Pay", true)) "Apple Pay" else null

        val (type, dir) = when {
            body.contains("Refund", true) -> TxnType.REFUND to Direction.CREDIT
            body.contains("Money Added", true) -> TxnType.WALLET_TOPUP_RECEIVED to Direction.CREDIT
            body.contains("Debit Transfer", true) -> TxnType.TRANSFER_OUT to Direction.DEBIT
            body.contains("Purchase", true) -> TxnType.PURCHASE to Direction.DEBIT
            else -> TxnType.UNKNOWN to Direction.DEBIT
        }
        val fundingCard = if (type == TxnType.WALLET_TOPUP_RECEIVED)
            Regex("card number:?\\s*\\*{2}(\\d{4})", RegexOption.IGNORE_CASE).find(body)?.groupValues?.get(1) else null

        return ParseResult.Transaction(ParsedTxn(
            extractorId, "Barq", dir, Amounts.toMinor(a), When.parse(body), type,
            merchantRaw = if (type == TxnType.PURCHASE) at else null,
            beneficiary = to ?: fundingCard?.let { "funded from card **$it" },
            channel = channel,
            instrumentMask = if (type == TxnType.WALLET_TOPUP_RECEIVED) null else mask,
            accountRefHint = acc, balanceMinor = bal,
        ))
    }
}

/** Emirates NBD KSA (Parser Spec §15). */
object EnbdSms : SmsParser {
    override val extractorId = "ENBD-SMS-EN-2026.07"
    override fun parse(body: String): ParseResult {
        if (body.contains("Mudarabah", true) && body.contains("Profit", true)) {
            val a = Regex("Profit of SAR\\s*$NUM").find(body)?.groupValues?.get(1) ?: return ParseResult.NoMatch
            return ParseResult.Transaction(ParsedTxn(extractorId, "ENBD", Direction.CREDIT,
                Amounts.toMinor(a), When.parse(body), TxnType.INCOME_INVESTMENT, merchantRaw = "Mudarabah profit"))
        }
        if (body.contains("Withdrawal: ATM", true)) {
            val a = amt(body, "Amount:\\s*SAR", "Amount") ?: return ParseResult.NoMatch
            val atm = Regex("At:\\s*([^\\n]+)").find(body)?.groupValues?.get(1)?.trim()
            return ParseResult.Transaction(ParsedTxn(extractorId, "ENBD", Direction.DEBIT,
                Amounts.toMinor(a), When.parse(body), TxnType.CASH_WITHDRAWAL, merchantRaw = "ATM ${atm ?: ""}".trim()))
        }
        if (body.contains("Incoming Fund Transfer", true)) {
            val a = amt(body, "Amount") ?: return ParseResult.NoMatch
            return ParseResult.Transaction(ParsedTxn(extractorId, "ENBD", Direction.CREDIT,
                Amounts.toMinor(a), When.parse(body), TxnType.TRANSFER_IN,
                beneficiary = Regex("From:\\s*([^\\n]+)").find(body)?.groupValues?.get(1)?.trim()))
        }
        return ParseResult.NoMatch
    }
}

/**
 * Saudi National Bank (SNB) — Arabic templates, built from 5 real message samples (Aug 2026):
 * POS purchase, online purchase, transfer between own accounts, credit-card settlement/top-up,
 * automatic top-up. SNB reports amounts as either "NUM SAR" or "SAR NUM" depending on message
 * type (see sarAmt()), and asterisk placement around a masked ref also varies by message type
 * (digits-then-asterisk for the "own accounts" transfer, asterisk-then-digits for the card
 * settlement) — each branch below matches its own real sample, not a guessed common format.
 * Not unit-tested (no harness in this repo yet — see doc §10); validate via Tools → paste SMS
 * before trusting it against live notifications.
 */
object SnbSms : SmsParser {
    override val extractorId = "SNB-SMS-AR-2026.08"

    // SNB sometimes writes "مبلغ 500 SAR" and sometimes "مبلغ SAR 144.00" — handle both orders.
    private fun sarAmt(body: String, label: String): String? =
        Regex("$label:?\\s*(?:SAR\\s*)?$NUM", RegexOption.IGNORE_CASE).find(body)?.groupValues?.get(1)
    private fun cardMask(body: String): String? = Regex("\\*{2,}(\\d{4})").find(body)?.groupValues?.get(1)
    private fun remaining(body: String) = sarAmt(body, "الصرف\\s+المتبقي")?.let(Amounts::toMinor)
    private fun merchantAfter(body: String) = Regex("من\\s+([^\\n]+)").find(body)?.groupValues?.get(1)?.trim()

    private val posPurchase = Regex("شراء\\s*-\\s*POS", RegexOption.IGNORE_CASE)
    private val netPurchase = Regex("شراء\\s+انترنت")
    private val ownTransfer = Regex("حواله\\s+بين\\s+حساباتك")
    private val ccSettle = Regex("سداد\\s+بطاقه\\s+ائتمانيه")
    private val autoTopup = Regex("اضافه\\s+رصيد\\s+تلقائي")
    private val outDomestic = Regex("حواله\\s+صادره\\s+محليه")   // to an external bank (Riyad Bank, D360, …)
    private val inDomestic = Regex("حواله\\s+وارده\\s+محليه")    // from a person at an external bank
    private val outInternal = Regex("حواله\\s+صادره\\s+داخليه")  // to a linked fintech (Tamra) — no "من" field
    private val citizenIncome = Regex("حواله\\s+وارده\\s+حساب\\s+مواطن")
    private val studentIncome = Regex("حواله\\s+وارده\\s+مكافاه\\s+طلاب")

    override fun parse(body: String): ParseResult {
        if (posPurchase.containsMatchIn(body)) {
            val a = sarAmt(body, "بـ") ?: return ParseResult.NoMatch
            return ParseResult.Transaction(ParsedTxn(
                extractorId, "SNB", Direction.DEBIT, Amounts.toMinor(a), When.parse(body), TxnType.PURCHASE,
                merchantRaw = merchantAfter(body),
                channel = Regex("\\(([^)]+)\\)").find(body)?.groupValues?.get(1),
                instrumentMask = cardMask(body), balanceMinor = remaining(body),
            ))
        }
        if (netPurchase.containsMatchIn(body)) {
            val a = sarAmt(body, "مبلغ") ?: return ParseResult.NoMatch
            return ParseResult.Transaction(ParsedTxn(
                extractorId, "SNB", Direction.DEBIT, Amounts.toMinor(a), When.parse(body), TxnType.PURCHASE,
                merchantRaw = merchantAfter(body), channel = "Internet",
                instrumentMask = cardMask(body), balanceMinor = remaining(body),
            ))
        }
        if (ownTransfer.containsMatchIn(body)) {
            val a = sarAmt(body, "مبلغ") ?: return ParseResult.NoMatch
            val from = Regex("من\\s+(\\d{4})\\*").find(body)?.groupValues?.get(1)
            val to = Regex("الى\\s+(\\d{4})\\*").find(body)?.groupValues?.get(1)
            // SNB itself vouches this is a self-transfer ("بين حساباتك") — still routed through
            // Review like any TRANSFER_OUT for now rather than auto-declared; see chat notes.
            return ParseResult.Transaction(ParsedTxn(
                extractorId, "SNB", Direction.DEBIT, Amounts.toMinor(a), When.parse(body), TxnType.TRANSFER_OUT,
                beneficiary = to?.let { "Own account ****$it" }, accountRefHint = from,
            ))
        }
        if (ccSettle.containsMatchIn(body)) {
            val a = sarAmt(body, "مبلغ") ?: return ParseResult.NoMatch
            val fromAcc = Regex("من\\s+حساب\\s+\\*(\\d{4})").find(body)?.groupValues?.get(1)
            return ParseResult.Transaction(ParsedTxn(
                extractorId, "SNB", Direction.CREDIT, Amounts.toMinor(a), When.parse(body), TxnType.CARD_PAYMENT_RECEIVED,
                beneficiary = fromAcc?.let { "from own account ****$it" },
                instrumentMask = cardMask(body), balanceMinor = remaining(body),
            ))
        }
        if (autoTopup.containsMatchIn(body)) {
            val a = sarAmt(body, "مبلغ") ?: return ParseResult.NoMatch
            return ParseResult.Transaction(ParsedTxn(
                extractorId, "SNB", Direction.CREDIT, Amounts.toMinor(a), When.parse(body), TxnType.CARD_PAYMENT_RECEIVED,
                channel = "Automatic", instrumentMask = cardMask(body), balanceMinor = remaining(body),
            ))
        }
        if (outDomestic.containsMatchIn(body)) {
            val a = sarAmt(body, "مبلغ") ?: return ParseResult.NoMatch
            val from = Regex("من:\\s*(\\d{4})\\*").find(body)?.groupValues?.get(1)
            val name = Regex("الى:\\s*([^\\n]+)").find(body)?.groupValues?.get(1)?.trim()
            val bank = Regex("عبر:\\s*([^\\n]+)").find(body)?.groupValues?.get(1)?.trim()
            return ParseResult.Transaction(ParsedTxn(
                extractorId, "SNB", Direction.DEBIT, Amounts.toMinor(a), When.parse(body), TxnType.TRANSFER_OUT,
                beneficiary = name, counterpartyBank = bank, accountRefHint = from,
            ))
        }
        if (inDomestic.containsMatchIn(body)) {
            val a = sarAmt(body, "مبلغ") ?: return ParseResult.NoMatch
            val to = Regex("الى:\\s*(\\d{4})\\*").find(body)?.groupValues?.get(1)
            val sender = Regex("من:\\s*([^\\n]+)").find(body)?.groupValues?.get(1)?.trim()
            val bank = Regex("عبر:\\s*([^\\n]+)").find(body)?.groupValues?.get(1)?.trim()
            return ParseResult.Transaction(ParsedTxn(
                extractorId, "SNB", Direction.CREDIT, Amounts.toMinor(a), When.parse(body), TxnType.TRANSFER_IN,
                beneficiary = sender, counterpartyBank = bank, accountRefHint = to,
            ))
        }
        if (outInternal.containsMatchIn(body)) {
            val a = sarAmt(body, "مبلغ") ?: return ParseResult.NoMatch
            // No "من" in this template — SNB doesn't say which of your accounts funds a transfer
            // to a linked fintech like Tamra. accountRefHint stays null on purpose: it lands in
            // quarantine rather than guessing the wrong account — flagged to Eliyas in chat.
            val name = Regex("الى:\\s*([^\\n]+)").findAll(body).map { it.groupValues[1].trim() }.toList().getOrNull(0)
            return ParseResult.Transaction(ParsedTxn(
                extractorId, "SNB", Direction.DEBIT, Amounts.toMinor(a), When.parse(body), TxnType.TRANSFER_OUT,
                beneficiary = name,
            ))
        }
        if (citizenIncome.containsMatchIn(body)) {
            val a = sarAmt(body, "مبلغ") ?: return ParseResult.NoMatch
            val to = Regex("حساب\\s*(\\d{4})\\*").find(body)?.groupValues?.get(1)
            return ParseResult.Transaction(ParsedTxn(
                extractorId, "SNB", Direction.CREDIT, Amounts.toMinor(a), When.parse(body), TxnType.INCOME_SALARY,
                merchantRaw = "Citizens Account", accountRefHint = to,
            ))
        }
        if (studentIncome.containsMatchIn(body)) {
            val a = sarAmt(body, "مبلغ") ?: return ParseResult.NoMatch
            val to = Regex("حساب\\s*(\\d{4})\\*").find(body)?.groupValues?.get(1)
            return ParseResult.Transaction(ParsedTxn(
                extractorId, "SNB", Direction.CREDIT, Amounts.toMinor(a), When.parse(body), TxnType.INCOME_SALARY,
                merchantRaw = "Student Reward", accountRefHint = to,
            ))
        }
        return ParseResult.NoMatch
    }
}

/** Last-resort recognizer for card messages from not-yet-registered banks. */
object GenericCardSms : SmsParser {
    override val extractorId = "GENERIC-CARD-2026.08"
    override fun parse(body: String): ParseResult {
        if (!body.contains("Card Purchase", true) && !body.contains("Current balance", true)) return ParseResult.NoMatch
        val a = amt(body, "Amount") ?: return ParseResult.NoMatch
        val mask = Regex("Card Number:?\\s*[A-Z]*\\*{2,}(\\d{4})", RegexOption.IGNORE_CASE).find(body)?.groupValues?.get(1)
        return ParseResult.Transaction(ParsedTxn(
            extractorId, "UNREGISTERED-BANK", Direction.DEBIT, Amounts.toMinor(a), When.parse(body), TxnType.PURCHASE,
            merchantRaw = Regex("At:?\\s*([^\\n]+)", RegexOption.IGNORE_CASE).find(body)?.groupValues?.get(1)?.trim(),
            instrumentMask = mask,
            balanceMinor = amt(body, "Current balance")?.let(Amounts::toMinor),
        ))
    }
}

object ParserRegistry {
    private val parsers = listOf(SabSms, BarqSms, EnbdSms, SnbSms, GenericCardSms)
    fun parse(senderHint: String?, rawBody: String): Pair<String, ParseResult> {
        val body = TextNorm.clean(rawBody)
        if (PreClassifier.isOtp(body)) return "pre-classifier" to ParseResult.Ignored("OTP — ignored by design")
        PreClassifier.infoReason(body)?.let { return "pre-classifier" to ParseResult.Ignored(it) }
        val ordered = when {
            senderHint?.contains("SAB", true) == true -> listOf(SabSms, BarqSms, EnbdSms, SnbSms, GenericCardSms)
            senderHint?.contains("barq", true) == true -> listOf(BarqSms, SabSms, EnbdSms, SnbSms, GenericCardSms)
            senderHint?.contains("NBD", true) == true -> listOf(EnbdSms, SabSms, BarqSms, SnbSms, GenericCardSms)
            senderHint?.contains("SNB", true) == true -> listOf(SnbSms, SabSms, BarqSms, EnbdSms, GenericCardSms)
            senderHint?.contains("Ahli", true) == true -> listOf(SnbSms, SabSms, BarqSms, EnbdSms, GenericCardSms)
            else -> parsers
        }
        for (p in ordered) {
            val r = p.parse(body)
            if (r !is ParseResult.NoMatch) return p.extractorId to r
        }
        return "none" to ParseResult.NoMatch
    }
}
