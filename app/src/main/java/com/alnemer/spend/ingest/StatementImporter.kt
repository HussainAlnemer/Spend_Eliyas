package com.alnemer.spend.ingest

import android.content.Context
import android.net.Uri
import com.alnemer.spend.data.*
import com.alnemer.spend.parse.*
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.security.MessageDigest

/** PDF statement → detect → extract → merge-ingest with SMS-provisional rows. */
class StatementImporter(private val db: SpendDb, private val ctx: Context) {

    suspend fun import(uri: Uri): String {
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return "Could not read the file"
        val text = try {
            PDDocument.load(bytes).use { doc ->
                PDFTextStripper().apply { sortByPosition = true }.getText(doc)
            }
        } catch (e: Exception) { return "Not a readable PDF: ${e.message}" }

        val kind = StatementDetector.detect(text)
            ?: return "Statement type not recognized — send this file to the developer to add support.\n" +
                "Extractor saw: " + text.replace(Regex("\\s+"), " ").trim().take(120)
        val parsed = when (kind) {
            "BARQ" -> BarqStmt.parse(text)
            "MOBILY" -> MobilyStmt.parse(text)
            "SAB_CA" -> SabCaStmt.parse(text)
            else -> SabCcStmt.parse(text)
        }
        // a reader that explicitly failed reconciliation refuses the import — surface its reason
        if (parsed.rows.isEmpty() && parsed.reconciled == false)
            return parsed.notes.joinToString("\n").ifEmpty { "Statement could not be verified — not imported" }

        val account = resolveAccount(parsed.accountHint)
            ?: return "Could not match this statement to one of your accounts (hint: ${parsed.accountHint})"

        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val importId = db.ingest().insertStatementImport(StatementImport(
            accountId = account.id, fileHash = hash,
            periodStart = parsed.periodStartMillis, periodEnd = parsed.periodEndMillis,
            extractorId = parsed.extractorId, extractorVersion = "2",
            reconciliationStatus = when (parsed.reconciled) {
                true -> ReconStatus.PASSED
                false -> ReconStatus.FAILED
                null -> ReconStatus.PARTIAL
            },
            importedAt = System.currentTimeMillis()))

        var new = 0; var merged = 0; var dup = 0; var corrected = 0
        for (r in parsed.rows) {
            val rowAccount = resolveRowAccount(kind, r) ?: account
            when (mergeRow(rowAccount, r, importId)) {
                MergeOutcome.NEW -> new++; MergeOutcome.MERGED -> merged++
                MergeOutcome.DUP -> dup++; MergeOutcome.CORRECTED -> corrected++
            }
        }
        // statements carry authoritative running balances — record the latest as a checkpoint
        parsed.rows.filter { it.balanceMinor != null && it.occurredAtMillis != null }
            .maxByOrNull { it.occurredAtMillis!! }
            ?.let { last ->
                db.ingest().insertCheckpoint(BalanceCheckpoint(
                    accountId = account.id, at = last.occurredAtMillis!!,
                    balanceMinor = last.balanceMinor!!, semantics = account.balanceSemantics))
            }

        val debits = parsed.rows.filter { it.direction == Direction.DEBIT }.sumOf { it.amountMinor }
        val credits = parsed.rows.filter { it.direction == Direction.CREDIT }.sumOf { it.amountMinor }
        return buildString {
            if (new == 0 && merged == 0 && corrected == 0 && dup > 0)
                appendLine("This file was already imported — nothing added, nothing double-counted.")
            appendLine("Imported ${parsed.rows.size} rows into ${account.displayName}:")
            append("• $new new · $merged matched to SMS records (now confirmed) · $dup already imported")
            appendLine(if (corrected > 0) " · $corrected corrected from an earlier import" else "")
            appendLine("• Debits ${fmt(debits)} · Credits ${fmt(credits)} SAR")
            parsed.notes.forEach { appendLine("• $it") }
        }
    }

    private enum class MergeOutcome { NEW, MERGED, DUP, CORRECTED }

    private suspend fun mergeRow(account: Account, r: StmtRow, importId: Long): MergeOutcome {
        val now = System.currentTimeMillis()
        r.bankRef?.let { ref ->
            val existing = db.txns().byBankRef(ref)
            if (existing != null) {
                // same statement row seen before: if the earlier reader got direction/amount/type
                // wrong, the statement is authoritative — fix in place, never duplicate.
                // The matching engine may have upgraded TRANSFER_OUT to CREDIT_CARD_PAYMENT;
                // that upgrade outranks the raw statement type and must not be reverted.
                val typeEquivalent = existing.txnType == r.txnType ||
                    (existing.txnType == TxnType.CREDIT_CARD_PAYMENT && r.txnType == TxnType.TRANSFER_OUT)
                if (existing.direction != r.direction || existing.amountSar != r.amountMinor ||
                    !typeEquivalent) {
                    db.txns().update(existing.copy(
                        direction = r.direction, amountSar = r.amountMinor, txnType = r.txnType,
                        merchantRaw = existing.merchantRaw ?: r.merchantRaw,
                        includeInSpend = r.txnType !in NON_SPEND && r.direction == Direction.DEBIT,
                        statementImportId = importId))
                    db.txns().insertSighting(Sighting(txnId = existing.id, rawMessageId = null,
                        statementImportId = importId, sourceKind = SourceKind.STATEMENT_ROW,
                        excerpt = "corrected: ${r.txnType.name} ${r.direction.name} · " + excerptFor(r), seenAt = now))
                    return MergeOutcome.CORRECTED
                }
                return MergeOutcome.DUP
            }
        }
        // fuzzy merge: provisional SMS row, same account+amount+direction within ±3 days
        val at = r.occurredAtMillis ?: now
        val window = 3L * 24 * 3600 * 1000
        val candidates = db.txns().forAccountBetween(account.id, at - window, at + window)
            .filter { it.status == TxnStatus.PROVISIONAL && it.amountSar == r.amountMinor && it.direction == r.direction }
        val match = candidates.firstOrNull()
        if (match != null) {
            db.txns().update(match.copy(
                status = TxnStatus.CONFIRMED,
                postedAt = r.occurredAtMillis,
                bankRef = r.bankRef ?: match.bankRef,
                merchantRaw = match.merchantRaw ?: r.merchantRaw,
                statementImportId = importId,
                cardHolder = match.cardHolder ?: r.cardHolder))
            db.txns().insertSighting(Sighting(txnId = match.id, rawMessageId = null,
                statementImportId = importId, sourceKind = SourceKind.STATEMENT_ROW,
                excerpt = excerptFor(r), seenAt = now))
            return MergeOutcome.MERGED
        }
        // new confirmed row
        val alias = r.merchantRaw?.lowercase()?.replace(Regex("\\s+"), " ")?.trim()
        val merchant = alias?.let { db.merchants().byAlias(stripCity(it)) }
        val fallbackCat = when (r.txnType) {
            TxnType.TRANSFER_OUT -> null   // user classifies transfers in Review — one decision becomes a rule
            TxnType.TRANSFER_IN -> db.categories().idByNameEn("Other income")
            TxnType.CREDIT_CARD_PAYMENT -> db.categories().idByNameEn("Card payments")
            TxnType.CARD_PAYMENT_RECEIVED -> db.categories().idByNameEn("Card payments")
            TxnType.WALLET_TOPUP_RECEIVED -> db.categories().idByNameEn("Wallet top-ups")
            else -> null
        }
        val catId = merchant?.defaultCategoryId ?: fallbackCat
        val txnId = db.txns().insert(Txn(
            accountId = account.id, occurredAt = at, postedAt = r.occurredAtMillis,
            direction = r.direction, amountSar = r.amountMinor, txnType = r.txnType,
            merchantRaw = r.merchantRaw, merchantId = merchant?.id,
            categoryId = catId,
            classifiedBy = if (catId != null) ClassifiedBy.SEED else ClassifiedBy.NONE,
            includeInSpend = r.txnType !in NON_SPEND && r.direction == Direction.DEBIT,
            status = TxnStatus.CONFIRMED, bankRef = r.bankRef, statementImportId = importId,
            cardHolder = r.cardHolder,
            originalAmountMilli = r.originalAmountMilli, originalCurrency = r.originalCurrency,
            dedupGroup = "${account.id}|stmt|${at / 86400000}|${alias ?: ""}|${r.amountMinor}"))
        db.txns().insertSighting(Sighting(txnId = txnId, rawMessageId = null,
            statementImportId = importId, sourceKind = SourceKind.STATEMENT_ROW,
            excerpt = excerptFor(r), seenAt = now))
        return MergeOutcome.NEW
    }

    private fun stripCity(m: String): String {
        val cities = listOf(" dammam", " jubail", " riyadh", " sehat", " qatif", " taroot", " khobar", " manama")
        var out = m
        for (c in cities) if (out.endsWith(c)) out = out.removeSuffix(c).trim()
        return out
    }

    /** accountRef (statement account number) wins over display-name fuzz. */
    private suspend fun resolveAccount(hint: String): Account? {
        val accounts = db.accounts().all()
        accounts.firstOrNull { a -> a.accountRef != null && (hint.contains(a.accountRef!!) || a.accountRef == hint) }
            ?.let { return it }
        return accounts.firstOrNull { it.displayName.contains(hint, true) || hint.contains(it.displayName, true) }
    }

    /** Credit-card statement rows route by card mask so both SAB cards land correctly. */
    private suspend fun resolveRowAccount(kind: String, r: StmtRow): Account? {
        if (kind != "SAB_CC") return null   // CA rows stay on the current account; mask is metadata
        val mask = r.instrumentMask ?: return null
        val accounts = db.accounts().all()
        return when (mask) {
            "9249", "9572", "9614" -> accounts.firstOrNull { it.displayName.contains("Mastercard") }
            "0657", "9476" -> accounts.firstOrNull { it.displayName.contains("Visa Platinum") }
            else -> null
        }
    }

    companion object {
        private val NON_SPEND = setOf(TxnType.WALLET_TOPUP_RECEIVED, TxnType.TRANSFER_TO_WALLET,
            TxnType.CREDIT_CARD_PAYMENT, TxnType.CARD_PAYMENT_RECEIVED, TxnType.REBATE,
            TxnType.TAWARRUQ, TxnType.TRANSFER_IN)
    }

    // prefer the verbatim statement text (Review needs it to identify ambiguous merchants);
    // fall back to a terse computed line for sources that don't carry raw detail (SMS, paste)
    private fun excerptFor(r: StmtRow) = r.rawDetail
        ?: "${r.merchantRaw ?: r.txnType.name} · ${fmt(r.amountMinor)} SAR"

    private fun fmt(minor: Long) = "%,d.%02d".format(minor / 100, minor % 100)
}
