package com.alnemer.spend.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

data class CatTotal(val categoryId: Long?, val total: Long)
data class TypeTotal(val txnType: TxnType, val total: Long)

@Dao
interface AccountDao {
    @Insert suspend fun insert(a: Account): Long
    @Insert suspend fun insertInstitution(i: Institution): Long
    @Insert suspend fun insertInstrument(i: Instrument): Long
    @Query("SELECT COUNT(*) FROM account") suspend fun count(): Int
    @Query("SELECT COUNT(*) FROM institution") suspend fun institutionCount(): Int
    @Query("SELECT * FROM account") suspend fun all(): List<Account>
    @Query("SELECT COUNT(*) FROM instrument WHERE mask = :mask") suspend fun instrumentCount(mask: String): Int
    @Query("SELECT accountId FROM instrument WHERE mask = :mask LIMIT 1") suspend fun accountIdByMask(mask: String): Long?
    @Query("UPDATE account SET accountRef = :ref WHERE id = :id") suspend fun setAccountRef(id: Long, ref: String)
}

@Dao
interface CategoryDao {
    @Insert suspend fun insert(c: Category): Long
    @Query("SELECT COUNT(*) FROM category") suspend fun count(): Int
    @Query("SELECT * FROM category ORDER BY sort") suspend fun all(): List<Category>
    @Query("SELECT id FROM category WHERE nameEn = :nameEn LIMIT 1") suspend fun idByNameEn(nameEn: String): Long?
    @Update suspend fun update(c: Category)
    // system-flagged categories (Income, Transfers, Uncategorized + their children) can never
    // be deleted through this method, even if a UI bug forgets to check — belt and suspenders.
    @Query("DELETE FROM category WHERE id = :id AND system = 0") suspend fun delete(id: Long): Int
    @Query("SELECT COUNT(*) FROM txn WHERE categoryId = :id") suspend fun txnCountUsing(id: Long): Int
    @Query("SELECT COUNT(*) FROM rule_merchant WHERE categoryId = :id") suspend fun merchantRuleCountUsing(id: Long): Int
    @Query("UPDATE txn SET categoryId = NULL, classifiedBy = 'NONE' WHERE categoryId = :id")
    suspend fun reassignTxnsToUncategorized(id: Long): Int
    @Query("DELETE FROM rule_merchant WHERE categoryId = :id") suspend fun deleteMerchantRulesUsing(id: Long): Int
}

@Dao
interface MerchantDao {
    @Insert suspend fun insert(m: Merchant): Long
    @Insert suspend fun insertAlias(a: MerchantAlias): Long
    @Query("SELECT COUNT(*) FROM merchant") suspend fun count(): Int
    @Query("SELECT COUNT(*) FROM merchant_alias") suspend fun aliasCount(): Int
    @Query("SELECT m.* FROM merchant m JOIN merchant_alias a ON a.merchantId = m.id WHERE a.alias = :alias LIMIT 1")
    suspend fun byAlias(alias: String): Merchant?
}

@Dao
interface TxnDao {
    @Insert suspend fun insert(t: Txn): Long
    @Update suspend fun update(t: Txn)
    @Insert suspend fun insertSighting(s: Sighting): Long
    @Query("SELECT * FROM sighting WHERE txnId = :id ORDER BY seenAt DESC LIMIT 1")
    suspend fun latestSightingFor(id: Long): Sighting?
    @Query("SELECT COUNT(*) FROM txn") suspend fun count(): Int
    @Query("SELECT * FROM txn WHERE bankRef = :ref LIMIT 1") suspend fun byBankRef(ref: String): Txn?
    @Query("SELECT * FROM txn WHERE dedupGroup = :g") suspend fun byDedupGroup(g: String): List<Txn>
    @Query("SELECT * FROM txn WHERE accountId = :acc AND occurredAt BETWEEN :from AND :to ORDER BY occurredAt DESC")
    suspend fun forAccountBetween(acc: Long, from: Long, to: Long): List<Txn>
    @Query("SELECT * FROM txn ORDER BY occurredAt DESC LIMIT :n") suspend fun recent(n: Int): List<Txn>
    @Query("SELECT * FROM txn WHERE txnType = :t ORDER BY occurredAt DESC") suspend fun byType(t: TxnType): List<Txn>
    @Query("SELECT COALESCE(SUM(CASE WHEN direction = 'DEBIT' THEN amountSar ELSE -amountSar END), 0) FROM txn WHERE includeInSpend = 1 AND occurredAt >= :from")
    suspend fun trueSpendSince(from: Long): Long
    @Query("SELECT COALESCE(SUM(CASE WHEN direction = 'DEBIT' THEN amountSar ELSE -amountSar END), 0) FROM txn WHERE includeInSpend = 1 AND occurredAt BETWEEN :from AND :to")
    suspend fun trueSpendBetween(from: Long, to: Long): Long
    @Query("SELECT COALESCE(SUM(CASE WHEN direction = 'DEBIT' THEN amountSar ELSE -amountSar END), 0) FROM txn WHERE occurredAt BETWEEN :from AND :to")
    suspend fun grossBetween(from: Long, to: Long): Long
    @Query("SELECT categoryId, SUM(CASE WHEN direction = 'DEBIT' THEN amountSar ELSE -amountSar END) AS total FROM txn WHERE includeInSpend = 1 AND occurredAt BETWEEN :from AND :to GROUP BY categoryId")
    suspend fun spendByCategoryBetween(from: Long, to: Long): List<CatTotal>
    @Query("SELECT txnType, SUM(amountSar) AS total FROM txn WHERE direction = 'DEBIT' AND txnType IN ('CREDIT_CARD_PAYMENT','TRANSFER_TO_WALLET') AND occurredAt BETWEEN :from AND :to GROUP BY txnType")
    suspend fun internalTransfersBetween(from: Long, to: Long): List<TypeTotal>
    @Query("SELECT COALESCE(SUM(amountSar), 0) FROM txn WHERE txnType IN ('INCOME_SALARY','INCOME_INVESTMENT') AND occurredAt BETWEEN :from AND :to")
    suspend fun strictIncomeBetween(from: Long, to: Long): Long
    @Query("SELECT COALESCE(SUM(amountSar), 0) FROM txn WHERE txnType = 'TRANSFER_IN' AND occurredAt BETWEEN :from AND :to")
    suspend fun transfersInBetween(from: Long, to: Long): Long
    @Query("UPDATE txn SET categoryId = NULL, classifiedBy = 'NONE' WHERE txnType = 'TRANSFER_OUT' AND classifiedBy = 'SEED'")
    suspend fun resetSeededTransferCategories(): Int
    @Query("SELECT COALESCE(SUM(amountSar), 0) FROM txn WHERE txnType = 'REBATE' AND occurredAt BETWEEN :from AND :to")
    suspend fun cashbackBetween(from: Long, to: Long): Long
    @Query("SELECT MAX(occurredAt) FROM txn") suspend fun latestTxnAt(): Long?
    @Query("SELECT COALESCE(SUM(CASE WHEN direction = 'DEBIT' THEN amountSar ELSE -amountSar END), 0) FROM txn WHERE occurredAt >= :from")
    suspend fun grossSince(from: Long): Long
    @Query("SELECT * FROM txn WHERE categoryId IS NULL AND txnType NOT IN ('TAWARRUQ') ORDER BY occurredAt DESC")
    suspend fun uncategorized(): List<Txn>
    @Query("SELECT categoryId, SUM(CASE WHEN direction = 'DEBIT' THEN amountSar ELSE -amountSar END) AS total FROM txn WHERE includeInSpend = 1 AND occurredAt >= :from GROUP BY categoryId")
    suspend fun spendByCategory(from: Long): List<CatTotal>
    @Query("SELECT COALESCE(SUM(amountSar), 0) FROM txn WHERE txnType IN ('INCOME_SALARY','INCOME_INVESTMENT','TRANSFER_IN') AND occurredAt >= :from")
    suspend fun incomeSince(from: Long): Long
    @Query("SELECT COALESCE(SUM(amountSar), 0) FROM txn WHERE txnType = 'REBATE' AND occurredAt >= :from")
    suspend fun cashbackSince(from: Long): Long
    @Query("UPDATE txn SET categoryId = :cat, classifiedBy = 'MERCHANT_RULE' WHERE merchantRaw = :merchantRaw AND classifiedBy NOT IN ('MANUAL','RECONCILED')")
    suspend fun applyMerchantCategory(merchantRaw: String, cat: Long): Int
    @Query("""UPDATE txn SET transferToAccountId = :accountId, includeInSpend = 0,
              categoryId = :categoryId, classifiedBy = 'MANUAL' WHERE id = :id""")
    suspend fun setTransferTarget(id: Long, accountId: Long, categoryId: Long?)
    @Query("""SELECT * FROM txn WHERE transferToAccountId IS NOT NULL
              AND id NOT IN (SELECT txnAId FROM link UNION SELECT txnBId FROM link)""")
    suspend fun declaredUnlinkedTransfers(): List<Txn>
    @Query("""SELECT * FROM txn WHERE accountId = :acc AND direction = :dir AND amountSar = :amt
              AND occurredAt BETWEEN :from AND :to
              AND id NOT IN (SELECT txnAId FROM link UNION SELECT txnBId FROM link)""")
    suspend fun candidatesFor(acc: Long, dir: Direction, amt: Long, from: Long, to: Long): List<Txn>
}

@Dao
interface IngestDao {
    @Insert suspend fun insertRaw(r: RawMessage): Long
    @Insert suspend fun insertQuarantine(q: Quarantine): Long
    @Insert suspend fun insertCheckpoint(b: BalanceCheckpoint): Long
    @Insert suspend fun insertStatementImport(s: StatementImport): Long
    @Query("SELECT COUNT(*) FROM quarantine WHERE resolvedTxnId IS NULL") suspend fun openQuarantineCount(): Int
    @Query("SELECT * FROM quarantine WHERE resolvedTxnId IS NULL ORDER BY createdAt DESC") suspend fun openQuarantine(): List<Quarantine>
    @Query("DELETE FROM quarantine WHERE id = :id") suspend fun deleteQuarantine(id: Long)
    @Query("SELECT * FROM balance_checkpoint WHERE accountId = :a ORDER BY at DESC LIMIT 1")
    suspend fun latestCheckpoint(a: Long): BalanceCheckpoint?
}

@Dao
interface RuleDao {
    @Insert suspend fun insertMerchantRule(r: RuleMerchant): Long
    @Insert suspend fun insertPatternRule(r: RulePattern): Long
    @Insert suspend fun insertLink(l: Link): Long
    @Query("DELETE FROM link WHERE id = :id") suspend fun unlink(id: Long)
    @Query("SELECT COUNT(*) FROM rule_merchant") suspend fun merchantRuleCount(): Int
    @Query("SELECT COUNT(*) FROM link") suspend fun linkCount(): Int
    @Query("SELECT txnAId FROM link UNION SELECT txnBId FROM link") suspend fun linkedTxnIds(): List<Long>
}
