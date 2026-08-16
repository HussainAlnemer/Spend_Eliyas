package com.alnemer.spend.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// Schema per Data Model & Schema Design v1.0.
// Foreign-key integrity is enforced at repository level (kept out of Room FKs
// deliberately: raw ingestion order is unpredictable; quarantine absorbs orphans).

@Entity(tableName = "institution")
data class Institution(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val kind: String, // BANK / WALLET / FINANCE_CO
)

@Entity(tableName = "account", indices = [Index("institutionId")])
data class Account(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val institutionId: Long,
    val type: AccountType,
    val displayName: String,
    val ibanSuffix: String? = null,
    val accountRef: String? = null,
    val currency: String = "SAR",
    val isOwn: Boolean = true,
    val balanceSemantics: BalanceSemantics,
)

@Entity(tableName = "instrument", indices = [Index("accountId"), Index("mask")])
data class Instrument(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val mask: String,          // "9249", "**0524", "274***", "7274"
    val holder: String? = null // HUSSAIN / ELIYAS
)

@Entity(tableName = "raw_message", indices = [Index("receivedAt"), Index("msgClass")])
data class RawMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val source: SourceKind,
    val sender: String?,
    val receivedAt: Long,
    val body: String,
    val msgClass: MsgClass = MsgClass.UNPARSED,
    val parsedTxnId: Long? = null,
)

@Entity(tableName = "statement_import", indices = [Index("accountId")])
data class StatementImport(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val fileHash: String,
    val periodStart: Long?,
    val periodEnd: Long?,
    val extractorId: String,
    val extractorVersion: String,
    val controlTotalsJson: String? = null,
    val reconciliationStatus: ReconStatus = ReconStatus.PENDING,
    val importedAt: Long,
)

@Entity(
    tableName = "txn",
    indices = [
        Index(value = ["accountId", "occurredAt"]),
        Index("bankRef"),
        Index("dedupGroup"),
        Index(value = ["categoryId", "occurredAt"]),
        Index("txnType"),
    ]
)
data class Txn(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val instrumentId: Long? = null,
    val occurredAt: Long,
    val postedAt: Long? = null,
    val direction: Direction,
    val amountSar: Long,             // minor units (halalas) — exact arithmetic
    val originalAmountMilli: Long? = null, // 3-dp currencies (BHD) in thousandths
    val originalCurrency: String? = null,
    val fxRateMicro: Long? = null,   // rate * 1_000_000
    val fxFeesSarMinor: Long? = null,
    val txnType: TxnType = TxnType.UNKNOWN,
    val merchantRaw: String? = null,
    val merchantId: Long? = null,
    val beneficiary: String? = null,
    val channel: String? = null,
    val categoryId: Long? = null,
    val classifiedBy: ClassifiedBy = ClassifiedBy.NONE,
    val classifiedRuleId: Long? = null,
    val includeInSpend: Boolean = true,
    val status: TxnStatus = TxnStatus.PROVISIONAL,
    val bankRef: String? = null,
    val statementImportId: Long? = null,
    val dedupGroup: String? = null,
    val cardHolder: String? = null,
    val note: String? = null,
    val transferToAccountId: Long? = null,   // user-declared: this txn is a self-transfer to this account
)

@Entity(tableName = "sighting", indices = [Index("txnId"), Index("rawMessageId")])
data class Sighting(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val txnId: Long,
    val rawMessageId: Long?,        // null when the sighting is a statement row
    val statementImportId: Long?,
    val sourceKind: SourceKind,
    val excerpt: String,            // verbatim evidence shown in UI
    val seenAt: Long,
)

@Entity(tableName = "merchant", indices = [Index(value = ["canonicalName"], unique = true)])
data class Merchant(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val canonicalName: String,
    val defaultCategoryId: Long? = null,
    val city: String? = null,
)

@Entity(tableName = "merchant_alias", indices = [Index(value = ["alias"], unique = true), Index("merchantId")])
data class MerchantAlias(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val merchantId: Long,
    val alias: String,              // normalized (lowercase, collapsed spaces)
)

@Entity(tableName = "category", indices = [Index("parentId")])
data class Category(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val parentId: Long? = null,
    val nameEn: String,
    val nameAr: String,
    val icon: String? = null,
    val sort: Int = 0,
    val system: Boolean = false,    // protected: Transfers, Income, Uncategorized
    val customColor: String? = null, // hex "#RRGGBB"; null = use colorForCat()'s automatic color
)

@Entity(tableName = "rule_merchant", indices = [Index("merchantId")])
data class RuleMerchant(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val merchantId: Long,
    val categoryId: Long,
    val createdFromTxnId: Long? = null,
    val createdAt: Long,
)

@Entity(tableName = "rule_pattern", indices = [Index("accountId")])
data class RulePattern(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val amountMinorMin: Long,
    val amountMinorMax: Long,
    val timeWindowStartMin: Int? = null,  // minutes from midnight
    val timeWindowEndMin: Int? = null,
    val weekdayMask: Int? = null,         // bit 0 = Sunday
    val recurrenceHint: String? = null,   // DAILY / WEEKLY / ADHOC
    val categoryId: Long,
    val createdFromTxnId: Long? = null,
    val createdAt: Long,
)

@Entity(tableName = "link", indices = [Index("txnAId"), Index("txnBId"), Index("linkType")])
data class Link(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val txnAId: Long,
    val txnBId: Long,
    val linkType: LinkType,
    val method: LinkMethod,
    val confidence: Float,          // 1.0 for deterministic/manual
    val modelVersion: String? = null,
    val featureSnapshotJson: String? = null, // audit trail for reversibility
    val createdAt: Long,
)

@Entity(tableName = "balance_checkpoint", indices = [Index(value = ["accountId", "at"])])
data class BalanceCheckpoint(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val at: Long,
    val balanceMinor: Long,
    val semantics: BalanceSemantics,
    val sourceSightingId: Long? = null,
)

@Entity(tableName = "quarantine", indices = [Index("createdAt")])
data class Quarantine(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val rawText: String,
    val source: SourceKind,
    val extractorId: String?,
    val reason: String,
    val pageLine: String? = null,
    val createdAt: Long,
    val resolvedTxnId: Long? = null,
)
