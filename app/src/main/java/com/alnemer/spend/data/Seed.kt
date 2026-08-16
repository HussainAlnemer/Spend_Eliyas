package com.alnemer.spend.data

import androidx.room.withTransaction

/**
 * First-run seeding — transactional and self-repairing (Stage 2 fix):
 * the whole seed commits atomically, and merchant seeding re-verifies
 * completeness on every launch so an interrupted first run heals itself.
 */
object Seed {

    suspend fun runIfEmpty(db: SpendDb) {
        if (db.categories().count() == 0) {
            db.withTransaction {
                seedCategories(db)
                seedInstitutionsAndAccounts(db)
            }
        }
        repairMerchants(db) // idempotent; also completes partial Stage-1 installs
        repairAccounts(db)  // additive account/instrument registry updates
        repairCategories(db) // additive category registry updates
        // transfers must be classified by the user, not guessed — release any seeded guesses to Review
        db.txns().resetSeededTransferCategories()
    }
    private val TREE: List<Triple<Pair<String, String>, List<Pair<String, String>>, Boolean>> = listOf(
        Triple("Food & Dining" to "الطعام والمطاعم", listOf(
            "Groceries" to "البقالة", "Dining" to "المطاعم", "Coffee" to "القهوة", "Delivery" to "التوصيل"
        ), false),
        Triple("Transport" to "المواصلات", listOf(
            "Fuel" to "الوقود", "Taxi & Ride" to "الأجرة", "Parking & Tolls" to "المواقف", "Maintenance" to "الصيانة"
        ), false),
        Triple("Bills & Utilities" to "الفواتير", listOf(
            "Water" to "المياه", "Electricity" to "الكهرباء", "Telecom" to "الاتصالات", "Subscriptions" to "الاشتراكات", "Government (SADAD)" to "مدفوعات حكومية"
        ), false),
        Triple("Shopping" to "التسوق", listOf(
            "Clothing" to "الملابس", "Electronics" to "الإلكترونيات", "Online" to "تسوق إلكتروني", "Home" to "المنزل"
        ), false),
        Triple("Health" to "الصحة", listOf(
            "Pharmacy" to "الصيدلية", "Clinics & Hospitals" to "العيادات", "Fitness" to "اللياقة"
        ), false),
        Triple("Family" to "العائلة", listOf(
            "Transfers to family" to "تحويلات عائلية", "Kids & Education" to "الأطفال والتعليم", "Gifts" to "الهدايا"
        ), false),
        Triple("Entertainment" to "الترفيه", listOf(
            "Clubs & Activities" to "النوادي", "Travel" to "السفر", "Events" to "الفعاليات"
        ), false),
        Triple("Personal" to "شخصي", listOf(
            "Grooming" to "العناية", "Charity" to "الصدقة", "Other" to "أخرى"
        ), false),
        Triple("Fees & Charges" to "الرسوم", listOf(
            "Bank fees" to "رسوم بنكية", "FX fees" to "رسوم صرف العملات"
        ), false),
        Triple("Income" to "الدخل", listOf(
            "Salary" to "الراتب", "Investment profit" to "أرباح استثمار", "Cashback & rewards" to "استرداد نقدي",
            "Citizens account" to "حساب المواطن", "Student reward" to "مكافأة الطلاب", "Daily salary" to "راتب يومي",
            "Other income" to "دخل آخر"
        ), true),
        Triple("Transfers" to "التحويلات", listOf(
            "Between my accounts" to "بين حساباتي", "Wallet top-ups" to "شحن المحافظ", "Card payments" to "سداد البطاقات"
        ), true),
        Triple("Uncategorized" to "غير مصنف", emptyList(), true),
    )

    private suspend fun seedCategories(db: SpendDb) {
        var sort = 0
        for ((parent, kids, system) in TREE) {
            val pid = db.categories().insert(
                Category(parentId = null, nameEn = parent.first, nameAr = parent.second, sort = sort++, system = system)
            )
            for ((en, ar) in kids) {
                db.categories().insert(Category(parentId = pid, nameEn = en, nameAr = ar, sort = sort++, system = system))
            }
        }
    }

    private suspend fun seedInstitutionsAndAccounts(db: SpendDb) {
        val snb = db.accounts().insertInstitution(Institution(name = "SNB", kind = "BANK"))
        val tamra = db.accounts().insertInstitution(Institution(name = "Tamra", kind = "FINANCE_CO"))
        val stc = db.accounts().insertInstitution(Institution(name = "STC Bank", kind = "BANK"))
        val awaed = db.accounts().insertInstitution(Institution(name = "Awaed", kind = "FINANCE_CO"))
        val riyad = db.accounts().insertInstitution(Institution(name = "Riyad Bank", kind = "BANK"))
        val d360 = db.accounts().insertInstitution(Institution(name = "D360 Bank", kind = "BANK"))
        val cash = db.accounts().insertInstitution(Institution(name = "Cash", kind = "CASH"))

        // Confirmed against 5 real SMS samples (Aug 2026) — see SnbSms in Parsers.kt.
        // Mapping (9493=Champions, 0801=Mada Credit Card, 7800=Savings) was inferred from
        // running-balance arithmetic across the samples, not stated outright — flagged for Eliyas.
        val champions = db.accounts().insert(Account(institutionId = snb, type = AccountType.CREDIT_CARD,
            displayName = "SNB Champions", balanceSemantics = BalanceSemantics.AVAILABLE_CREDIT))
        db.accounts().insert(Account(institutionId = snb, type = AccountType.CREDIT_CARD,
            displayName = "SNB Mada Credit Card", accountRef = "0801",
            balanceSemantics = BalanceSemantics.AVAILABLE_CREDIT))
        db.accounts().insert(Account(institutionId = snb, type = AccountType.CURRENT,
            displayName = "SNB Savings", accountRef = "7800",
            balanceSemantics = BalanceSemantics.RUNNING_BALANCE))
        db.accounts().insertInstrument(Instrument(accountId = champions, mask = "9493", holder = "ELIYAS"))

        // Placeholders — no SMS samples yet, so these won't auto-capture until a parser is added.
        // They still show up in Ledger/Home for manual entries or a future PDF import.
        db.accounts().insert(Account(institutionId = snb, type = AccountType.GOLD,
            displayName = "SNB Gold", accountRef = "7703", balanceSemantics = BalanceSemantics.WALLET_BALANCE))
        db.accounts().insert(Account(institutionId = tamra, type = AccountType.INVESTMENT,
            displayName = "Tamra", balanceSemantics = BalanceSemantics.WALLET_BALANCE))
        db.accounts().insert(Account(institutionId = stc, type = AccountType.CURRENT,
            displayName = "STC Bank", balanceSemantics = BalanceSemantics.RUNNING_BALANCE))
        db.accounts().insert(Account(institutionId = awaed, type = AccountType.INVESTMENT,
            displayName = "Awaed", balanceSemantics = BalanceSemantics.WALLET_BALANCE))
        db.accounts().insert(Account(institutionId = riyad, type = AccountType.CURRENT,
            displayName = "Riyad Bank", accountRef = "2991", balanceSemantics = BalanceSemantics.RUNNING_BALANCE))
        db.accounts().insert(Account(institutionId = d360, type = AccountType.CURRENT,
            displayName = "D360 Bank", accountRef = "8626", balanceSemantics = BalanceSemantics.RUNNING_BALANCE))
        db.accounts().insert(Account(institutionId = cash, type = AccountType.CASH,
            displayName = "Cash", balanceSemantics = BalanceSemantics.WALLET_BALANCE))
    }

    private data class SeedMerchant(val canonical: String, val cat: String, val aliases: List<String>)

    private val MERCHANTS = listOf(
        SeedMerchant("Muntazah Markets", "Groceries", listOf("muntazah markets")),
        SeedMerchant("Tamimi Markets", "Groceries", listOf("tamimi markets", "tamimi")),
        SeedMerchant("Herfy", "Dining", listOf("herfy", "herfy1409")),
        SeedMerchant("KUDU", "Dining", listOf("kudu")),
        SeedMerchant("Ocean Restaurant", "Dining", listOf("ocean resturanut", "ocean restura")),
        SeedMerchant("Kanz Al Khaleej Catering", "Dining", listOf("f s tco sjmb catering")),
        SeedMerchant("Coffee Address", "Coffee", listOf("coffee address")),
        SeedMerchant("ALDREES Petrol", "Fuel", listOf("aldrees", "aldrees 704")),
        SeedMerchant("Al Dawaa Pharmacy", "Pharmacy", listOf("aldawaa", "aldawaa ph078")),
        SeedMerchant("Royal Care Center", "Clinics & Hospitals", listOf("royal care center")),
        SeedMerchant("Khaleej Club", "Clubs & Activities", listOf("khaleej club")),
        SeedMerchant("Chuck E Cheese", "Kids & Education", listOf("chuck e cheese")),
        SeedMerchant("MAX Fashion", "Clothing", listOf("max 60135", "max fashion")),
        SeedMerchant("AliExpress", "Online", listOf("www.aliexpress.com", "aliexpress")),
        SeedMerchant("Nana (ananinja)", "Groceries", listOf("www.ananinja.com", "ananinja")),
        SeedMerchant("National Water Company", "Water", listOf("national water company")),
        SeedMerchant("Al Mosafer", "Travel", listOf("al mosafer")),
        SeedMerchant("Desktop Search (subscription)", "Subscriptions", listOf("desktop search spee")),
        SeedMerchant("Carrefour", "Groceries", listOf("carefour", "carrefour")),
        SeedMerchant("Keeta", "Delivery", listOf("keeta technologies ara", "keeta")),
        SeedMerchant("Citizens Account", "Citizens account", listOf("citizens account")),
        SeedMerchant("Student Reward", "Student reward", listOf("student reward")),
    )

    /**
     * Insert-if-missing registry additions discovered after first release — runs on every
     * launch, so this is THE place to add a new account/card later without touching Seed's
     * first-run path or writing a migration. Pattern (uncomment/adapt when needed):
     *
     *   val accounts = db.accounts().all()
     *   val snbId = accounts.first { it.displayName.startsWith("SNB") }.institutionId
     *   if (accounts.none { it.displayName == "SNB Gold" && it.accountRef != null }) {
     *       accounts.first { it.displayName == "SNB Gold" }
     *           .let { db.accounts().setAccountRef(it.id, "<ref once known>") }
     *   }
     *   if (db.accounts().instrumentCount("<new 4-digit mask>") == 0)
     *       db.accounts().insertInstrument(Instrument(accountId = someAccountId, mask = "...", holder = "ELIYAS"))
     */
    private suspend fun repairAccounts(db: SpendDb) {
        // no post-release additions yet
    }

    /** Same insert-if-missing pattern as repairAccounts(), for categories discovered after
     *  first release — e.g. Eliyas asking for Citizens account / Student reward / Daily salary
     *  after already installing the app, where seedCategories()'s first-run-only path can't help. */
    private suspend fun repairCategories(db: SpendDb) {
        val incomeId = db.categories().idByNameEn("Income") ?: return
        val existing = db.categories().all().map { it.nameEn }.toSet()
        val additions = listOf(
            "Citizens account" to "حساب المواطن",
            "Student reward" to "مكافأة الطلاب",
            "Daily salary" to "راتب يومي",
        )
        for ((i, pair) in additions.withIndex()) {
            val (en, ar) = pair
            if (en !in existing) db.categories().insert(
                Category(parentId = incomeId, nameEn = en, nameAr = ar, sort = 1000 + i, system = true))
        }
    }

    private suspend fun repairMerchants(db: SpendDb) {
        val now = System.currentTimeMillis()
        for (m in MERCHANTS) {
            // canonical name is unique — presence of the first alias marks completion
            if (db.merchants().byAlias(m.aliases.first()) != null) continue
            db.withTransaction {
                val catId = db.categories().idByNameEn(m.cat)
                val mid = db.merchants().insert(Merchant(canonicalName = m.canonical, defaultCategoryId = catId))
                for (a in m.aliases) db.merchants().insertAlias(MerchantAlias(merchantId = mid, alias = a))
                if (catId != null) db.rules().insertMerchantRule(
                    RuleMerchant(merchantId = mid, categoryId = catId, createdAt = now))
            }
        }
    }
}
