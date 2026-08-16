package com.alnemer.spend

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List as ListIcon
import androidx.compose.material.icons.filled.Settings as SettingsIcon
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alnemer.spend.data.*
import com.alnemer.spend.ingest.Ingestor
import com.alnemer.spend.ingest.Matcher
import com.alnemer.spend.notify.scheduleWeeklySummary
import com.alnemer.spend.ingest.StatementImporter
import com.alnemer.spend.ui.Motion
import com.alnemer.spend.ui.ProvideReduceMotion
import com.alnemer.spend.ui.fluidClickable
import com.alnemer.spend.ui.projectedOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Calendar
import java.util.Date
import java.util.Locale

// ————— palette (light + dark; every existing reference to Bg/Mint/Ink/etc. keeps working
// unchanged since these are still plain top-level vals, just with a @Composable getter that
// picks light or dark based on the system setting) —————
val Bg: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFF0E1512) else Color(0xFFFAFBFA)
val CardC: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFF17201C) else Color(0xFFFFFFFF)
val Card2: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFF1C2620) else Color(0xFFF6F8F6)
val LineC: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFF2A352E) else Color(0xFFE3E7E4)
val Mint: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFF2FBE8E) else Color(0xFF1E8F6B)
val MintDim: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFF6FA98D) else Color(0xFF5B8B72)
val Gold: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFFC99B3D) else Color(0xFFA6791F)
val Ink: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFFEFF3F0) else Color(0xFF16201B)
val Dim: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFFA8B8AE) else Color(0xFF5B6B62)
val Faint: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFF6E7D74) else Color(0xFF8B9990)
// Categories are now user-editable (Tools -> Manage Categories), so colors are derived
// deterministically from the name instead of a fixed name->color map — a rename or a
// brand-new category always resolves to a stable color, never a silent miss.
// Values are tuned for legibility as text/icon color on the light background (not just as a
// pale fill), since colorForCat() is used both ways.
val CatPalette = listOf(
    Color(0xFF1E8F6B), Color(0xFF6B5BC4), Color(0xFFC4577D), Color(0xFF3C7FB8),
    Color(0xFFA1792E), Color(0xFF4C9142), Color(0xFFBC6A46), Color(0xFF8C8C61),
    Color(0xFF6E6E6E), Color(0xFF2E9186), Color(0xFF9757A8), Color(0xFF8A9E3D))
fun colorForCat(name: String?): Color {
    if (name == null || name == "Uncategorized" || name == "needs review") return Color(0xFF8B9990)
    val h = name.fold(0) { acc, c -> acc * 31 + c.code }
    return CatPalette[((h % CatPalette.size) + CatPalette.size) % CatPalette.size]
}
/** Prefers a manually-picked color (Manage categories) over the automatic hash color. */
fun colorForCat(cat: Category?): Color =
    cat?.customColor?.let { hex -> runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrNull() }
        ?: colorForCat(cat?.nameEn)

fun fmt(minor: Long) = "%,d.%02d".format(minor / 100, minor % 100)

data class HomeData(
    val trueSpend: Long, val gross: Long, val cats: List<Pair<String, Long>>,
    val reviewCount: Int, val links: Int, val income: Long, val cashback: Long,
    val transfersIn: Long, val internal: List<Pair<String, Long>>,
    val accounts: List<Triple<Account, Long?, Long?>>, val cycleLabel: String)

data class LedgerLoad(
    val rows: List<Triple<Txn, String, Boolean>>,
    val accNames: Map<Long, String>,
    val error: String?,
    val cats: List<Category>,
    val accounts: List<Account>,
)

class AppActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val dark = isSystemInDarkTheme()
            val view = androidx.compose.ui.platform.LocalView.current
            if (!view.isInEditMode) {
                SideEffect {
                    val controller = androidx.core.view.WindowCompat.getInsetsController(window, view)
                    controller.isAppearanceLightStatusBars = !dark
                    controller.isAppearanceLightNavigationBars = !dark
                }
            }
            MaterialTheme(colorScheme = if (dark)
                darkColorScheme(primary = Mint, background = Bg, surface = CardC, onBackground = Ink, onSurface = Ink)
            else
                lightColorScheme(primary = Mint, background = Bg, surface = CardC, onBackground = Ink, onSurface = Ink)) {
                AppRoot()
            }
        }
    }
}

fun cycleStart(offset: Int = 0): Long {
    val c = Calendar.getInstance()
    c.set(Calendar.DAY_OF_MONTH, 1)
    c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
    c.add(Calendar.MONTH, offset)
    return c.timeInMillis
}

/** cycle offset (0 = current) that contains the most recent transaction, capped at -36 */
suspend fun cycleOfLatestTxn(db: SpendDb): Int {
    val latest = db.txns().latestTxnAt() ?: return 0
    var k = 0
    while (k > -36 && latest < cycleStart(offset = k)) k--
    return k
}

suspend fun loadHome(db: SpendDb, offset: Int?): HomeData {
    val from = if (offset == null) 0L else cycleStart(offset = offset)
    val to = if (offset == null) Long.MAX_VALUE else cycleStart(offset = offset + 1) - 1
    val cats = db.categories().all()
    val parents = cats.filter { it.parentId == null }.associateBy { it.id }
    val totals = HashMap<String, Long>()
    for (ct in db.txns().spendByCategoryBetween(from, to)) {
        val c = cats.firstOrNull { it.id == ct.categoryId }
        val name = when {
            c == null -> "Uncategorized"
            c.parentId != null -> parents[c.parentId]?.nameEn ?: c.nameEn
            else -> c.nameEn
        }
        if (name == "Transfers" || name == "Income") continue
        totals[name] = (totals[name] ?: 0) + ct.total
    }
    val accs = db.accounts().all().map { a ->
        val cp = db.ingest().latestCheckpoint(a.id)
        Triple(a, cp?.balanceMinor, cp?.at)
    }
    val internalLabels = mapOf(
        TxnType.CREDIT_CARD_PAYMENT to "Card payments", TxnType.TRANSFER_TO_WALLET to "Wallet top-ups")
    val internal = db.txns().internalTransfersBetween(from, to)
        .map { (internalLabels[it.txnType] ?: it.txnType.name) to it.total }
    val df = SimpleDateFormat("MMMM yyyy", Locale.US)
    return HomeData(
        db.txns().trueSpendBetween(from, to), db.txns().grossBetween(from, to),
        totals.toList().sortedByDescending { it.second },
        db.txns().uncategorized().size, db.rules().linkCount(),
        db.txns().strictIncomeBetween(from, to), db.txns().cashbackBetween(from, to),
        db.txns().transfersInBetween(from, to), internal,
        accs,
        if (offset == null) "All recorded activity · every account"
        else df.format(Date(from)))
}

@Composable
fun AppRoot() {
    ProvideReduceMotion {
    var tab by remember { mutableStateOf(0) }
    var refresh by remember { mutableIntStateOf(0) }
    val ctx = LocalContext.current
    var home by remember { mutableStateOf<HomeData?>(null) }
    var cycleOffset by remember { mutableStateOf<Int?>(null) }   // null = overall landing
    LaunchedEffect(refresh, cycleOffset) {
        home = withContext(Dispatchers.IO) { loadHome(SpendDb.get(ctx), cycleOffset) }
    }
    Scaffold(
        containerColor = Bg,
        bottomBar = {
            Box {
                HorizontalDivider(color = LineC, thickness = 1.dp, modifier = Modifier.align(Alignment.TopCenter))
                NavigationBar(containerColor = CardC) {
                    val navLabels = listOf("Home", "Ledger", "Review", "Tools")
                    navLabels.forEachIndexed { i, label ->
                        NavigationBarItem(
                            selected = tab == i, onClick = { tab = i; refresh++ },
                            icon = {
                                val iconContent: @Composable () -> Unit = {
                                    when (i) {
                                        0 -> Icon(Icons.Default.Home, contentDescription = label, modifier = Modifier.size(19.dp))
                                        1 -> Icon(Icons.Default.ListIcon, contentDescription = label, modifier = Modifier.size(19.dp))
                                        // custom vector — the full Material icon set (CompareArrows etc.) lives in
                                        // material-icons-extended, which we deliberately don't depend on for four icons
                                        2 -> Icon(painter = painterResource(id = R.drawable.ic_review), contentDescription = label, modifier = Modifier.size(19.dp))
                                        else -> Icon(Icons.Default.SettingsIcon, contentDescription = label, modifier = Modifier.size(19.dp))
                                    }
                                }
                                if (i == 2 && (home?.reviewCount ?: 0) > 0)
                                    BadgedBox(badge = { Badge(containerColor = Gold) { Text("${home?.reviewCount}", fontSize = 9.sp) } }) { iconContent() }
                                else iconContent()
                            },
                            label = { Text(label, fontSize = 10.sp) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Mint, selectedTextColor = Mint,
                                unselectedIconColor = Faint, unselectedTextColor = Faint,
                                indicatorColor = Card2))
                    }
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize().background(Bg)) {
            when (tab) {
                0 -> HomeScreen(home, cycleOffset,
                    onPrevCycle = {
                        val cur = cycleOffset
                        if (cur == null) scopeLatest(ctx) { cycleOffset = it }
                        else cycleOffset = cur - 1
                    },
                    onNextCycle = {
                        val cur = cycleOffset
                        if (cur != null) cycleOffset = if (cur >= 0) null else cur + 1
                    },
                    onOverall = { cycleOffset = null },
                    goReview = { tab = 2 })
                1 -> LedgerScreen(refresh, onChanged = { refresh++ })
                2 -> ReviewScreen(onChanged = { refresh++ })
                3 -> ToolsScreen(onChanged = { refresh++ })
            }
        }
    }
    }
}

private fun scopeLatest(ctx: android.content.Context, set: (Int) -> Unit) {
    (ctx.applicationContext as App).scope.launch {
        val k = cycleOfLatestTxn(SpendDb.get(ctx))
        withContext(Dispatchers.Main) { set(k) }
    }
}

@Composable
fun Chip(text: String, color: Color = Dim) {
    Text(text, fontSize = 10.sp, color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(99.dp))
            .background(color.copy(alpha = 0.10f))
            .padding(horizontal = 8.dp, vertical = 3.dp))
}

@Composable
fun HomeScreen(d: HomeData?, cycleOffset: Int?, onPrevCycle: () -> Unit, onNextCycle: () -> Unit, onOverall: () -> Unit, goReview: () -> Unit) {
    var showTrue by remember { mutableStateOf(true) }
    if (d == null) { Text("Loading…", color = Dim, modifier = Modifier.padding(24.dp)); return }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp)) {
        Text("SPEND ANALYZER · v${BuildConfig.VERSION_NAME}", color = MintDim, fontSize = 11.sp, letterSpacing = 2.sp)
        Spacer(Modifier.height(14.dp))
        Row {
            listOf(true to "True spend", false to "Gross activity").forEach { (v, label) ->
                Text(label, fontSize = 11.sp,
                    color = if (showTrue == v) Mint else Faint,
                    modifier = Modifier
                        .clip(RoundedCornerShape(99.dp))
                        .background(if (showTrue == v) Mint.copy(alpha = 0.12f) else Color.Transparent)
                        .fluidClickable { showTrue = v }
                        .padding(horizontal = 12.dp, vertical = 5.dp))
                Spacer(Modifier.width(6.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        val amount = if (showTrue) d.trueSpend else d.gross
        Row(verticalAlignment = Alignment.Bottom) {
            Text(fmt(amount), color = Ink, fontSize = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.8).sp)
            Text("  SAR", color = Faint, fontSize = 14.sp, modifier = Modifier.padding(bottom = 6.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("‹", color = Mint, fontSize = 22.sp,
                modifier = Modifier.fluidClickable { onPrevCycle() }.padding(horizontal = 10.dp, vertical = 4.dp))
            Text(d.cycleLabel + (if (cycleOffset == 0) " · current" else ""), color = Dim, fontSize = 12.sp)
            Text("›", color = if (cycleOffset != null) Mint else Faint, fontSize = 22.sp,
                modifier = Modifier.fluidClickable { onNextCycle() }.padding(horizontal = 10.dp, vertical = 4.dp))
            if (cycleOffset != null) {
                Spacer(Modifier.width(4.dp))
                Text("ALL", color = MintDim, fontSize = 10.sp, letterSpacing = 1.sp,
                    modifier = Modifier.clip(RoundedCornerShape(99.dp))
                        .background(MintDim.copy(alpha = 0.12f))
                        .fluidClickable { onOverall() }
                        .padding(horizontal = 10.dp, vertical = 4.dp))
            }
        }
        if (!showTrue) Text("incl. money moved between your accounts", color = Dim, fontSize = 11.sp)
        Spacer(Modifier.height(12.dp))

        if (d.reviewCount > 0) {
            Row(Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Gold.copy(alpha = 0.12f))
                .fluidClickable { goReview() }
                .padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("${d.reviewCount} items to review", color = Gold, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text("One decision can classify a whole group", color = Dim, fontSize = 11.sp)
                }
                Text("›", color = Gold, fontSize = 20.sp)
            }
            Spacer(Modifier.height(16.dp))
        }

        Text("WHERE IT WENT", color = Faint, fontSize = 10.sp, letterSpacing = 1.5.sp)
        Spacer(Modifier.height(6.dp))
        val max = d.cats.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1
        d.cats.forEach { (name, total) ->
            if (total <= 0) return@forEach
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(name, color = if (name == "Uncategorized") Faint else Ink, fontSize = 13.sp)
                Text(fmt(total), color = Dim, fontSize = 13.sp)
            }
            Box(Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(4.dp)).background(LineC.copy(alpha = 0.6f))) {
                Box(Modifier.fillMaxWidth(total.toFloat() / max).height(7.dp)
                    .clip(RoundedCornerShape(4.dp)).background(colorForCat(name)))
            }
        }
        Spacer(Modifier.height(16.dp))

        if (d.internal.isNotEmpty()) {
            Text("INTERNAL TRANSFERS · excluded from spend", color = Faint, fontSize = 10.sp, letterSpacing = 1.2.sp)
            Spacer(Modifier.height(6.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CardC).border(1.dp, LineC, RoundedCornerShape(14.dp)).padding(14.dp)) {
                d.internal.forEach { (name, total) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(name, color = Dim, fontSize = 13.sp)
                        Text(fmt(total), color = Dim, fontSize = 13.sp)
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Total — money moved between your own accounts", color = Faint, fontSize = 11.sp)
                    Text(fmt(d.internal.sumOf { it.second }), color = Faint, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CardC).border(1.dp, LineC, RoundedCornerShape(14.dp)).padding(14.dp)) {
            Text("IN THIS PERIOD", color = Faint, fontSize = 10.sp, letterSpacing = 1.5.sp)
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Income (salary & profit)", color = Dim, fontSize = 13.sp); Text("+${fmt(d.income)}", color = Mint, fontSize = 13.sp)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Received transfers", color = Dim, fontSize = 13.sp); Text("+${fmt(d.transfersIn)}", color = Mint, fontSize = 13.sp)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Cashback earned", color = Dim, fontSize = 13.sp); Text("+${fmt(d.cashback)}", color = Mint, fontSize = 13.sp)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Linked pairs", color = Dim, fontSize = 13.sp); Text("${d.links} ⇄", color = Dim, fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(16.dp))

        Text("ACCOUNTS", color = Faint, fontSize = 10.sp, letterSpacing = 1.5.sp)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            val adf = SimpleDateFormat("dd MMM yy", Locale.US)
            d.accounts.forEach { (a, bal, at) ->
                Column(Modifier.padding(end = 10.dp).width(150.dp)
                    .clip(RoundedCornerShape(14.dp)).background(CardC).border(1.dp, LineC, RoundedCornerShape(14.dp)).padding(12.dp)) {
                    Text(a.displayName, color = Ink, fontSize = 13.sp, maxLines = 1)
                    Spacer(Modifier.height(6.dp))
                    Text(bal?.let { fmt(it) } ?: "—", color = Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text(when (a.balanceSemantics) {
                        BalanceSemantics.AVAILABLE_CREDIT -> "available credit"
                        BalanceSemantics.WALLET_BALANCE -> "wallet balance"
                        else -> "balance"
                    } + (at?.let { " · as of ${adf.format(Date(it))}" } ?: ""),
                        color = Faint, fontSize = 10.sp)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * Shared classify UI: used by Ledger (tap any row), Review's swipe-to-categorize sheet, and
 * Review's "recently classified" revisit list. Two mutually-exclusive outcomes: pick a spend
 * category, or declare this a transfer to one of the user's own accounts (tag-now, the Matcher
 * links the other leg automatically once it exists — see Matcher.pairDeclaredTransfers).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ClassifySheetContent(
    count: Int,
    accountId: Long,
    cats: List<Category>,
    accounts: List<Account>,
    merchantRaw: String?,
    onApplyCategory: (Category, Boolean) -> Unit,
    onApplyTransfer: (Account) -> Unit,
) {
    var picked by remember { mutableStateOf<Category?>(null) }
    Column(Modifier.padding(18.dp)) {
        val p = picked
        if (p == null) {
            Text("Pick a category", color = Ink, fontSize = 14.sp)
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                cats.forEach { c ->
                    Text(c.nameEn, color = Ink, fontSize = 12.sp,
                        modifier = Modifier.clip(RoundedCornerShape(99.dp))
                            .background(colorForCat(c.nameEn).copy(alpha = 0.15f))
                            .fluidClickable { picked = c }
                            .padding(horizontal = 12.dp, vertical = 8.dp))
                }
            }
            val others = accounts.filter { it.id != accountId }
            if (others.isNotEmpty()) {
                Spacer(Modifier.height(18.dp))
                Text("OR — TRANSFER TO MY OWN ACCOUNT", color = Faint, fontSize = 10.sp, letterSpacing = 1.2.sp)
                Spacer(Modifier.height(8.dp))
                others.forEach { a ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .fluidClickable { onApplyTransfer(a) }
                        .padding(vertical = 10.dp, horizontal = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(a.displayName, color = Ink, fontSize = 13.sp)
                        Text("matches automatically →", color = Faint, fontSize = 11.sp)
                    }
                }
            }
        } else {
            Text("Apply ${p.nameEn} to…", color = Ink, fontSize = 14.sp)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { onApplyCategory(p, false) }, modifier = Modifier.weight(1f)) {
                    Text(if (count > 1) "Just these $count" else "Just this one", fontSize = 12.sp)
                }
                Button(onClick = { onApplyCategory(p, true) }, modifier = Modifier.weight(1f),
                    enabled = merchantRaw != null,
                    colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Color.White)) {
                    Text("All from merchant — rule", fontSize = 12.sp)
                }
            }
        }
        Spacer(Modifier.height(30.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LedgerScreen(refresh: Int, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<Triple<Txn, String, Boolean>>>(emptyList()) }
    var accNames by remember { mutableStateOf<Map<Long, String>>(emptyMap()) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var pickerCats by remember { mutableStateOf<List<Category>>(emptyList()) }
    var pickerAccounts by remember { mutableStateOf<List<Account>>(emptyList()) }
    var sheetTxn by remember { mutableStateOf<Txn?>(null) }
    LaunchedEffect(refresh) {
        val loaded = withContext(Dispatchers.IO) {
            try {
                val db = SpendDb.get(ctx)
                val linked = db.rules().linkedTxnIds().toHashSet()
                val catMap = db.categories().all().associateBy { it.id }
                val names = db.accounts().all().associate { it.id to it.displayName }
                val list = db.txns().recent(300).map { t ->
                    Triple(t, t.categoryId?.let { catMap[it]?.nameEn } ?: "needs review", t.id in linked)
                }
                val pickCats = catMap.values.filter { it.parentId != null && !it.system }.sortedBy { it.sort }
                LedgerLoad(list, names, null, pickCats, db.accounts().all())
            } catch (e: Exception) {
                LedgerLoad(emptyList(), emptyMap(),
                    "Ledger failed to load: ${e.message ?: e.javaClass.simpleName}", emptyList(), emptyList())
            }
        }
        // state writes on the main thread, all-or-nothing — items and content stay consistent
        rows = loaded.rows; accNames = loaded.accNames; loadError = loaded.error
        pickerCats = loaded.cats; pickerAccounts = loaded.accounts
    }
    if (loadError != null) {
        Text(loadError ?: "", color = Gold, fontSize = 12.sp, modifier = Modifier.padding(16.dp))
        return
    }
    val df = SimpleDateFormat("EEE, dd MMM yy", Locale.US)
    val tf = SimpleDateFormat("HH:mm", Locale.US)
    // precomputed day headers: lazy items compose out of order, so no shared mutable state
    val list = rows   // single snapshot: item count and item content must read the same list
    val headers = remember(list) {
        list.mapIndexed { i, r ->
            val day = df.format(Date(r.first.occurredAt))
            val prev = if (i == 0) null else df.format(Date(list[i - 1].first.occurredAt))
            if (day != prev) day else null
        }
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        items(list.size, key = { list[it].first.id }) { i ->
            val (t, cat, linked) = list[i]
            headers[i]?.let { day ->
                Text(day.uppercase(), color = Faint, fontSize = 10.sp, letterSpacing = 1.sp,
                    modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(if (t.status == TxnStatus.PROVISIONAL) Color.Transparent else CardC)
                .border(1.dp, if (t.status == TxnStatus.PROVISIONAL) Color.Transparent else LineC, RoundedCornerShape(13.dp))
                .fluidClickable { sheetTxn = t }
                .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(t.merchantRaw ?: t.beneficiary ?: t.txnType.name, color = Ink, fontSize = 14.sp, maxLines = 1)
                    Spacer(Modifier.height(3.dp))
                    Row {
                        Text("${accNames[t.accountId] ?: ""} · ${df.format(Date(t.occurredAt))} ${tf.format(Date(t.occurredAt))}", color = Faint, fontSize = 11.sp)
                    }
                    Spacer(Modifier.height(3.dp))
                    Row {
                        if (!t.includeInSpend) { Chip("excluded", Dim); Spacer(Modifier.width(4.dp)) }
                        if (t.status == TxnStatus.PROVISIONAL) { Chip("provisional", Gold); Spacer(Modifier.width(4.dp)) }
                        if (linked) Chip("linked ⇄", MintDim)
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text((if (t.direction == Direction.CREDIT) "+" else "−") + fmt(t.amountSar),
                        color = if (t.direction == Direction.CREDIT) Mint else Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(cat, color = colorForCat(cat), fontSize = 11.sp)
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    sheetTxn?.let { t ->
        ModalBottomSheet(onDismissRequest = { sheetTxn = null }, containerColor = Card2) {
            ClassifySheetContent(
                count = 1, accountId = t.accountId, cats = pickerCats, accounts = pickerAccounts,
                merchantRaw = t.merchantRaw,
                onApplyCategory = { c, all ->
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            val db = SpendDb.get(ctx)
                            if (all && t.merchantRaw != null) db.txns().applyMerchantCategory(t.merchantRaw, c.id)
                            db.txns().update(t.copy(categoryId = c.id, classifiedBy = ClassifiedBy.MANUAL))
                        }
                        sheetTxn = null; onChanged()
                    }
                },
                onApplyTransfer = { a ->
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            val db = SpendDb.get(ctx)
                            val betweenId = db.categories().idByNameEn("Between my accounts")
                            db.txns().setTransferTarget(t.id, a.id, betweenId)
                            Matcher(db).run()
                        }
                        sheetTxn = null; onChanged()
                    }
                })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ReviewScreen(onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var queue by remember { mutableStateOf<List<List<Txn>>>(emptyList()) }
    var total by remember { mutableIntStateOf(0) }
    var loaded by remember { mutableStateOf(false) }
    // offsetX is an Animatable, not raw state — it is what makes the card interruptible: a new
    // drag can grab it mid-spring, mid-fling, at any instant, and snapTo() always cancels
    // whatever animation was previously running on it (Apple "Designing Fluid Interfaces" §3).
    val offsetX = remember { Animatable(0f) }
    val density = LocalDensity.current
    val threshPx = remember(density) { with(density) { 96.dp.toPx() } }
    val flingPx = remember(density) { with(density) { 640.dp.toPx() } }
    var showSheet by remember { mutableStateOf(false) }
    var cats by remember { mutableStateOf<List<Category>>(emptyList()) }
    var accounts by remember { mutableStateOf<List<Account>>(emptyList()) }
    var rawDetail by remember { mutableStateOf<String?>(null) }
    var recentTick by remember { mutableIntStateOf(0) }
    var recentList by remember { mutableStateOf<List<Txn>>(emptyList()) }
    var editTxn by remember { mutableStateOf<Txn?>(null) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val db = SpendDb.get(ctx)
            val items = db.txns().uncategorized()
            queue = items.groupBy { it.merchantRaw?.lowercase()?.trim() ?: "amt:${it.amountSar}|${it.txnType}" }.values.toList()
            total = queue.size
            cats = db.categories().all().filter { it.parentId != null && !it.system }
            accounts = db.accounts().all()
            loaded = true
        }
    }

    val currentTxnId = queue.firstOrNull()?.firstOrNull()?.id
    LaunchedEffect(currentTxnId) {
        rawDetail = if (currentTxnId == null) null else withContext(Dispatchers.IO) {
            SpendDb.get(ctx).txns().latestSightingFor(currentTxnId)?.excerpt
        }
    }
    // a fresh card (new currentTxnId) always starts centered, whatever state the last one ended in
    LaunchedEffect(currentTxnId) { offsetX.snapTo(0f) }

    // recently-classified revisit list: always kept fresh, independent of the swipe queue —
    // shown even when "all caught up", since that's exactly when a quick double-check is useful
    LaunchedEffect(recentTick, total) {
        recentList = withContext(Dispatchers.IO) {
            SpendDb.get(ctx).txns().recent(12).filter { it.categoryId != null || it.transferToAccountId != null }
        }
    }

    suspend fun advance() { queue = queue.drop(1); showSheet = false; offsetX.snapTo(0f); onChanged() }

    fun apply(group: List<Txn>, cat: Category, all: Boolean) {
        scope.launch {
            withContext(Dispatchers.IO) {
                val db = SpendDb.get(ctx)
                val merchant = group.first().merchantRaw
                if (all && merchant != null) {
                    db.txns().applyMerchantCategory(merchant, cat.id)
                    val mid = db.merchants().insert(Merchant(canonicalName = merchant, defaultCategoryId = cat.id))
                    db.merchants().insertAlias(MerchantAlias(merchantId = mid, alias = merchant.lowercase().trim()))
                    db.rules().insertMerchantRule(RuleMerchant(merchantId = mid, categoryId = cat.id, createdAt = System.currentTimeMillis()))
                } else {
                    for (t in group) db.txns().update(t.copy(categoryId = cat.id, classifiedBy = ClassifiedBy.MANUAL))
                }
            }
            advance()
        }
    }

    fun applyTransfer(group: List<Txn>, account: Account) {
        scope.launch {
            withContext(Dispatchers.IO) {
                val db = SpendDb.get(ctx)
                val betweenId = db.categories().idByNameEn("Between my accounts")
                for (t in group) db.txns().setTransferTarget(t.id, account.id, betweenId)
                Matcher(db).run()
            }
            advance()
        }
    }

    fun applyToTxn(t: Txn, cat: Category, all: Boolean) {
        scope.launch {
            withContext(Dispatchers.IO) {
                val db = SpendDb.get(ctx)
                if (all && t.merchantRaw != null) db.txns().applyMerchantCategory(t.merchantRaw, cat.id)
                db.txns().update(t.copy(categoryId = cat.id, classifiedBy = ClassifiedBy.MANUAL))
            }
            editTxn = null; recentTick++; onChanged()
        }
    }

    fun applyTransferToTxn(t: Txn, account: Account) {
        scope.launch {
            withContext(Dispatchers.IO) {
                val db = SpendDb.get(ctx)
                val betweenId = db.categories().idByNameEn("Between my accounts")
                db.txns().setTransferTarget(t.id, account.id, betweenId)
                Matcher(db).run()
            }
            editTxn = null; recentTick++; onChanged()
        }
    }

    val group = queue.firstOrNull()
    val dragState = rememberDraggableState { delta -> scope.launch { offsetX.snapTo(offsetX.value + delta) } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Review", color = Dim, fontSize = 12.sp)
            Text("${(total - queue.size + 1).coerceAtMost(total)} of $total", color = Faint, fontSize = 12.sp)
        }
        Spacer(Modifier.height(16.dp))
        when {
            !loaded -> Text("Loading…", color = Dim)
            group == null -> {
                Spacer(Modifier.height(60.dp))
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("✓", color = Mint, fontSize = 44.sp)
                    Text("All caught up", color = Ink, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                    Text("Every decision became a rule — tomorrow needs fewer.", color = Faint, fontSize = 12.sp)
                }
            }
            else -> {
                val t = group.first()
                val df = SimpleDateFormat("dd MMM HH:mm", Locale.US)
                Box(Modifier.fillMaxWidth().height(280.dp)
                    .graphicsLayer { translationX = offsetX.value; rotationZ = offsetX.value / 60f }
                    .clip(RoundedCornerShape(20.dp)).background(CardC)
                    .border(1.dp, LineC, RoundedCornerShape(20.dp))
                    .draggable(
                        state = dragState,
                        orientation = Orientation.Horizontal,
                        onDragStopped = { velocity ->
                            // momentum projection (Apple "Designing Fluid Interfaces" §6): decide
                            // commit vs. cancel from where the flick would land, not just where the
                            // finger happened to let go — a fast short drag can still commit.
                            val projected = projectedOffset(offsetX.value, velocity)
                            when {
                                projected > threshPx -> {
                                    offsetX.animateTo(threshPx * 1.3f,
                                        spring(dampingRatio = Motion.DampingMomentum, stiffness = Motion.stiffness(Motion.ResponseMomentum)),
                                        initialVelocity = velocity)
                                    showSheet = true
                                }
                                projected < -threshPx -> {
                                    offsetX.animateTo(-flingPx,
                                        spring(dampingRatio = Motion.DampingMomentum, stiffness = Motion.stiffness(Motion.ResponseMomentum)),
                                        initialVelocity = velocity)
                                    advance()
                                }
                                else -> offsetX.animateTo(0f, Motion.default(), initialVelocity = velocity)
                            }
                        })
                    .padding(20.dp)) {
                    Column {
                        Text(if (group.size > 1) "PATTERN FOUND" else "NEEDS A CATEGORY", color = Faint, fontSize = 10.sp, letterSpacing = 1.5.sp)
                        Spacer(Modifier.height(8.dp))
                        Text(t.merchantRaw ?: t.beneficiary ?: t.txnType.name, color = Ink, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Text((if (group.size > 1) "${group.size} similar transactions · " else "") +
                            "${fmt(t.amountSar)} SAR · ${df.format(Date(t.occurredAt))}", color = Dim, fontSize = 13.sp)
                        if (group.size > 1) {
                            Spacer(Modifier.height(10.dp))
                            Text("One decision classifies all ${group.size} — past and future.", color = Faint, fontSize = 11.sp)
                        }
                    }
                    Text("SKIP", color = Gold, fontSize = 12.sp, letterSpacing = 2.sp,
                        modifier = Modifier.align(Alignment.TopEnd).graphicsLayer { alpha = (-offsetX.value / threshPx).coerceIn(0f, 1f) })
                    Text("CATEGORIZE", color = Mint, fontSize = 12.sp, letterSpacing = 2.sp,
                        modifier = Modifier.align(Alignment.TopStart).graphicsLayer { alpha = (offsetX.value / threshPx).coerceIn(0f, 1f) })
                }
                Spacer(Modifier.height(14.dp))

                rawDetail?.let { detail ->
                    Text("FROM THE STATEMENT", color = Faint, fontSize = 10.sp, letterSpacing = 1.2.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(detail, color = Dim, fontSize = 11.sp, lineHeight = 15.sp,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(Card2).padding(10.dp))
                    Spacer(Modifier.height(14.dp))
                }

                if (group.size > 1) {
                    Text("${group.size} SIMILAR TRANSACTIONS", color = Faint, fontSize = 10.sp, letterSpacing = 1.2.sp)
                    Spacer(Modifier.height(6.dp))
                    val ddf = SimpleDateFormat("dd MMM yy, HH:mm", Locale.US)
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Card2).padding(10.dp)) {
                        group.sortedByDescending { it.occurredAt }.take(8).forEach { g ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(ddf.format(Date(g.occurredAt)), color = Dim, fontSize = 11.sp)
                                Text(fmt(g.amountSar), color = Dim, fontSize = 11.sp)
                            }
                        }
                        if (group.size > 8) Text("+ ${group.size - 8} more", color = Faint, fontSize = 10.sp,
                            modifier = Modifier.padding(top = 4.dp))
                    }
                    Spacer(Modifier.height(14.dp))
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { scope.launch { advance() } }, modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Gold)) { Text("Skip") }
                    Button(onClick = { showSheet = true }, modifier = Modifier.weight(1.4f),
                        colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Color.White)) { Text("Categorize") }
                }
                Text("or swipe the card →", color = Faint, fontSize = 11.sp,
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 10.dp))
            }
        }

        Spacer(Modifier.height(28.dp))
        Text("RECENTLY CLASSIFIED · tap to correct", color = Faint, fontSize = 10.sp, letterSpacing = 1.2.sp)
        Spacer(Modifier.height(8.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Card2).padding(6.dp)) {
            if (recentList.isEmpty()) {
                Text("Nothing classified yet.", color = Faint, fontSize = 11.sp, modifier = Modifier.padding(10.dp))
            } else {
                recentList.forEach { rt ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .fluidClickable { editTxn = rt }
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text(rt.merchantRaw ?: rt.beneficiary ?: rt.txnType.name, color = Ink, fontSize = 12.sp, maxLines = 1)
                            Text(if (rt.transferToAccountId != null) "transfer to own account"
                                 else cats.firstOrNull { it.id == rt.categoryId }?.nameEn ?: "—",
                                 color = Faint, fontSize = 10.sp)
                        }
                        Text(fmt(rt.amountSar), color = Dim, fontSize = 12.sp)
                    }
                }
            }
        }
        Spacer(Modifier.height(20.dp))
    }

    if (showSheet && group != null) {
        ModalBottomSheet(onDismissRequest = { showSheet = false; scope.launch { offsetX.animateTo(0f, Motion.default()) } }, containerColor = Card2) {
            ClassifySheetContent(
                count = group.size, accountId = group.first().accountId, cats = cats, accounts = accounts,
                merchantRaw = group.first().merchantRaw,
                onApplyCategory = { c, all -> apply(group, c, all) },
                onApplyTransfer = { a -> applyTransfer(group, a) })
        }
    }

    editTxn?.let { t ->
        ModalBottomSheet(onDismissRequest = { editTxn = null }, containerColor = Card2) {
            ClassifySheetContent(
                count = 1, accountId = t.accountId, cats = cats, accounts = accounts,
                merchantRaw = t.merchantRaw,
                onApplyCategory = { c, all -> applyToTxn(t, c, all) },
                onApplyTransfer = { a -> applyTransferToTxn(t, a) })
        }
    }
}

@Composable
fun ToolsScreen(onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var paste by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var showCategories by remember { mutableStateOf(false) }
    var showManualEntry by remember { mutableStateOf(true) }
    var showQuarantine by remember { mutableStateOf(false) }
    var reconciling by remember { mutableStateOf(false) }

    fun refreshStatus() {
        scope.launch {
            status = withContext(Dispatchers.IO) {
                val db = SpendDb.get(ctx)
                "Transactions ${db.txns().count()} · Quarantined ${db.ingest().openQuarantineCount()} · Links ${db.rules().linkCount()} · Merchants ${db.merchants().count()}"
            }
        }
    }
    LaunchedEffect(Unit) { refreshStatus() }

    val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        result = "Reading statement…"
        scope.launch {
            result = withContext(Dispatchers.IO) {
                val db = SpendDb.get(ctx)
                val r = StatementImporter(db, ctx).import(uri)
                r + "\nMatching: " + Matcher(db).run()
            }
            refreshStatus(); onChanged()
        }
    }
    val backupPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            try {
                val dbFile = ctx.getDatabasePath("spend.db")
                ctx.contentResolver.openOutputStream(uri)?.use { out -> dbFile.inputStream().use { it.copyTo(out) } }
            } catch (_: Exception) { }
        }
        result = "Backup saved (encrypted)."
    }
    var notifMsg by remember { mutableStateOf("") }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        scheduleWeeklySummary(ctx)
        notifMsg = if (granted || android.os.Build.VERSION.SDK_INT < 33)
            "Enabled — next one lands Friday evening, then every week."
        else "Scheduled, but notifications are blocked for this app — turn them on in phone Settings to actually see it."
    }

    var crashText by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        crashText = withContext(Dispatchers.IO) {
            val f = java.io.File(ctx.filesDir, "last_crash.txt")
            if (f.exists()) f.readText().take(2000) else null
        }
    }

    if (showCategories) {
        CategoriesScreen(onBack = { showCategories = false }, onChanged = onChanged)
        return
    }
    if (showQuarantine) {
        QuarantineScreen(onBack = { showQuarantine = false }, onChanged = { refreshStatus(); onChanged() })
        return
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp)) {
        Text("TOOLS", color = MintDim, fontSize = 11.sp, letterSpacing = 2.sp)
        Spacer(Modifier.height(14.dp))
        crashText?.let { txt ->
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .background(Gold.copy(alpha = 0.10f)).padding(12.dp)) {
                Text("The app crashed last time. Long-press to select and share this with the developer:",
                    color = Gold, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(txt, color = Dim, fontSize = 9.sp, lineHeight = 12.sp)
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = {
                    java.io.File(ctx.filesDir, "last_crash.txt").delete(); crashText = null
                }) { Text("Dismiss", fontSize = 12.sp) }
            }
            Spacer(Modifier.height(14.dp))
        }
        OutlinedButton(onClick = { showManualEntry = !showManualEntry }, modifier = Modifier.fillMaxWidth()) {
            Text(if (showManualEntry) "Hide manual entry" else "Manual entry (cash, balances, untracked spend)")
        }
        if (showManualEntry) {
            Spacer(Modifier.height(10.dp))
            ManualEntryBlock(onChanged = { refreshStatus(); onChanged() })
        }
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(value = paste, onValueChange = { paste = it },
            placeholder = { Text("Paste a bank SMS here", color = Faint) },
            modifier = Modifier.fillMaxWidth().height(130.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MintDim, unfocusedBorderColor = LineC,
                focusedTextColor = Ink, unfocusedTextColor = Ink))
        Spacer(Modifier.height(10.dp))
        Button(onClick = {
            val body = paste.trim(); if (body.isEmpty()) return@Button
            scope.launch {
                result = withContext(Dispatchers.IO) {
                    val db = SpendDb.get(ctx)
                    val o = Ingestor(db).ingest(null, body, SourceKind.PASTE)
                    val links = Matcher(db).run()
                    o.summary + "  [matching: $links]"
                }
                paste = ""; refreshStatus(); onChanged()
            }
        }, modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Color.White)) { Text("Parse & save") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { pdfPicker.launch(arrayOf("application/pdf")) }, modifier = Modifier.fillMaxWidth()) {
            Text("Import statement (PDF)")
        }
        OutlinedButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
            modifier = Modifier.fillMaxWidth()) { Text("Enable automatic SMS capture") }
        OutlinedButton(onClick = { backupPicker.launch("spend-backup.db") }, modifier = Modifier.fillMaxWidth()) {
            Text("Backup (encrypted)")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = {
            reconciling = true
            scope.launch {
                result = withContext(Dispatchers.IO) { Matcher(SpendDb.get(ctx)).run() }
                reconciling = false; refreshStatus(); onChanged()
            }
        }, modifier = Modifier.fillMaxWidth(), enabled = !reconciling) {
            Text(if (reconciling) "Reconciling…" else "Reconcile transfers")
        }
        OutlinedButton(onClick = { showCategories = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Manage categories")
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = { showQuarantine = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Review quarantine" + if (status.contains("Quarantined 0")) "" else " · needs a look")
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = {
            if (android.os.Build.VERSION.SDK_INT >= 33) notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            else { scheduleWeeklySummary(ctx); notifMsg = "Enabled — next one lands Friday evening, then every week." }
        }, modifier = Modifier.fillMaxWidth()) { Text("Enable weekly summary notification") }
        if (notifMsg.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(notifMsg, color = Faint, fontSize = 11.sp, lineHeight = 15.sp)
        }
        Spacer(Modifier.height(14.dp))
        if (result.isNotEmpty()) Text(result, color = Ink, fontSize = 13.sp,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Card2).padding(12.dp))
        Spacer(Modifier.height(10.dp))
        Text(status, color = Faint, fontSize = 11.sp)
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * Covers two related asks: logging a manual spend/income (cash, or anything on an account with
 * no SMS parser yet — Gold/Tamra/Awaed/STC Bank/Cash all need this) and setting an account's
 * starting balance. These are deliberately different operations, not one form: a balance is a
 * point-in-time number (writes to balance_checkpoint, same table Home already reads for account
 * balances — see doc §3), a transaction is a spend/income event (writes to txn). Conflating them
 * into a single "manual txn" would double count — a starting balance isn't spend or income.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualEntryBlock(onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(0) } // 0 = transaction, 1 = set balance
    var accounts by remember { mutableStateOf<List<Account>>(emptyList()) }
    var spendCats by remember { mutableStateOf<List<Category>>(emptyList()) }
    var incomeCats by remember { mutableStateOf<List<Category>>(emptyList()) }
    var account by remember { mutableStateOf<Account?>(null) }
    var category by remember { mutableStateOf<Category?>(null) }
    var isSpend by remember { mutableStateOf(true) }
    var amount by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var accExpanded by remember { mutableStateOf(false) }
    var catExpanded by remember { mutableStateOf(false) }
    var pickedMillis by remember { mutableStateOf<Long?>(null) } // null = log at current date/time
    var showDatePicker by remember { mutableStateOf(false) }
    val whenFmt = remember { SimpleDateFormat("d MMM yyyy, h:mm a", Locale.US) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val db = SpendDb.get(ctx)
            accounts = db.accounts().all().sortedBy { it.displayName }
            val all = db.categories().all()
            // "Frozen" only means Income/Transfers/Uncategorized can't be renamed or deleted in
            // Manage Categories — their children are still perfectly normal, pickable categories
            // for a transaction. Spend picks from everything else; income picks from Income's
            // own children (Salary, Investment profit, Cashback & rewards, Other income).
            val incomeParentId = all.firstOrNull { it.parentId == null && it.nameEn == "Income" }?.id
            incomeCats = all.filter { it.parentId == incomeParentId }.sortedBy { it.sort }
            spendCats = all.filter { it.parentId != null && !it.system }.sortedBy { it.sort }
        }
    }
    fun minorOf(text: String): Long? =
        text.trim().replace(",", "").toDoubleOrNull()?.let { Math.round(it * 100) }

    val fieldColors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MintDim, unfocusedBorderColor = LineC,
        focusedTextColor = Ink, unfocusedTextColor = Ink)

    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Card2).padding(14.dp)) {
        Row(Modifier.fillMaxWidth()) {
            FilterChip(selected = mode == 0, onClick = { mode = 0; msg = "" }, label = { Text("Transaction") },
                modifier = Modifier.weight(1f),
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Mint, selectedLabelColor = Color.White))
            Spacer(Modifier.width(8.dp))
            FilterChip(selected = mode == 1, onClick = { mode = 1; msg = "" }, label = { Text("Set balance") },
                modifier = Modifier.weight(1f),
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Mint, selectedLabelColor = Color.White))
        }
        Spacer(Modifier.height(10.dp))

        ExposedDropdownMenuBox(expanded = accExpanded, onExpandedChange = { accExpanded = it }) {
            OutlinedTextField(value = account?.displayName ?: "Choose account", onValueChange = {}, readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = accExpanded) },
                modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable), colors = fieldColors)
            ExposedDropdownMenu(expanded = accExpanded, onDismissRequest = { accExpanded = false }) {
                accounts.forEach { a ->
                    DropdownMenuItem(text = { Text(a.displayName) }, onClick = { account = a; accExpanded = false })
                }
            }
        }
        Spacer(Modifier.height(8.dp))

        OutlinedButton(onClick = { showDatePicker = true }, modifier = Modifier.fillMaxWidth()) {
            Text("When: " + (pickedMillis?.let { whenFmt.format(Date(it)) } ?: "now"), fontSize = 13.sp)
        }
        Spacer(Modifier.height(8.dp))

        if (mode == 0) {
            Row(Modifier.fillMaxWidth()) {
                FilterChip(selected = isSpend, onClick = { isSpend = true; category = null }, label = { Text("Spent") },
                    modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                FilterChip(selected = !isSpend, onClick = { isSpend = false; category = null }, label = { Text("Received") },
                    modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
        }

        OutlinedTextField(value = amount, onValueChange = { v -> amount = v.filter { it.isDigit() || it == '.' } },
            placeholder = { Text(if (mode == 0) "Amount (SAR)" else "Current balance (SAR)", color = Faint) },
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(), colors = fieldColors)

        if (mode == 0) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = desc, onValueChange = { desc = it },
                placeholder = { Text("Description", color = Faint) },
                modifier = Modifier.fillMaxWidth(), colors = fieldColors)
            Spacer(Modifier.height(8.dp))
            val catOptions = if (isSpend) spendCats else incomeCats
            ExposedDropdownMenuBox(expanded = catExpanded, onExpandedChange = { catExpanded = it }) {
                OutlinedTextField(value = category?.nameEn ?: "Choose category", onValueChange = {}, readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = catExpanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable), colors = fieldColors)
                ExposedDropdownMenu(expanded = catExpanded, onDismissRequest = { catExpanded = false }) {
                    catOptions.forEach { c ->
                        DropdownMenuItem(text = { Text(c.nameEn, color = colorForCat(c.nameEn)) },
                            onClick = { category = c; catExpanded = false })
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        if (msg.isNotEmpty()) { Text(msg, color = MintDim, fontSize = 12.sp); Spacer(Modifier.height(6.dp)) }

        Button(onClick = {
            val acc = account
            val minor = minorOf(amount)
            when {
                acc == null -> msg = "Pick an account first"
                minor == null || minor <= 0 -> msg = "Enter a valid amount"
                else -> scope.launch {
                    val whenMillis = pickedMillis ?: System.currentTimeMillis()
                    withContext(Dispatchers.IO) {
                        val db = SpendDb.get(ctx)
                        if (mode == 0) {
                            val incomeType = when (category?.nameEn) {
                                "Investment profit" -> TxnType.INCOME_INVESTMENT
                                "Cashback & rewards" -> TxnType.REBATE
                                else -> TxnType.INCOME_SALARY
                            }
                            val id = db.txns().insert(Txn(
                                accountId = acc.id, occurredAt = whenMillis,
                                direction = if (isSpend) Direction.DEBIT else Direction.CREDIT, amountSar = minor,
                                txnType = if (isSpend) TxnType.PURCHASE else incomeType,
                                merchantRaw = desc.ifBlank { null }, categoryId = category?.id,
                                classifiedBy = ClassifiedBy.MANUAL, includeInSpend = isSpend, status = TxnStatus.CONFIRMED,
                            ))
                            db.txns().insertSighting(Sighting(txnId = id, rawMessageId = null, statementImportId = null,
                                sourceKind = SourceKind.MANUAL, excerpt = desc.ifBlank { "Manual entry" },
                                seenAt = System.currentTimeMillis()))
                            // Home shows the last known balance_checkpoint, not a running total —
                            // a plain transaction never touched it before, so nudge it here too,
                            // if there's an existing balance to nudge from.
                            db.ingest().latestCheckpoint(acc.id)?.let { prev ->
                                val delta = if (isSpend) -minor else minor
                                db.ingest().insertCheckpoint(BalanceCheckpoint(accountId = acc.id,
                                    at = whenMillis, balanceMinor = prev.balanceMinor + delta,
                                    semantics = acc.balanceSemantics))
                            }
                        } else {
                            db.ingest().insertCheckpoint(BalanceCheckpoint(accountId = acc.id,
                                at = whenMillis, balanceMinor = minor, semantics = acc.balanceSemantics))
                        }
                    }
                    amount = ""; desc = ""; category = null; pickedMillis = null
                    msg = if (mode == 0) "Saved." else "Balance updated."
                    onChanged()
                }
            }
        }, modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Color.White)) {
            Text(if (mode == 0) "Save transaction" else "Save balance")
        }
    }

    if (showDatePicker) {
        val baseMillis = pickedMillis ?: System.currentTimeMillis()
        val dateState = rememberDatePickerState(initialSelectedDateMillis = baseMillis)
        val cal = remember(baseMillis) { Calendar.getInstance().apply { timeInMillis = baseMillis } }
        val timeState = rememberTimePickerState(
            initialHour = cal.get(Calendar.HOUR_OF_DAY), initialMinute = cal.get(Calendar.MINUTE), is24Hour = false)
        ModalBottomSheet(onDismissRequest = { showDatePicker = false }, containerColor = Card2) {
            Column(Modifier.padding(horizontal = 18.dp).padding(bottom = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                DatePicker(state = dateState, showModeToggle = false, title = null, headline = null)
                Spacer(Modifier.height(4.dp))
                TimePicker(state = timeState)
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { pickedMillis = null; showDatePicker = false }) { Text("Use now instead") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        // DatePickerState.selectedDateMillis is UTC midnight for the picked date,
                        // not local midnight — read it as a UTC date, then combine with the time
                        // picker's local hour/minute in the device's actual time zone. Naively
                        // adding the two millis together would shift the date/time in any zone
                        // other than UTC (Saudi Arabia is UTC+3).
                        val dm = dateState.selectedDateMillis ?: baseMillis
                        val date = Instant.ofEpochMilli(dm).atZone(ZoneOffset.UTC).toLocalDate()
                        val dt = date.atTime(timeState.hour, timeState.minute)
                        pickedMillis = dt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                        showDatePicker = false
                    }, colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Color.White)) { Text("Set") }
                }
            }
        }
    }
}

/**
 * Point 3 (editable categories), lives inside Tools per the user's explicit call not to add a
 * new bottom-nav tab. Two-level tree: system-flagged parents/children (Income, Transfers,
 * Uncategorized — the categories the parser and Matcher depend on by name) render "frozen" with
 * no edit/delete controls; everything else is fully editable. Delete reassigns any transactions
 * on that category to Uncategorized and drops any merchant rules pointing at it first, so the
 * DB is never left with a dangling categoryId.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CategoriesScreen(onBack: () -> Unit, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tree by remember { mutableStateOf<List<Pair<Category, List<Category>>>>(emptyList()) }
    var tick by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<Category?>(null) }
    var editingIsNew by remember { mutableStateOf(false) }
    var nameEn by remember { mutableStateOf("") }
    var nameAr by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf<Category?>(null) }
    var deleteWarning by remember { mutableStateOf<String?>(null) }
    var color by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(tick) {
        val all = withContext(Dispatchers.IO) { SpendDb.get(ctx).categories().all() }
        val parents = all.filter { it.parentId == null }
        tree = parents.map { p -> p to all.filter { it.parentId == p.id } }
    }

    fun openAdd(parentId: Long?) {
        editing = Category(parentId = parentId, nameEn = "", nameAr = "")
        editingIsNew = true; nameEn = ""; nameAr = ""; color = null
    }
    fun openEdit(c: Category) {
        editing = c; editingIsNew = false; nameEn = c.nameEn; nameAr = c.nameAr; color = c.customColor
    }
    fun save() {
        val e = editing ?: return
        val trimmedEn = nameEn.trim()
        if (trimmedEn.isEmpty()) return
        scope.launch {
            withContext(Dispatchers.IO) {
                val db = SpendDb.get(ctx)
                if (editingIsNew) db.categories().insert(e.copy(nameEn = trimmedEn, nameAr = nameAr.trim(), customColor = color))
                else db.categories().update(e.copy(nameEn = trimmedEn, nameAr = nameAr.trim(), customColor = color))
            }
            editing = null; tick++; onChanged()
        }
    }
    fun requestDelete(c: Category) {
        scope.launch {
            val (txnN, ruleN) = withContext(Dispatchers.IO) {
                val db = SpendDb.get(ctx)
                db.categories().txnCountUsing(c.id) to db.categories().merchantRuleCountUsing(c.id)
            }
            deleteWarning = if (txnN > 0 || ruleN > 0)
                "$txnN transaction(s) and $ruleN rule(s) use “${c.nameEn}”. Deleting moves those transactions to Uncategorized and removes the rules."
            else null
            confirmDelete = c
        }
    }
    fun doDelete(c: Category) {
        scope.launch {
            withContext(Dispatchers.IO) {
                val db = SpendDb.get(ctx)
                db.categories().reassignTxnsToUncategorized(c.id)
                db.categories().deleteMerchantRulesUsing(c.id)
                db.categories().delete(c.id)
            }
            confirmDelete = null; deleteWarning = null; tick++; onChanged()
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("‹ Back", color = Mint, fontSize = 13.sp, modifier = Modifier.fluidClickable { onBack() })
            Spacer(Modifier.weight(1f))
            Text("CATEGORIES", color = MintDim, fontSize = 11.sp, letterSpacing = 2.sp)
        }
        Spacer(Modifier.height(6.dp))
        Text("Frozen categories (Income, Transfers, Uncategorized) keep automatic classification working and can't be edited or deleted.",
            color = Faint, fontSize = 11.sp, lineHeight = 15.sp)
        Spacer(Modifier.height(14.dp))
        tree.forEach { (parent, kids) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(colorForCat(parent)))
                    Spacer(Modifier.width(8.dp))
                    Text(parent.nameEn, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    if (parent.system) { Spacer(Modifier.width(6.dp)); Chip("frozen", Faint) }
                }
                Row {
                    if (!parent.system) {
                        Text("Edit", color = MintDim, fontSize = 12.sp, modifier = Modifier.fluidClickable { openEdit(parent) })
                        Spacer(Modifier.width(12.dp))
                        Text("Delete", color = Gold, fontSize = 12.sp, modifier = Modifier.fluidClickable { requestDelete(parent) })
                    } else {
                        Text("Color", color = MintDim, fontSize = 12.sp, modifier = Modifier.fluidClickable { openEdit(parent) })
                    }
                }
            }
            kids.forEach { c ->
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 3.dp, bottom = 3.dp),
                    horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(7.dp).clip(RoundedCornerShape(2.dp)).background(colorForCat(c)))
                        Spacer(Modifier.width(7.dp))
                        Text(c.nameEn, color = Dim, fontSize = 13.sp)
                    }
                    if (!c.system) Row {
                        Text("Edit", color = MintDim, fontSize = 11.sp, modifier = Modifier.fluidClickable { openEdit(c) })
                        Spacer(Modifier.width(12.dp))
                        Text("Delete", color = Gold, fontSize = 11.sp, modifier = Modifier.fluidClickable { requestDelete(c) })
                    } else Row {
                        Text("Color", color = MintDim, fontSize = 11.sp, modifier = Modifier.fluidClickable { openEdit(c) })
                        Spacer(Modifier.width(12.dp))
                        Chip("frozen", Faint)
                    }
                }
            }
            if (!parent.system) Text("+ Add subcategory", color = MintDim, fontSize = 12.sp,
                modifier = Modifier.padding(start = 16.dp, top = 2.dp).fluidClickable { openAdd(parent.id) })
            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = LineC)
        }
        Spacer(Modifier.height(14.dp))
        OutlinedButton(onClick = { openAdd(null) }, modifier = Modifier.fillMaxWidth()) { Text("+ Add main category") }
        Spacer(Modifier.height(24.dp))
    }

    editing?.let { e ->
        ModalBottomSheet(onDismissRequest = { editing = null }, containerColor = Card2) {
            Column(Modifier.padding(18.dp)) {
                Text(
                    if (e.system) "Category color"
                    else if (editingIsNew) (if (e.parentId == null) "New main category" else "New subcategory")
                    else "Edit category",
                    color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                if (e.system) {
                    Spacer(Modifier.height(4.dp))
                    Text("“${e.nameEn}” is a system category — its name is fixed, but you can still pick its color.",
                        color = Faint, fontSize = 11.sp, lineHeight = 15.sp)
                }
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(value = nameEn, onValueChange = { nameEn = it }, readOnly = e.system,
                    label = { Text("Name (English)", color = Faint) }, modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MintDim, unfocusedBorderColor = LineC,
                        focusedTextColor = Ink, unfocusedTextColor = Ink))
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(value = nameAr, onValueChange = { nameAr = it }, readOnly = e.system,
                    label = { Text("Name (Arabic)", color = Faint) }, modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MintDim, unfocusedBorderColor = LineC,
                        focusedTextColor = Ink, unfocusedTextColor = Ink))
                Spacer(Modifier.height(14.dp))
                Text("Color", color = Faint, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CatPalette.forEach { swatch ->
                        val hex = "#%06X".format(swatch.toArgb() and 0xFFFFFF)
                        Box(Modifier.size(32.dp).clip(RoundedCornerShape(16.dp)).background(swatch)
                            .border(if (color == hex) 2.dp else 0.dp, Ink, RoundedCornerShape(16.dp))
                            .fluidClickable { color = hex })
                    }
                    Box(Modifier.size(32.dp).clip(RoundedCornerShape(16.dp)).background(Card2)
                        .border(1.dp, if (color == null) Mint else LineC, RoundedCornerShape(16.dp))
                        .fluidClickable { color = null }, contentAlignment = Alignment.Center) {
                        Text("auto", color = Dim, fontSize = 8.sp)
                    }
                }
                Spacer(Modifier.height(16.dp))
                Button(onClick = { save() }, modifier = Modifier.fillMaxWidth(), enabled = nameEn.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Color.White)) { Text("Save") }
                Spacer(Modifier.height(20.dp))
            }
        }
    }

    confirmDelete?.let { c ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null; deleteWarning = null },
            title = { Text("Delete “${c.nameEn}”?", color = Ink) },
            text = { Text(deleteWarning ?: "This category isn't used by any transaction or rule.", color = Dim, fontSize = 13.sp) },
            confirmButton = { TextButton(onClick = { doDelete(c) }) { Text("Delete", color = Gold) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null; deleteWarning = null }) { Text("Cancel", color = Dim) } },
            containerColor = Card2)
    }
}

/**
 * Surfaces what §10 in the spec called an invisible gap: messages the parser recognized as
 * probably a transaction but couldn't fully resolve (no account match, or no parser at all)
 * used to only show as a raw count on the Tools status line. v1 scope is deliberately simple —
 * read the raw text, log it by hand in Manual entry if it's worth tracking, then dismiss —
 * rather than trying to auto-extract structured fields from arbitrary quarantined text.
 */
@Composable
fun QuarantineScreen(onBack: () -> Unit, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<Quarantine>>(emptyList()) }
    var tick by remember { mutableIntStateOf(0) }
    val df = remember { SimpleDateFormat("d MMM, h:mm a", Locale.US) }

    LaunchedEffect(tick) {
        items = withContext(Dispatchers.IO) { SpendDb.get(ctx).ingest().openQuarantine() }
    }
    fun dismiss(id: Long) {
        scope.launch {
            withContext(Dispatchers.IO) { SpendDb.get(ctx).ingest().deleteQuarantine(id) }
            tick++; onChanged()
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("‹ Back", color = Mint, fontSize = 13.sp, modifier = Modifier.fluidClickable { onBack() })
            Spacer(Modifier.weight(1f))
            Text("QUARANTINE", color = MintDim, fontSize = 11.sp, letterSpacing = 2.sp)
        }
        Spacer(Modifier.height(6.dp))
        Text("Messages the parser couldn't confidently turn into a transaction. Read the text, " +
            "log it in Manual entry if you want it tracked, then dismiss it here.",
            color = Faint, fontSize = 11.sp, lineHeight = 15.sp)
        Spacer(Modifier.height(14.dp))
        if (items.isEmpty()) {
            Spacer(Modifier.height(30.dp))
            Text("Nothing waiting here.", color = Faint, fontSize = 13.sp)
        }
        items.forEach { q ->
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(12.dp))
                .background(Card2).padding(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(q.reason, color = Gold, fontSize = 11.sp)
                    Text(df.format(Date(q.createdAt)), color = Faint, fontSize = 11.sp)
                }
                Spacer(Modifier.height(6.dp))
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(q.rawText, color = Ink, fontSize = 12.sp, lineHeight = 16.sp)
                }
                Spacer(Modifier.height(8.dp))
                Text("Dismiss", color = MintDim, fontSize = 12.sp, modifier = Modifier.fluidClickable { dismiss(q.id) })
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
