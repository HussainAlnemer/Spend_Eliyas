package com.alnemer.spend.ingest

import com.alnemer.spend.data.*
import com.alnemer.spend.parse.ParseResult
import com.alnemer.spend.parse.ParsedTxn
import com.alnemer.spend.parse.ParserRegistry

data class IngestOutcome(val summary: String, val txnId: Long? = null)

/**
 * Message → ledger pipeline: raw storage, parse, account resolution,
 * occurrence-aware dedup, classification, balance checkpoint.
 */
class Ingestor(private val db: SpendDb) {

    suspend fun ingest(sender: String?, body: String, source: SourceKind): IngestOutcome {
        val now = System.currentTimeMillis()
        val rawId = db.ingest().insertRaw(RawMessage(source = source, sender = sender, receivedAt = now, body = body))
        val (extractorId, result) = ParserRegistry.parse(sender, body)

        return when (result) {
            is ParseResult.Ignored -> IngestOutcome("Ignored: ${result.reason}")
            is ParseResult.NoMatch -> {
                db.ingest().insertQuarantine(Quarantine(
                    rawText = body, source = source, extractorId = null,
                    reason = "No parser matched", createdAt = now))
                IngestOutcome("Not recognized — saved to quarantine for a future parser update")
            }
            is ParseResult.Transaction -> insertTxn(result.txn, rawId, extractorId, source, now)
        }
    }

    private suspend fun insertTxn(p: ParsedTxn, rawId: Long, extractorId: String, source: SourceKind, now: Long): IngestOutcome {
        val accountId = resolveAccount(p) ?: run {
            db.ingest().insertQuarantine(Quarantine(
                rawText = p.toString(), source = source, extractorId = extractorId,
                reason = "Account not resolved (${p.institution}/${p.instrumentMask ?: p.accountRefHint})", createdAt = now))
            return IngestOutcome("Parsed, but couldn't tell which account — quarantined")
        }
        val occurred = p.occurredAtMillis ?: now
        val merchantNorm = p.merchantRaw?.lowercase()?.replace(Regex("\\s+"), " ")?.trim()
        val dedup = "${accountId}|${occurred / 60000}|${merchantNorm ?: p.beneficiary ?: ""}|${p.amountMinor}"

        // Occurrence-aware duplicate suppression: identical (account, minute, merchant, amount)
        // is the same sighting; a different minute is a legitimate repeat (Khaleej Club invariant).
        db.txns().byDedupGroup(dedup).firstOrNull()?.let { existing ->
            db.txns().insertSighting(Sighting(txnId = existing.id, rawMessageId = rawId,
                statementImportId = null, sourceKind = source, excerpt = shortExcerpt(p), seenAt = now))
            return IngestOutcome("Already recorded — added as extra evidence to transaction #${existing.id}", existing.id)
        }

        val (merchantId, categoryId, classifiedBy, finalType, inSpend) = classify(p)
        val txnId = db.txns().insert(Txn(
            accountId = accountId, occurredAt = occurred, direction = p.direction,
            amountSar = p.amountMinor, txnType = finalType,
            originalAmountMilli = p.originalAmountMilli, originalCurrency = p.originalCurrency,
            fxRateMicro = p.fxRateMicro, fxFeesSarMinor = p.fxFeesMinor,
            merchantRaw = p.merchantRaw, merchantId = merchantId, beneficiary = p.beneficiary,
            channel = p.channel, categoryId = categoryId, classifiedBy = classifiedBy,
            includeInSpend = inSpend, status = TxnStatus.PROVISIONAL,
            dedupGroup = dedup,
        ))
        db.txns().insertSighting(Sighting(txnId = txnId, rawMessageId = rawId,
            statementImportId = null, sourceKind = source, excerpt = shortExcerpt(p), seenAt = now))

        if (p.balanceMinor != null) {
            val acc = db.accounts().all().first { it.id == accountId }
            db.ingest().insertCheckpoint(BalanceCheckpoint(
                accountId = accountId, at = occurred, balanceMinor = p.balanceMinor,
                semantics = acc.balanceSemantics))
        } else {
            // Message didn't report its own balance (Citizens account / Student reward, and the
            // Riyad Bank/D360/STC Bank transfer templates, all skip it) — nudge the last known
            // checkpoint by this transaction's amount instead of leaving Home's balance stale.
            // Only if a checkpoint already exists; with no baseline there's nothing to nudge from.
            db.ingest().latestCheckpoint(accountId)?.let { prev ->
                val delta = if (p.direction == Direction.DEBIT) -p.amountMinor else p.amountMinor
                val acc = db.accounts().all().first { it.id == accountId }
                db.ingest().insertCheckpoint(BalanceCheckpoint(
                    accountId = accountId, at = occurred, balanceMinor = prev.balanceMinor + delta,
                    semantics = acc.balanceSemantics))
            }
        }
        val catName = categoryId?.let { id -> db.categories().all().firstOrNull { it.id == id }?.nameEn }
        return IngestOutcome(buildString {
            append("Recorded ${fmt(p.amountMinor)} SAR ")
            append(if (p.direction == Direction.DEBIT) "out" else "in")
            p.merchantRaw?.let { append(" · $it") }
            p.beneficiary?.let { append(" · to $it") }
            append(" · ${finalType.name}")
            append(" · category: ${catName ?: "needs review"}")
            if (!inSpend) append(" · excluded from spend")
        }, txnId)
    }

    private data class Classification(
        val merchantId: Long?, val categoryId: Long?, val by: ClassifiedBy,
        val type: TxnType, val includeInSpend: Boolean)

    private suspend fun classify(p: ParsedTxn): Classification {
        // Income & transfer types are spend-excluded regardless of merchant.
        val nonSpend = setOf(TxnType.TRANSFER_IN, TxnType.INCOME_INVESTMENT, TxnType.INCOME_SALARY,
            TxnType.WALLET_TOPUP_RECEIVED, TxnType.TRANSFER_TO_WALLET, TxnType.CREDIT_CARD_PAYMENT,
            TxnType.CARD_PAYMENT_RECEIVED, TxnType.REBATE)
        val alias = p.merchantRaw?.lowercase()?.replace(Regex("\\s+"), " ")?.trim()
        val merchant = alias?.let { db.merchants().byAlias(it) }
        if (merchant != null) {
            val walletTopup = merchant.canonicalName.contains("top-up")
            val type = if (walletTopup) TxnType.TRANSFER_TO_WALLET else p.txnType
            return Classification(merchant.id, merchant.defaultCategoryId, ClassifiedBy.SEED,
                type, type !in nonSpend && !walletTopup)
        }
        val fallbackCat = when (p.txnType) {
            TxnType.INCOME_INVESTMENT -> db.categories().idByNameEn("Investment profit")
            TxnType.INCOME_SALARY -> db.categories().idByNameEn("Salary")
            TxnType.TRANSFER_IN -> db.categories().idByNameEn("Other income")
            TxnType.TRANSFER_OUT -> null   // user classifies transfers in Review
            TxnType.BILL_PAYMENT_SADAD -> db.categories().idByNameEn("Government (SADAD)")
            TxnType.WALLET_TOPUP_RECEIVED -> db.categories().idByNameEn("Wallet top-ups")
            TxnType.CARD_PAYMENT_RECEIVED -> db.categories().idByNameEn("Card payments")
            TxnType.REFUND -> null
            else -> null
        }
        return Classification(null, fallbackCat,
            if (fallbackCat != null) ClassifiedBy.SEED else ClassifiedBy.NONE,
            p.txnType, p.txnType !in nonSpend)
    }

    private suspend fun resolveAccount(p: ParsedTxn): Long? {
        // 1) instrument mask — data-driven from the `instrument` table (see Seed.kt/repairAccounts()).
        //    Adding a new card later means adding an Instrument row, not touching this function.
        p.instrumentMask?.let { mask -> db.accounts().accountIdByMask(mask)?.let { return it } }
        // 2) account ref hint (last 4 chars) — covers non-card accounts: savings, current, etc.
        p.accountRefHint?.let { ref ->
            db.accounts().all().firstOrNull { a -> a.accountRef?.takeLast(4) == ref.takeLast(4) }?.let { return it.id }
        }
        // 3) single-account-per-institution convenience fallback. Only add a case here for an
        //    institution where every message unambiguously belongs to its one account — anything
        //    with multiple accounts (like SNB) must rely on (1)/(2) or it risks silent misrouting.
        return null
    }

    private fun shortExcerpt(p: ParsedTxn) =
        listOfNotNull(p.merchantRaw, p.beneficiary, "${fmt(p.amountMinor)} SAR").joinToString(" · ")

    private fun fmt(minor: Long) = "%,d.%02d".format(minor / 100, minor % 100)
}
