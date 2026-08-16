package com.alnemer.spend.ingest

import com.alnemer.spend.data.*
import kotlin.math.abs

/**
 * Matching engine v0 — deterministic + scored heuristics ("Option B with rails").
 * All matches create reversible Link rows; nothing is merged or deleted.
 */
class Matcher(private val db: SpendDb) {

    suspend fun run(): String {
        val linked = db.rules().linkedTxnIds().toHashSet()
        var made = 0
        made += pair(TxnType.TRANSFER_TO_WALLET, TxnType.WALLET_TOPUP_RECEIVED,
            LinkType.WALLET_TOPUP, daysWindow = 2, linked)
        made += pair(TxnType.CREDIT_CARD_PAYMENT, TxnType.CARD_PAYMENT_RECEIVED,
            LinkType.CARD_PAYMENT, daysWindow = 5, linked)
        made += pairCardPaymentFromTransfers(linked)
        made += pairRebates(linked)
        made += pairRefunds(linked)
        made += pairDeclaredTransfers(linked)
        return if (made == 0) "no new links" else "$made new links"
    }

    /** amount-equal cross-account pairing within a day window */
    private suspend fun pair(aType: TxnType, bType: TxnType, link: LinkType, daysWindow: Int, linked: HashSet<Long>): Int {
        val w = daysWindow * 86400000L
        val bs = db.txns().byType(bType).filter { it.id !in linked }.toMutableList()
        var n = 0
        for (a in db.txns().byType(aType).filter { it.id !in linked }) {
            val b = bs.firstOrNull { it.amountSar == a.amountSar && abs(it.occurredAt - a.occurredAt) <= w && it.accountId != a.accountId }
                ?: continue
            makeLink(a, b, link, LinkMethod.DETERMINISTIC, 1.0f)
            bs.remove(b); linked.add(a.id); linked.add(b.id); n++
        }
        return n
    }

    /** SAB SMS outgoing transfers that actually paid a card (amount matches a card credit) */
    private suspend fun pairCardPaymentFromTransfers(linked: HashSet<Long>): Int {
        val w = 5 * 86400000L
        val credits = db.txns().byType(TxnType.CARD_PAYMENT_RECEIVED).filter { it.id !in linked }.toMutableList()
        var n = 0
        for (t in db.txns().byType(TxnType.TRANSFER_OUT).filter { it.id !in linked }) {
            val c = credits.firstOrNull { it.amountSar == t.amountSar && abs(it.occurredAt - t.occurredAt) <= w }
                ?: continue
            // reclassify the transfer leg as a card payment (excluded from spend)
            db.txns().update(t.copy(txnType = TxnType.CREDIT_CARD_PAYMENT, includeInSpend = false))
            makeLink(t, c, LinkType.CARD_PAYMENT, LinkMethod.FUZZY_AUTO, 0.97f)
            credits.remove(c); linked.add(t.id); linked.add(c.id); n++
        }
        return n
    }

    /** Mobily-style cashback: same account, ≤3 min apart, rebate ≤ 6% of purchase */
    private suspend fun pairRebates(linked: HashSet<Long>): Int {
        val purchases = db.txns().byType(TxnType.PURCHASE).toMutableList()
        var n = 0
        for (r in db.txns().byType(TxnType.REBATE).filter { it.id !in linked }) {
            val p = purchases.firstOrNull {
                it.accountId == r.accountId && abs(it.occurredAt - r.occurredAt) <= 180000 &&
                r.amountSar in 1..(it.amountSar * 6 / 100 + 5)
            } ?: continue
            makeLink(r, p, LinkType.REBATE_OF, LinkMethod.FUZZY_AUTO, 0.95f)
            linked.add(r.id); n++
        }
        return n
    }

    /** refunds back to their originating purchase: same account, equal amount, 60-day lookback */
    private suspend fun pairRefunds(linked: HashSet<Long>): Int {
        val purchases = db.txns().byType(TxnType.PURCHASE).toMutableList()
        var n = 0
        for (r in db.txns().byType(TxnType.REFUND).filter { it.id !in linked }) {
            val p = purchases.firstOrNull {
                it.accountId == r.accountId && it.amountSar == r.amountSar &&
                r.occurredAt - it.occurredAt in 0..(60L * 86400000)
            } ?: continue
            makeLink(r, p, LinkType.REFUND_OF, LinkMethod.FUZZY_AUTO, 0.9f)
            linked.add(r.id); n++
        }
        return n
    }

    /**
     * Point 1 (user-declared self-transfers): the user tagged a debit or credit as "goes to /
     * comes from my own account X" but the other leg may not exist yet (its statement not
     * imported). Runs automatically after every import, and again on demand from Tools —
     * a transfer only gets linked once BOTH legs are present, and only if the match is
     * unambiguous (never guesses between two equally-plausible candidates).
     */
    private suspend fun pairDeclaredTransfers(linked: HashSet<Long>): Int {
        val w = 5 * 86400000L
        val betweenAccounts = db.categories().idByNameEn("Between my accounts")
        var n = 0
        for (t in db.txns().declaredUnlinkedTransfers()) {
            if (t.id in linked) continue
            val destAccount = t.transferToAccountId ?: continue
            if (destAccount == t.accountId) continue
            val oppositeDir = if (t.direction == Direction.DEBIT) Direction.CREDIT else Direction.DEBIT
            val candidates = db.txns().candidatesFor(destAccount, oppositeDir, t.amountSar,
                t.occurredAt - w, t.occurredAt + w).filter { it.id != t.id && it.id !in linked }
            val match = candidates.singleOrNull() ?: continue   // 0 or 2+ candidates: leave for later, never guess
            db.txns().update(match.copy(includeInSpend = false,
                categoryId = betweenAccounts ?: match.categoryId, classifiedBy = ClassifiedBy.RECONCILED))
            makeLink(t, match, LinkType.TRANSFER_PAIR, LinkMethod.FUZZY_AUTO, 0.95f)
            linked.add(t.id); linked.add(match.id); n++
        }
        return n
    }

    private suspend fun makeLink(a: Txn, b: Txn, type: LinkType, method: LinkMethod, conf: Float) {
        db.rules().insertLink(Link(txnAId = a.id, txnBId = b.id, linkType = type,
            method = method, confidence = conf, modelVersion = "heuristic-v0",
            createdAt = System.currentTimeMillis()))
    }
}
