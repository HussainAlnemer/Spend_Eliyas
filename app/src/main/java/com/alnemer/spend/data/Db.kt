package com.alnemer.spend.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

// v1 -> v2: add Txn.transferToAccountId (nullable, defaults NULL for every existing row) —
// additive only, no data loss. Never use fallbackToDestructiveMigration: this app carries
// real imported statement history that must survive every update.
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE txn ADD COLUMN transferToAccountId INTEGER")
    }
}

// v2 -> v3: add Category.customColor (nullable hex string; NULL keeps the automatic hash color)
// — additive only, no data loss.
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE category ADD COLUMN customColor TEXT")
    }
}

class Converters {
    @TypeConverter fun accountType(v: AccountType) = v.name
    @TypeConverter fun toAccountType(v: String) = AccountType.valueOf(v)
    @TypeConverter fun balSem(v: BalanceSemantics) = v.name
    @TypeConverter fun toBalSem(v: String) = BalanceSemantics.valueOf(v)
    @TypeConverter fun dir(v: Direction) = v.name
    @TypeConverter fun toDir(v: String) = Direction.valueOf(v)
    @TypeConverter fun status(v: TxnStatus) = v.name
    @TypeConverter fun toStatus(v: String) = TxnStatus.valueOf(v)
    @TypeConverter fun src(v: SourceKind) = v.name
    @TypeConverter fun toSrc(v: String) = SourceKind.valueOf(v)
    @TypeConverter fun msg(v: MsgClass) = v.name
    @TypeConverter fun toMsg(v: String) = MsgClass.valueOf(v)
    @TypeConverter fun ttype(v: TxnType) = v.name
    @TypeConverter fun toTtype(v: String) = TxnType.valueOf(v)
    @TypeConverter fun ltype(v: LinkType) = v.name
    @TypeConverter fun toLtype(v: String) = LinkType.valueOf(v)
    @TypeConverter fun lmethod(v: LinkMethod) = v.name
    @TypeConverter fun toLmethod(v: String) = LinkMethod.valueOf(v)
    @TypeConverter fun cby(v: ClassifiedBy) = v.name
    @TypeConverter fun toCby(v: String) = ClassifiedBy.valueOf(v)
    @TypeConverter fun recon(v: ReconStatus) = v.name
    @TypeConverter fun toRecon(v: String) = ReconStatus.valueOf(v)
}

@Database(
    entities = [
        Institution::class, Account::class, Instrument::class, RawMessage::class,
        StatementImport::class, Txn::class, Sighting::class, Merchant::class,
        MerchantAlias::class, Category::class, RuleMerchant::class, RulePattern::class,
        Link::class, BalanceCheckpoint::class, Quarantine::class,
    ],
    version = 3,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class SpendDb : RoomDatabase() {
    abstract fun accounts(): AccountDao
    abstract fun categories(): CategoryDao
    abstract fun merchants(): MerchantDao
    abstract fun txns(): TxnDao
    abstract fun ingest(): IngestDao
    abstract fun rules(): RuleDao

    companion object {
        @Volatile private var instance: SpendDb? = null

        fun get(context: Context): SpendDb = instance ?: synchronized(this) {
            instance ?: build(context).also { instance = it }
        }

        private fun build(context: Context): SpendDb {
            System.loadLibrary("sqlcipher")
            val passphrase = CryptoPrefs.dbPassphrase(context)
            val factory = SupportOpenHelperFactory(passphrase)
            return Room.databaseBuilder(context, SpendDb::class.java, "spend.db")
                .openHelperFactory(factory)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
        }
    }
}
