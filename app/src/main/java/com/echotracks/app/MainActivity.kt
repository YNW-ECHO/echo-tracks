package com.echotracks.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.navigation.compose.*
import com.echotracks.app.data.BudgetStore
import com.echotracks.app.data.CategoryBudgets
import com.echotracks.app.data.EchoDb
import com.echotracks.app.data.EchoEntity
import com.echotracks.app.data.EchoStats
import com.echotracks.app.data.Exporter
import com.echotracks.app.data.LearnStore
import com.echotracks.app.data.MoneyQuotes
import com.echotracks.app.data.OverspendAlerter
import com.echotracks.app.data.ReportPdf
import com.echotracks.app.data.RescanWorker
import com.echotracks.app.data.SmsReader
import com.echotracks.app.data.AppPrefs
import com.echotracks.app.data.UpdateChecker
import com.echotracks.app.model.*
import com.echotracks.app.security.AppLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

val Mint = Color(0xFF00E5A0); val DeepMint = Color(0xFF00B87E); val Midnight = Color(0xFF0E1A2B)
val CardBg = Color(0xFF16263D); val CardHi = Color(0xFF1E3252); val Ink = Color(0xFFF2F7F5)
val Rose = Color(0xFFFF8A80); val Amber = Color(0xFFFFB020); val Sky = Color(0xFF7C9AFF)
val HeroBrush = Brush.horizontalGradient(listOf(Color(0xFF123B2F), Color(0xFF16263D)))

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Screenshots ALLOWED (no FLAG_SECURE): user wants to share/save
        // dashboard shots. PIN + data still protected by app lock.
        setContent {
            ThemedEchoApp()
        }
    }
}

@Composable
fun ThemedEchoApp() {
    val ctx = LocalContext.current
    var themeMode by remember { mutableStateOf(AppPrefs.theme(ctx)) }
    var fontKey by remember { mutableStateOf(AppPrefs.font(ctx)) }
    var fontScale by remember { mutableStateOf(AppPrefs.fontScale(ctx)) }
    // refresh prefs when returning from Menu (Menu writes prefs then pops back)
    LaunchedEffect(Unit) {
        // best-effort live refresh handled via callbacks below too
    }
    val dark = when (themeMode) {
        AppPrefs.THEME_LIGHT -> false
        AppPrefs.THEME_DARK -> true
        else -> androidx.compose.foundation.isSystemInDarkTheme()
    }
    val scheme = if (dark) darkColorScheme(
        primary = Mint, background = Midnight, surface = CardBg,
        onBackground = Ink, onSurface = Ink
    ) else lightColorScheme(
        primary = DeepMint, background = Color(0xFFF4F7F5), surface = Color.White,
        onBackground = Color(0xFF0E1A2B), onSurface = Color(0xFF0E1A2B)
    )
    val ff = when (fontKey) {
        AppPrefs.FONT_SERIF -> FontFamily.Serif
        AppPrefs.FONT_MONO -> FontFamily.Monospace
        else -> FontFamily.Default
    }
    val base = MaterialTheme.typography
    val scaled = base.copy(
        displayLarge = base.displayLarge.copy(fontFamily = ff, fontSize = base.displayLarge.fontSize * fontScale),
        headlineSmall = base.headlineSmall.copy(fontFamily = ff, fontSize = base.headlineSmall.fontSize * fontScale),
        titleLarge = base.titleLarge.copy(fontFamily = ff, fontSize = base.titleLarge.fontSize * fontScale),
        titleMedium = base.titleMedium.copy(fontFamily = ff, fontSize = base.titleMedium.fontSize * fontScale),
        bodyLarge = base.bodyLarge.copy(fontFamily = ff, fontSize = base.bodyLarge.fontSize * fontScale),
        bodyMedium = base.bodyMedium.copy(fontFamily = ff, fontSize = base.bodyMedium.fontSize * fontScale),
        bodySmall = base.bodySmall.copy(fontFamily = ff, fontSize = base.bodySmall.fontSize * fontScale),
        labelSmall = base.labelSmall.copy(fontFamily = ff, fontSize = base.labelSmall.fontSize * fontScale),
        labelMedium = base.labelMedium.copy(fontFamily = ff, fontSize = base.labelMedium.fontSize * fontScale),
    )
    MaterialTheme(colorScheme = scheme, typography = scaled) {
        EchoApp(
            onPrefsChanged = {
                themeMode = AppPrefs.theme(ctx)
                fontKey = AppPrefs.font(ctx)
                fontScale = AppPrefs.fontScale(ctx)
            }
        )
    }
}

@Composable
fun EchoApp(onPrefsChanged: () -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var txs by remember { mutableStateOf<List<EchoTransaction>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var granted by remember { mutableStateOf(hasSms(ctx)) }
    var hideNoise by remember { mutableStateOf(true) }
    var rangeKey by remember { mutableStateOf("ALL") } // ALL/1M/3M/6M
    var sourceTab by remember { mutableStateOf("ALL") }
    var query by remember { mutableStateOf("") }
    var unlocked by remember { mutableStateOf(!AppLock.hasPin(ctx)) }
    var pinInput by remember { mutableStateOf("") }
    var pinSetup by remember { mutableStateOf("") }
    var update by remember { mutableStateOf<UpdateChecker.UpdateInfo?>(null) }
    var selectedTx by remember { mutableStateOf<EchoTransaction?>(null) }
    var infoTitle by remember { mutableStateOf<String?>(null) }
    var infoBody by remember { mutableStateOf("") }
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route ?: "Home"

    fun applyLearned(list: List<EchoTransaction>): List<EchoTransaction> {
        return try {
            list.map { t ->
                val fixed = LearnStore.applyLearned(ctx, t.who, t.rawSms, t.category)
                if (fixed != t.category) t.copy(category = fixed) else t
            }
        } catch (_: Exception) { list }
    }

    suspend fun loadAndCache(): List<EchoTransaction> {
        val raw = SmsReader.toTransactions(SmsReader.readAll(ctx))
        val tx = applyLearned(raw)
        try {
            EchoDb.get(ctx).dao().insertAll(tx.map {
                EchoEntity(
                    it.code, it.amount, it.who, it.dateMillis,
                    it.source.name, it.direction.name,
                    it.category.name, it.spendType.name, it.rawSms.take(500)
                )
            })
        } catch (_: Exception) {}
        return tx
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { ok ->
        granted = ok
        if (ok) {
            loading = true
            scope.launch(Dispatchers.IO) {
                try {
                    val tx = loadAndCache()
                    withContext(Dispatchers.Main) {
                        txs = tx; loading = false
                        Toast.makeText(ctx, "${tx.size} echoes loaded", Toast.LENGTH_SHORT).show()
                    }
                } catch (_: Exception) {
                    withContext(Dispatchers.Main) { loading = false }
                }
            }
        } else {
            Toast.makeText(ctx, "SMS permission needed for auto-tracking", Toast.LENGTH_LONG).show()
        }
    }
    LaunchedEffect(granted) {
        if (granted && txs.isEmpty() && !loading) {
            loading = true
            scope.launch(Dispatchers.IO) {
                // 1) instant: Room cache (works offline, fast with 8k SMS)
                try {
                    val cached = EchoDb.get(ctx).dao().all()
                    if (cached.isNotEmpty()) {
                        val mapped = cached.map {
                            val baseCat =
                                try { Category.valueOf(it.category) } catch (_: Exception) { Category.UNCATEGORIZED }
                            // apply taught rules instantly, even offline
                            val fixed = try {
                                LearnStore.applyLearned(ctx, it.who, it.raw, baseCat)
                            } catch (_: Exception) { baseCat }
                            EchoTransaction(
                                it.code, it.amount, it.who, it.date,
                                try { Source.valueOf(it.source) } catch (_: Exception) { Source.MPESA },
                                try { Direction.valueOf(it.direction) } catch (_: Exception) { Direction.OUT },
                                fixed,
                                try { SpendType.valueOf(it.spendType) } catch (_: Exception) { SpendType.REAL_SPEND },
                                null, it.raw
                            )
                        }.sortedByDescending { it.dateMillis }
                        withContext(Dispatchers.Main) { txs = applyLearned(mapped) }
                    }
                } catch (_: Exception) {}
                // 2) fresh: inbox rescan + cache new
                try {
                    val tx = loadAndCache()
                    withContext(Dispatchers.Main) { txs = tx; loading = false }
                } catch (_: Exception) {
                    withContext(Dispatchers.Main) { loading = false }
                }
            }
        }
    }
    // Daily background rescan (once per install)
    LaunchedEffect(Unit) { RescanWorker.schedule(ctx) }
    // In-app updater: check GitHub Releases/latest once per launch (offline-safe)
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val info = UpdateChecker.check(ctx)
            if (info != null) {
                withContext(Dispatchers.Main) { update = info }
                UpdateChecker.notify(ctx, info)
            }
        }
    }
    // Update dialog (shown above lock gate so you never miss it)
    update?.let { info ->
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Echo Tracks ${info.tag} available") },
            text = { Text("A new version is on GitHub. Tap Download to update, then install the APK (your data + PIN stay).") },
            confirmButton = {
                TextButton(onClick = {
                    UpdateChecker.openUpdate(ctx, info.url)
                    UpdateChecker.dismiss(ctx, info.tag); update = null
                }) { Text("Download") }
            },
            dismissButton = {
                TextButton(onClick = {
                    UpdateChecker.dismiss(ctx, info.tag); update = null
                }) { Text("Later") }
            }
        )
    }

    // Generic info dialog — makes AssistChips / insights tappable with real content
    infoTitle?.let { t ->
        AlertDialog(
            onDismissRequest = { infoTitle = null },
            title = { Text(t) },
            text = { Text(infoBody) },
            confirmButton = { TextButton(onClick = { infoTitle = null }) { Text("Close") } }
        )
    }

    // Transaction detail dialog — every list row opens this + Teach Echo
    selectedTx?.let { t ->
        var teachOpen by remember(t.code) { mutableStateOf(false) }
        var teachCat by remember(t.code) { mutableStateOf(t.category) }
        var teachKey by remember(t.code) { mutableStateOf(LearnStore.keyFor(t.who, t.rawSms)) }
        AlertDialog(
            onDismissRequest = { selectedTx = null },
            title = {
                Column {
                    Text(
                        "${if (t.direction == Direction.OUT) "−" else "+"} ${"%,.0f".format(t.amount)}",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (t.direction == Direction.OUT) Rose else Mint
                    )
                    Text(
                        t.who,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        AssistChip(onClick = {}, label = { Text(t.category.name.lowercase()) })
                        AssistChip(onClick = {}, label = { Text(t.source.name) })
                        AssistChip(onClick = {}, label = { Text(t.spendType.name) })
                    }
                    Text(java.text.DateFormat.getDateTimeInstance().format(java.util.Date(t.dateMillis)))
                    Text("Code: ${t.code}", style = MaterialTheme.typography.bodySmall)
                    if (t.balanceAfter != null) Text(
                        "Balance after: ${"%,.0f".format(t.balanceAfter)}",
                        style = MaterialTheme.typography.bodySmall, color = Sky
                    )
                    if (t.rawSms.isNotBlank()) Card(colors = CardDefaults.cardColors(containerColor = Midnight)) {
                        Text(
                            t.rawSms.take(500),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                    if (t.category == Category.UNCATEGORIZED && !teachOpen) {
                        Button(
                            onClick = { teachOpen = true },
                            colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Midnight)
                        ) { Text("🏷️ Teach Echo — classify this") }
                    }
                    if (teachOpen || t.category == Category.UNCATEGORIZED && teachOpen) {
                        OutlinedTextField(
                            value = teachKey, onValueChange = { teachKey = it.take(40) },
                            label = { Text("Keyword to remember (e.g. kibandaski)") },
                            singleLine = true, modifier = Modifier.fillMaxWidth()
                        )
                        var drop by remember { mutableStateOf(false) }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { drop = true }, modifier = Modifier.weight(1f)) {
                                Text("Category: ${teachCat.name.lowercase()}")
                            }
                            DropdownMenu(expanded = drop, onDismissRequest = { drop = false }) {
                                Category.values().filter { it != Category.UNCATEGORIZED }.forEach { c ->
                                    DropdownMenuItem(text = { Text(c.name.lowercase()) }, onClick = {
                                        teachCat = c; drop = false
                                    })
                                }
                            }
                        }
                        Button(
                            onClick = {
                                if (teachKey.isBlank()) {
                                    Toast.makeText(ctx, "Type a keyword first", Toast.LENGTH_SHORT).show()
                                } else {
                                    LearnStore.set(ctx, teachKey.trim().lowercase(), teachCat)
                                    // regroup ALL similar in memory instantly
                                    val key = teachKey.trim().lowercase()
                                    txs = txs.map { x ->
                                        if (("${x.who} ${x.rawSms}".lowercase().contains(key)) &&
                                            x.category == Category.UNCATEGORIZED
                                        ) x.copy(category = teachCat) else x
                                    }
                                    selectedTx = selectedTx?.copy(category = teachCat)
                                    teachOpen = false
                                    Toast.makeText(
                                        ctx,
                                        "Learned “$key” → ${teachCat.name.lowercase()} (${LearnStore.count(ctx)} rules)",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Midnight),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Save & apply to all similar") }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    query = t.who; selectedTx = null
                    nav.navigate("Echoes") { launchSingleTop = true }
                }) { Text("Show all from this") }
            },
            dismissButton = { TextButton(onClick = { selectedTx = null }) { Text("Close") } }
        )
    }

    if (!unlocked) {
        val act = ctx as? androidx.fragment.app.FragmentActivity
        LockGate(
            pinInput = pinInput, onInput = { pinInput = it.filter { c -> c.isDigit() }.take(8) },
            onUnlock = {
                if (AppLock.checkPin(ctx, pinInput)) { unlocked = true; pinInput = "" }
                else Toast.makeText(ctx, "Wrong PIN", Toast.LENGTH_SHORT).show()
            },
            setupMode = !AppLock.hasPin(ctx), setup = pinSetup, onSetup = { pinSetup = it.filter { c -> c.isDigit() }.take(8) },
            onSavePin = {
                if (pinSetup.length >= 4) {
                    AppLock.setPin(ctx, pinSetup); pinSetup = ""
                    unlocked = true
                    Toast.makeText(ctx, "PIN saved", Toast.LENGTH_SHORT).show()
                } else Toast.makeText(ctx, "PIN must be 4+ digits", Toast.LENGTH_SHORT).show()
            },
            canBio = act != null && AppPrefs.bioEnabled(ctx) && AppLock.canUseBiometric(ctx),
            onBio = {
                if (act != null) AppLock.promptBiometric(act) { unlocked = true }
                else Toast.makeText(ctx, "No fingerprint on this device", Toast.LENGTH_SHORT).show()
            }
        )
        return
    }

    val from = when (rangeKey) {
        "TODAY" -> EchoStats.startOfDay(System.currentTimeMillis())
        "7D" -> EchoStats.daysAgo(7)
        "1M" -> EchoStats.monthsAgo(1); "3M" -> EchoStats.monthsAgo(3); "6M" -> EchoStats.monthsAgo(6); else -> null
    }
    val filtered = remember(txs, from, hideNoise, sourceTab, query) {
        var list = EchoStats.inRange(txs, from, null)
        if (sourceTab != "ALL") list = list.filter { it.source.name == sourceTab }
        list = EchoStats.visible(list, hideNoise)
        EchoStats.search(list, query)
    }
    val (spent, income) = EchoStats.totals(filtered)
    val net = income - spent

    fun filterToEchoes(q: String) {
        query = q
        nav.navigate("Echoes") { launchSingleTop = true }
    }

    // Overspend alerts: re-check whenever transactions land (offline, once per month per level)
    LaunchedEffect(txs) {
        if (txs.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                try {
                    val spend = EchoStats.monthSpent(EchoStats.visible(txs, true))
                    OverspendAlerter.check(ctx, spend, BudgetStore.get(ctx))
                } catch (_: Exception) { }
            }
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = CardBg) {
                listOf("Home", "Echoes", "Stats", "Sources", "Menu").forEach { s ->
                    NavigationBarItem(
                        selected = currentRoute == s,
                        onClick = { nav.navigate(s) { launchSingleTop = true } },
                        label = { Text(s) },
                        icon = {
                            Text(
                                when (s) { "Home" -> "◉"; "Echoes" -> "☰"; "Stats" -> "◈"; "Sources" -> "⬣"; else -> "☰" },
                                color = if (currentRoute == s) Mint else Ink
                            )
                        }
                    )
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad)) {
            NavHost(nav, startDestination = "Home") {
                composable("Home") {
                    DashboardHome(
                        spent = spent, income = income, net = net, txs = filtered,
                        loading = loading, granted = granted,
                        onGrant = { launcher.launch(Manifest.permission.READ_SMS) },
                        onRescan = {
                            loading = true
                            scope.launch(Dispatchers.IO) {
                                try {
                                    val tx = loadAndCache()
                                    withContext(Dispatchers.Main) {
                                        txs = tx; loading = false
                                        Toast.makeText(ctx, "${tx.size} echoes refreshed", Toast.LENGTH_SHORT).show()
                                    }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        loading = false
                                        Toast.makeText(ctx, "Rescan failed: ${e.message}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        },
                        hideNoise = hideNoise, onToggleNoise = { hideNoise = !hideNoise },
                        rangeKey = rangeKey, onRange = { rangeKey = it },
                        sourceTab = sourceTab, onSource = { sourceTab = it },
                        query = query, onQuery = { query = it },
                        onTxClick = { selectedTx = it },
                        onCategoryClick = { c -> filterToEchoes(c.name.lowercase()) },
                        onPersonClick = { w -> filterToEchoes(w) },
                        onGoEchoes = { nav.navigate("Echoes") { launchSingleTop = true } },
                        onGoStats = { nav.navigate("Stats") { launchSingleTop = true } },
                        onInfo = { t, b -> infoTitle = t; infoBody = b },
                        onExport = {
                            scope.launch(Dispatchers.IO) {
                                try {
                                    val csv = Exporter.toCsv(filtered)
                                    val f = Exporter.savePublic(ctx, "echo_tracks_${System.currentTimeMillis()}.csv", csv)
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(
                                            ctx,
                                            if (filtered.isEmpty()) "Nothing to export in this view"
                                            else "Saved ${filtered.size} rows: ${f.absolutePath}",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(ctx, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        },
                        onWeeklyPdf = {
                            scope.launch(Dispatchers.IO) {
                                try {
                                    val f = ReportPdf.weekly(ctx, txs)
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(ctx, "Weekly PDF saved: ${f.absolutePath}", Toast.LENGTH_LONG).show()
                                    }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(ctx, "PDF failed: ${e.message}", Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        },
                        onMonthlyPdf = {
                            scope.launch(Dispatchers.IO) {
                                try {
                                    val f = ReportPdf.monthly(ctx, txs)
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(ctx, "Monthly PDF (merged weeks) saved: ${f.absolutePath}", Toast.LENGTH_LONG).show()
                                    }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(ctx, "PDF failed: ${e.message}", Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        }
                    )
                }
                composable("Echoes") {
                    EchoesGrouped(
                        txs = filtered, query = query, onQuery = { query = it },
                        onTxClick = { selectedTx = it },
                        onClearFilters = { query = ""; rangeKey = "ALL"; sourceTab = "ALL"; hideNoise = false }
                    )
                }
                composable("Stats") {
                    StatsDash(txs = filtered, onCategoryClick = { c -> filterToEchoes(c.name.lowercase()) })
                }
                composable("Sources") { SourcesScreen(all = txs, tab = sourceTab, onTab = { sourceTab = it }) }
                composable("Menu") {
                    SettingsScreen(
                        onPrefsChanged = onPrefsChanged,
                        onLockNow = {
                            pinInput = ""
                            unlocked = !AppLock.hasPin(ctx)
                        }
                    )
                }
            }
        }
    }
}

fun hasSms(ctx: android.content.Context) =
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

// ---------- LOCK ----------
@Composable
fun LockGate(pinInput: String, onInput: (String) -> Unit, onUnlock: () -> Unit,
              setupMode: Boolean, setup: String, onSetup: (String) -> Unit, onSavePin: () -> Unit,
              canBio: Boolean = false, onBio: () -> Unit = {}) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text("◉ Echo Tracks", color = Mint, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        if (setupMode) {
            Text("Set a 4+ digit PIN to protect your money data")
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = setup, onValueChange = onSetup, label = { Text("New PIN") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                singleLine = true
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onSavePin, enabled = setup.length >= 4,
                colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Midnight)
            ) { Text("Save & Enter") }
        } else {
            Text("Enter PIN or use fingerprint")
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = pinInput, onValueChange = onInput, label = { Text("PIN") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                singleLine = true
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onUnlock, enabled = pinInput.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Midnight)
            ) { Text("Unlock") }
            if (canBio) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onBio) { Text("Use fingerprint", color = Mint) }
            }
        }
    }
}

// ---------- PRO UI BITS ----------
@Composable
fun StatPill(label: String, value: Double, color: Color, modifier: Modifier = Modifier) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Midnight.copy(alpha = 0.65f)),
        shape = RoundedCornerShape(14.dp), modifier = modifier
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = Ink.copy(alpha = 0.7f))
            Text(
                "%,.0f".format(value),
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = color
            )
        }
    }
}

@Composable
fun SectionHeader(title: String, accent: Color = Mint, onSeeAll: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(
            title, color = accent, fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleMedium, fontStyle = FontStyle.Italic,
            fontFamily = FontFamily.Serif
        )
        if (onSeeAll != null) TextButton(onClick = onSeeAll) { Text("See all", color = accent) }
    }
}

// Smooth touch helper lives inside DashboardHome as tap() (haptic + ripple).
// Ripple comes free with Modifier.clickable on every section row.

@Composable
fun GoalCard(monthSpend: Double, onSaved: (Double) -> Unit = {}) {
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var goalTxt by remember { mutableStateOf(BudgetStore.get(ctx).toInt().toString()) }
    var savedFlash by remember { mutableStateOf(false) }
    val goal = goalTxt.toDoubleOrNull()?.coerceAtLeast(1.0) ?: 1.0
    val pct = (monthSpend / goal).coerceIn(0.0, 1.0)
    val animPct by animateFloatAsState(targetValue = pct.toFloat(), label = "goalPct")
    val barColor = if (pct >= 1.0) Rose else if (pct >= 0.8) Amber else Mint
    val left = goal - monthSpend
    LaunchedEffect(savedFlash) {
        if (savedFlash) { kotlinx.coroutines.delay(1500); savedFlash = false }
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = CardBg),
        shape = RoundedCornerShape(18.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            Modifier.background(Brush.verticalGradient(listOf(CardBg, Color(0xFF101F36)))).padding(18.dp)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "🎯 Monthly budget goal", color = Mint, fontWeight = FontWeight.Bold,
                    fontStyle = FontStyle.Italic, fontFamily = FontFamily.Serif,
                    style = MaterialTheme.typography.titleMedium
                )
                TextButton(onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    Toast.makeText(ctx, "Spent ${"%,.0f".format(monthSpend)} this month", Toast.LENGTH_SHORT).show()
                }) { Text(if (savedFlash) "✓ Saved!" else "Details", color = if (savedFlash) Mint else Mint) }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Spent ${"%,.0f".format(monthSpend)} of ${"%,.0f".format(goal)}",
                style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = barColor
            )
            Text(
                if (left >= 0) "${(pct * 100).toInt()}% used • ${"%,.0f".format(left)} left"
                else "${(pct * 100).toInt()}% used • ${"%,.0f".format(-left)} over",
                style = MaterialTheme.typography.bodySmall, color = Ink.copy(alpha = 0.75f)
            )
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { animPct },
                color = barColor, trackColor = Midnight,
                modifier = Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(8.dp))
                    .clickable {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        Toast.makeText(ctx, "${(pct * 100).toInt()}% of goal used", Toast.LENGTH_SHORT).show()
                    }
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (pct >= 1.0) "⚠ Over budget — freeze non-essentials. Alert sent to notifications."
                else if (pct >= 0.8) "Caution: 80%+ used — alert sent. Slow down eating-out."
                else "On track. Keep it under.",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(20000.0 to "20k", 30000.0 to "30k", 50000.0 to "50k", 100000.0 to "100k").forEach { (v, label) ->
                    FilterChip(
                        selected = kotlin.math.abs(goal - v) < 1.0,
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            goalTxt = v.toInt().toString()
                            BudgetStore.set(ctx, v)
                            onSaved(v)
                            savedFlash = true
                            Toast.makeText(ctx, "Goal set to $label", Toast.LENGTH_SHORT).show()
                        },
                        label = { Text(label) }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            // Always-visible input + Save: type any amount, tap Save, done.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = goalTxt,
                    onValueChange = { goalTxt = it.filter { c -> c.isDigit() }.take(9) },
                    label = { Text("Goal (Ksh)") }, modifier = Modifier.weight(1f), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                Button(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        val g = goalTxt.toDoubleOrNull()
                        if (g == null || g < 1000) {
                            Toast.makeText(ctx, "Type an amount first (min 1,000)", Toast.LENGTH_SHORT).show()
                        } else {
                            BudgetStore.set(ctx, g)
                            savedFlash = true
                            onSaved(g)
                            OverspendAlerter.check(ctx, monthSpend, g)
                            Toast.makeText(ctx, "Budget goal saved: ${"%,.0f".format(g)}", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Midnight)
                ) { Text("Save") }
            }
        }
    }
}

@Composable
fun QuoteFooter() {
    val q = remember { MoneyQuotes.ofDay() }
    Card(
        colors = CardDefaults.cardColors(containerColor = CardHi),
        shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("💬 Daily wisdom", color = Amber, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                "“${q.text}”",
                fontStyle = FontStyle.Italic, fontFamily = FontFamily.Serif,
                style = MaterialTheme.typography.bodyMedium, color = Ink
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "— ${q.author}, ${q.year}",
                fontStyle = FontStyle.Italic, fontFamily = FontFamily.Serif,
                style = MaterialTheme.typography.bodySmall, color = Amber
            )
        }
    }
}

// ---------- HOME DASHBOARD ----------
@Composable
fun DashboardHome(spent: Double, income: Double, net: Double, txs: List<EchoTransaction>,
                  loading: Boolean, granted: Boolean, onGrant: () -> Unit, onRescan: () -> Unit,
                  hideNoise: Boolean, onToggleNoise: () -> Unit,
                  rangeKey: String, onRange: (String) -> Unit,
                  sourceTab: String, onSource: (String) -> Unit, onExport: () -> Unit,
                  query: String = "", onQuery: (String) -> Unit = {},
                  onTxClick: (EchoTransaction) -> Unit = {},
                  onCategoryClick: (Category) -> Unit = {},
                  onPersonClick: (String) -> Unit = {},
                  onGoEchoes: () -> Unit = {}, onGoStats: () -> Unit = {},
                  onInfo: (String, String) -> Unit = { _, _ -> },
                  onWeeklyPdf: () -> Unit = {}, onMonthlyPdf: () -> Unit = {}) {
    val trend = remember(txs) { EchoStats.perDay(txs, 14) }
    val maxT = (trend.maxOfOrNull { it.second } ?: 1.0).coerceAtLeast(1.0)
    val byCat = remember(txs) { EchoStats.byCategory(txs).take(5) }
    val maxC = (byCat.firstOrNull()?.second ?: 1.0).coerceAtLeast(1.0)
    val top = remember(txs) { EchoStats.topRecipients(txs, 5) }
    val ctx2 = LocalContext.current
    val monthSpend = remember(txs) { EchoStats.monthSpent(EchoStats.visible(txs, true)) }
    val bills = remember(txs) { EchoStats.upcomingBills(EchoStats.visible(txs, false)) }
    val (greet, greetSub, greetEmoji) = remember { MoneyQuotes.greeting() }
    val haptic = LocalHapticFeedback.current
    fun tap(fn: () -> Unit) {
        try { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) } catch (_: Exception) { }
        fn()
    }
    val uncatCount = remember(txs) { txs.count { it.category == Category.UNCATEGORIZED } }

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            OutlinedTextField(
                value = query, onValueChange = onQuery,
                label = { Text("Search name, amount, category… e.g. naivas, >5000, 1k-5k") },
                supportingText = { Text("Try: kplc • 5000 • >5000 • <1000 • 1000-5000 • 5k") },
                modifier = Modifier.fillMaxWidth(), singleLine = true
            )
        }
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = CardBg),
                shape = RoundedCornerShape(20.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    Modifier.background(HeroBrush).padding(20.dp)
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "$greetEmoji  $greet",
                                color = Amber, style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic,
                                fontFamily = FontFamily.Serif
                            )
                            Text(greetSub, style = MaterialTheme.typography.bodySmall, color = Ink.copy(alpha = 0.8f))
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            TextButton(onClick = onExport) { Text("CSV", color = Mint) }
                            TextButton(onClick = onWeeklyPdf) { Text("Week PDF", color = Amber) }
                            TextButton(onClick = onMonthlyPdf) { Text("Month PDF", color = Sky) }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatPill("Spent", spent, Rose, Modifier.weight(1f))
                        StatPill("In", income, Mint, Modifier.weight(1f))
                        StatPill("Net", net, if (net >= 0) Mint else Amber, Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(if (net >= 0) "Net +${"%,.0f".format(net)} — saving" else "Net ${"%,.0f".format(net)} — overspent",
                        color = if (net >= 0) Mint else Rose,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable { onGoStats() })
                    if (uncatCount > 0) {
                        Spacer(Modifier.height(6.dp))
                        AssistChip(onClick = {
                            onQuery(""); onGoEchoes()
                            Toast.makeText(ctx2, "$uncatCount uncategorized — tap any → Teach Echo", Toast.LENGTH_LONG).show()
                        }, label = { Text("🏷️ $uncatCount need teaching — tap to fix") })
                    }
                    Spacer(Modifier.height(6.dp))
                    // savings rate + month compare — tappable chips with real dialogs
                    run {
                        val rate = EchoStats.savingsRate(spent, income)
                        val (thisM, lastM) = EchoStats.thisMonthVsLast(txs)
                        val delta = if (lastM > 0) (((thisM - lastM) / lastM * 100).toInt()) else 0
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            AssistChip(onClick = {
                                onInfo(
                                    "Savings rate: $rate%",
                                    "Income ${"%,.0f".format(income)} − spent ${"%,.0f".format(spent)} in this view. Tap View breakdown for the full split."
                                )
                                onGoStats()
                            }, label = { Text("Saved $rate%") })
                            AssistChip(onClick = {
                                onInfo(
                                    if (delta <= 0) "Spending down ${-delta}%" else "Spending up $delta%",
                                    "This month ${"%,.0f".format(thisM)} vs last ${"%,.0f".format(lastM)}. Tap View breakdown for categories."
                                )
                                onGoStats()
                            }, label = {
                                Text(if (delta <= 0) "↓ ${-delta}% vs last month" else "↑ $delta% vs last month")
                            })
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(EchoStats.dailyStory(txs), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(4.dp))
                    Text("💡 ${EchoStats.insight(txs)}",
                        style = MaterialTheme.typography.bodySmall, color = Amber,
                        modifier = Modifier.clickable { onGoStats() })
                    Spacer(Modifier.height(10.dp))
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("TODAY", "7D", "ALL", "1M", "3M", "6M").forEach { k ->
                            FilterChip(selected = rangeKey == k, onClick = { onRange(k) }, label = { Text(k) })
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("ALL", "MPESA", "BANK").forEach { k ->
                            FilterChip(selected = sourceTab == k, onClick = { onSource(k) }, label = { Text(k) })
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { onToggleNoise() }
                    ) {
                        Checkbox(checked = hideNoise, onCheckedChange = { onToggleNoise() })
                        Text("Hide fees, loans & reversals", style = MaterialTheme.typography.bodySmall)
                    }
                    if (loading) { Spacer(Modifier.height(6.dp)); LinearProgressIndicator(color = Mint); Text("Reading SMS…", style = MaterialTheme.typography.bodySmall) }
                    if (!granted) {
                        Spacer(Modifier.height(6.dp))
                        Button(onClick = onGrant, colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Midnight)) { Text("Allow SMS reading") }
                    } else {
                        TextButton(onClick = onRescan) { Text("↻ Rescan inbox", color = Mint) }
                    }
                }
            }
        }
        item {
            GoalCard(
                monthSpend = monthSpend,
                onSaved = { g ->
                    OverspendAlerter.check(ctx2, monthSpend, g)
                }
            )
        }
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = CardBg),
                modifier = Modifier.fillMaxWidth().clickable { tap { if (bills.isNotEmpty()) onPersonClick(bills.first().name) } }
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Bill reminders", color = Amber, fontWeight = FontWeight.Bold)
                        if (bills.isNotEmpty()) TextButton(onClick = onGoEchoes) { Text("View all", color = Amber) }
                    }
                    Spacer(Modifier.height(6.dp))
                    if (bills.isEmpty()) Text("No repeating bills detected yet. Pay KPLC / DSTV twice and I'll track them.",
                        style = MaterialTheme.typography.bodySmall)
                    bills.forEach { b ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { tap { onPersonClick(b.name) } },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(b.name, fontWeight = FontWeight.SemiBold)
                                Text("Avg ${"%,.0f".format(b.avgAmount)}", style = MaterialTheme.typography.bodySmall)
                            }
                            Text(if (b.overdue) "OVERDUE ${-b.daysLeft}d" else "due in ${b.daysLeft}d",
                                color = if (b.overdue) Rose else Amber, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = CardBg),
                modifier = Modifier.fillMaxWidth().clickable { tap { onGoEchoes() } }
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Spend trend — last 14 days", color = Mint, fontWeight = FontWeight.Bold)
                        TextButton(onClick = onGoEchoes) { Text("Open", color = Mint) }
                    }
                    Spacer(Modifier.height(8.dp))
                    Canvas(Modifier.fillMaxWidth().height(110.dp)) {
                        val n = trend.size; if (n == 0) return@Canvas
                        val bw = size.width / (n * 1.6f)
                        trend.forEachIndexed { i, (_, v) ->
                            val h = (v / maxT * (size.height - 20)).toFloat()
                            drawRect(Mint, topLeft = androidx.compose.ui.geometry.Offset(i * 1.6f * bw + bw * 0.3f, size.height - h),
                                size = Size(bw, h))
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(trend.firstOrNull()?.first ?: "", style = MaterialTheme.typography.labelSmall)
                        Text(trend.lastOrNull()?.first ?: "today", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = CardBg)) {
                Column(Modifier.padding(16.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Where it went", color = Mint, fontWeight = FontWeight.Bold)
                        TextButton(onClick = onGoStats) { Text("All", color = Mint) }
                    }
                    Spacer(Modifier.height(8.dp))
                    if (byCat.isEmpty()) Text("No spend in this view yet.")
                    byCat.forEach { (c, v) ->
                        Column(
                            Modifier.padding(vertical = 4.dp).fillMaxWidth().clickable { tap { onCategoryClick(c) } }
                        ) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(c.name.lowercase() + " ›"); Text("%,.0f".format(v), fontWeight = FontWeight.Bold)
                            }
                            LinearProgressIndicator(progress = { (v / maxC).toFloat() }, color = Mint, trackColor = Midnight, modifier = Modifier.fillMaxWidth().height(6.dp))
                        }
                    }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = CardBg)) {
                Column(Modifier.padding(16.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Top destinations", color = Mint, fontWeight = FontWeight.Bold)
                        TextButton(onClick = onGoEchoes) { Text("See all", color = Mint) }
                    }
                    Spacer(Modifier.height(6.dp))
                    if (top.isEmpty()) Text("Nothing yet.")
                    top.forEach { (w, v) -> Row(
                        Modifier.fillMaxWidth().padding(vertical = 3.dp).clickable { tap { onPersonClick(w) } },
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(w.take(28) + " ›", modifier = Modifier.weight(1f)); Text("%,.0f".format(v), fontWeight = FontWeight.Bold)
                    } }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Recent echoes", color = Mint, fontWeight = FontWeight.Bold)
                TextButton(onClick = onGoEchoes) { Text("See all", color = Mint) }
            }
        }
        items(txs.take(30)) { t ->
            Card(
                colors = CardDefaults.cardColors(containerColor = CardBg),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().clickable { onTxClick(t) }
            ) {
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(t.who.take(30), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f, fill = false))
                            if (t.category == Category.UNCATEGORIZED) {
                                AssistChip(onClick = { onTxClick(t) }, label = { Text("Teach 🏷️") })
                            }
                        }
                        Text("${t.category.name.lowercase()} • ${t.source.name} • ${java.text.DateFormat.getDateInstance().format(java.util.Date(t.dateMillis))}",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Text("${if (t.direction == Direction.OUT) "−" else "+"} ${"%,.0f".format(t.amount)}",
                        color = if (t.direction == Direction.OUT) Rose else Mint, fontWeight = FontWeight.Bold)
                }
            }
        }
        item {
            QuoteFooter()
        }
    }
}

// ---------- ECHOES grouped by day ----------
@Composable
fun EchoesGrouped(
    txs: List<EchoTransaction>, query: String, onQuery: (String) -> Unit,
    onTxClick: (EchoTransaction) -> Unit = {},
    onClearFilters: () -> Unit = {}
) {
    val groups = remember(txs) {
        txs.groupBy { EchoStats.startOfDay(it.dateMillis) }.toList().sortedByDescending { it.first }.take(60)
    }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            OutlinedTextField(value = query, onValueChange = onQuery, label = { Text("Search name, amount, category… e.g. >5000") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        }
        if (query.isNotBlank()) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("${txs.size} results for “$query”", style = MaterialTheme.typography.bodySmall, color = Mint)
                    TextButton(onClick = { onQuery("") }) { Text("Clear", color = Mint) }
                }
            }
        }
        if (txs.isEmpty()) item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("No echoes in this view. Try ALL + unhide noise.")
                Button(
                    onClick = onClearFilters,
                    colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Midnight)
                ) { Text("Reset filters") }
            }
        }
        groups.forEach { (day, list) ->
            item {
                val (s, inc) = EchoStats.totals(list)
                Card(
                    colors = CardDefaults.cardColors(containerColor = CardHi),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().clickable { onQuery("") }
                ) {
                    Column(
                        Modifier.background(
                            Brush.horizontalGradient(listOf(CardHi, Color(0xFF0F2B26)))
                        ).padding(horizontal = 14.dp, vertical = 12.dp)
                    ) {
                        Text(
                            java.text.DateFormat.getDateInstance().format(java.util.Date(day)),
                            color = Sky, style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold, fontStyle = FontStyle.Italic,
                            fontFamily = FontFamily.Serif
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "Spent ${"%,.0f".format(s)}  •  ${list.size} echoes" +
                                if (inc > 0) "  •  In ${"%,.0f".format(inc)}" else "",
                            color = Amber,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            fontStyle = FontStyle.Italic,
                            fontFamily = FontFamily.Serif
                        )
                    }
                }
            }
            items(list) { t ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = CardBg),
                    shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth().clickable { tap { onTxClick(t) } }
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                t.who.take(26),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                "${t.category.name.lowercase()}${if (t.category == Category.UNCATEGORIZED) " — tap to teach 🏷️" else ""}",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (t.category == Category.UNCATEGORIZED) Amber else Ink.copy(alpha = 0.7f),
                                fontStyle = if (t.category == Category.UNCATEGORIZED) FontStyle.Italic else FontStyle.Normal
                            )
                        }
                        Text("${if (t.direction == Direction.OUT) "−" else "+"} ${"%,.0f".format(t.amount)}",
                            color = if (t.direction == Direction.OUT) Rose else Mint,
                            style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        item {
            QuoteFooter()
        }
    }
}

// ---------- STATS ----------
@Composable
fun StatsDash(txs: List<EchoTransaction>, onCategoryClick: (Category) -> Unit = {}) {
    val byCat = remember(txs) { EchoStats.byCategory(txs) }
    val total = (byCat.sumOf { it.second }).coerceAtLeast(1.0)
    val palette = listOf(Mint, Amber, Rose, Color(0xFF7C9AFF), Color(0xFF9D7BFF), Color(0xFF5BD8C8))
    val ctx = LocalContext.current
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = CardBg),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Share by category — tap a row to drill in", color = Mint, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Canvas(Modifier.size(150.dp).clickable {
                        if (byCat.isNotEmpty()) onCategoryClick(byCat.first().first)
                    }) {
                        var start = -90f
                        byCat.forEachIndexed { i, (_, v) ->
                            val sweep = (v / total * 360).toFloat()
                            drawArc(palette[i % palette.size], start, sweep, useCenter = false, style = Stroke(28f))
                            start += sweep
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    byCat.forEachIndexed { i, (c, v) ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onCategoryClick(c) }.padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("● ${c.name.lowercase()} ›", color = palette[i % palette.size])
                            Text("${"%,.0f".format(v)} (${(v / total * 100).toInt()}%)", fontWeight = FontWeight.Bold)
                        }
                    }
                    if (byCat.isEmpty()) Text("No data in this view.")
                    else TextButton(onClick = {
                        Toast.makeText(ctx, "Total ${"%,.0f".format(total)} across ${byCat.size} categories", Toast.LENGTH_SHORT).show()
                    }) { Text("Show total", color = Mint) }
                }
            }
        }
        item {
            CategoryCapsCard(txs = txs, onCategoryClick = onCategoryClick)
        }
        item {
            QuoteFooter()
        }
    }
}

@Composable
fun CategoryCapsCard(txs: List<EchoTransaction>, onCategoryClick: (Category) -> Unit = {}) {
    val ctx = LocalContext.current
    val spentByCat = remember(txs) { EchoStats.byCategory(txs).toMap() }
    var refresh by remember { mutableStateOf(0) }
    Card(colors = CardDefaults.cardColors(containerColor = CardBg)) {
        Column(Modifier.padding(16.dp)) {
            Text("Category caps", color = Amber, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text("Tap a cap, type a monthly limit, Save. Over-cap turns red. Tap a name to see its echoes.",
                style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            // refresh marker to re-read prefs after save
            refresh.let {
                Category.values().filter { it != Category.UNCATEGORIZED }.forEach { c ->
                    val spent = spentByCat[c] ?: 0.0
                    var txt by remember(c, it) {
                        mutableStateOf(
                            CategoryBudgets.get(ctx, c).takeIf { it > 0 }?.toInt()?.toString() ?: ""
                        )
                    }
                    val cap = txt.toDoubleOrNull() ?: 0.0
                    val over = cap > 0 && spent > cap
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f).clickable { onCategoryClick(c) }) {
                            Text(c.name.lowercase() + " ›", fontWeight = FontWeight.SemiBold,
                                color = if (over) Rose else Ink)
                            Text("spent ${"%,.0f".format(spent)}" +
                                if (cap > 0) " / cap ${"%,.0f".format(cap)}" else " / no cap",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (over) Rose else Ink.copy(alpha = 0.7f))
                        }
                        OutlinedTextField(
                            value = txt,
                            onValueChange = { txt = it.filter { ch -> ch.isDigit() }.take(9) },
                            label = { Text("Cap") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                        Button(onClick = {
                            CategoryBudgets.set(ctx, c, txt.toDoubleOrNull() ?: 0.0)
                            refresh++
                            Toast.makeText(ctx, "${c.name.lowercase()} cap saved", Toast.LENGTH_SHORT).show()
                        }, colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Midnight)) {
                            Text("Save")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SourcesScreen(all: List<EchoTransaction>, tab: String, onTab: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var checkMsg by remember { mutableStateOf<String?>(null) }
    val versionName = remember {
        try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "1.3"
        } catch (_: Exception) { "1.3" }
    }
    val m = remember(all) { all.filter { it.source == Source.MPESA } }
    val b = remember(all) { all.filter { it.source == Source.BANK } }
    val (ms, mi) = EchoStats.totals(EchoStats.visible(m, true))
    val (bs, bi) = EchoStats.totals(EchoStats.visible(b, true))
    fun checkNow() {
        checking = true; checkMsg = null
        scope.launch(Dispatchers.IO) {
            val info = UpdateChecker.check(ctx)
            withContext(Dispatchers.Main) {
                checking = false
                if (info != null) {
                    checkMsg = "${info.tag} available — tap Download."
                    UpdateChecker.notify(ctx, info)
                    UpdateChecker.openUpdate(ctx, info.url)
                    UpdateChecker.dismiss(ctx, info.tag)
                } else {
                    checkMsg = "You're on the latest (v$versionName). Checked just now."
                    Toast.makeText(ctx, "No updates — v$versionName is latest", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("ALL", "MPESA", "BANK").forEach { t ->
                FilterChip(selected = tab == t, onClick = {
                    onTab(t)
                    Toast.makeText(ctx, "$t filter applied to Home + Echoes", Toast.LENGTH_SHORT).show()
                }, label = { Text(t) })
            }
        }
        Card(
            colors = CardDefaults.cardColors(containerColor = CardBg),
            modifier = Modifier.fillMaxWidth().clickable { onTab("MPESA") }
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("M-PESA  •  ${m.size} echoes", color = Mint, fontWeight = FontWeight.Bold)
                    Text(if (tab == "MPESA") "● active" else "Tap to filter ›", color = Mint, style = MaterialTheme.typography.bodySmall)
                }
                Text("Spent ${"%,.0f".format(ms)}  •  In ${"%,.0f".format(mi)}")
            }
        }
        Card(
            colors = CardDefaults.cardColors(containerColor = CardBg),
            modifier = Modifier.fillMaxWidth().clickable { onTab("BANK") }
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("BANKS  •  ${b.size} echoes", color = Amber, fontWeight = FontWeight.Bold)
                    Text(if (tab == "BANK") "● active" else "Tap to filter ›", color = Amber, style = MaterialTheme.typography.bodySmall)
                }
                Text("Spent ${"%,.0f".format(bs)}  •  In ${"%,.0f".format(bi)}")
            }
        }
        if (tab != "ALL") {
            Button(
                onClick = { onTab("ALL") },
                colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Midnight)
            ) { Text("Show ALL sources") }
        }
        Text("M-PESA is your main track. Banks stay separate so totals never mix. Switch the tab to filter Home + Echoes. Tap a card to filter instantly.",
            style = MaterialTheme.typography.bodySmall)
        Card(
            colors = CardDefaults.cardColors(containerColor = CardBg),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("App updates", color = Mint, fontWeight = FontWeight.Bold)
                    Text("v$versionName", style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    "Auto-checks GitHub Releases on launch. Push to main builds + publishes echo-tracks.apk, then the phone notifies.",
                    style = MaterialTheme.typography.bodySmall
                )
                if (checking) {
                    LinearProgressIndicator(color = Mint, modifier = Modifier.fillMaxWidth())
                    Text("Checking GitHub…", style = MaterialTheme.typography.bodySmall)
                }
                checkMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Amber) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { checkNow() }, enabled = !checking,
                        colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Midnight)
                    ) { Text(if (checking) "Checking…" else "Check for updates now") }
                    OutlinedButton(
                        onClick = {
                            UpdateChecker.openUpdate(
                                ctx,
                                "https://github.com/YNW-ECHO/echo-tracks/releases/latest"
                            )
                        }
                    ) { Text("Open Releases", color = Mint) }
                }
            }
        }
        QuoteFooter()
    }
}

// ---------- MENU / SETTINGS ----------
@Composable
fun SettingsScreen(onPrefsChanged: () -> Unit = {}, onLockNow: () -> Unit = {}) {
    val ctx = LocalContext.current
    val act = ctx as? FragmentActivity
    var theme by remember { mutableStateOf(AppPrefs.theme(ctx)) }
    var font by remember { mutableStateOf(AppPrefs.font(ctx)) }
    var scale by remember { mutableStateOf(AppPrefs.fontScale(ctx)) }
    var bioOn by remember { mutableStateOf(AppPrefs.bioEnabled(ctx)) }
    var timeout by remember { mutableStateOf(AppPrefs.lockTimeoutMin(ctx)) }
    var hasPin by remember { mutableStateOf(AppLock.hasPin(ctx)) }
    var newPin by remember { mutableStateOf("") }
    val canBio = remember { act != null && AppLock.canUseBiometric(ctx) }

    fun refresh() {
        theme = AppPrefs.theme(ctx); font = AppPrefs.font(ctx)
        scale = AppPrefs.fontScale(ctx); bioOn = AppPrefs.bioEnabled(ctx)
        timeout = AppPrefs.lockTimeoutMin(ctx); hasPin = AppLock.hasPin(ctx)
        onPrefsChanged()
    }

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(
                "☰ Menu", color = Amber,
                style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                fontStyle = FontStyle.Italic, fontFamily = FontFamily.Serif
            )
            Text("Fonts, sizes, modes, lock — all here.", style = MaterialTheme.typography.bodySmall)
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = CardBg), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("🎨 Appearance", color = Mint, fontWeight = FontWeight.Bold)
                    Text("Mode", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(AppPrefs.THEME_DARK to "Dark", AppPrefs.THEME_LIGHT to "Light", AppPrefs.THEME_SYSTEM to "Auto").forEach { (v, label) ->
                            FilterChip(selected = theme == v, onClick = {
                                AppPrefs.setTheme(ctx, v); refresh()
                                Toast.makeText(ctx, "$label mode on", Toast.LENGTH_SHORT).show()
                            }, label = { Text(label) })
                        }
                    }
                    Text("Font", style = MaterialTheme.typography.labelMedium)
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(AppPrefs.FONT_DEFAULT to "Default", AppPrefs.FONT_SERIF to "Serif", AppPrefs.FONT_MONO to "Mono").forEach { (v, label) ->
                            FilterChip(selected = font == v, onClick = {
                                AppPrefs.setFont(ctx, v); refresh()
                            }, label = { Text(label) })
                        }
                    }
                    Text("Text size: ${(scale * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = scale, onValueChange = { scale = it },
                        onValueChangeFinished = {
                            val snapped = ((scale * 20).toInt() / 20f).coerceIn(0.85f, 1.3f)
                            AppPrefs.setFontScale(ctx, snapped); refresh()
                        },
                        valueRange = 0.85f..1.3f, steps = 8
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0.85f to "S", 1.0f to "M", 1.15f to "L", 1.3f to "XL").forEach { (v, label) ->
                            FilterChip(selected = kotlin.math.abs(scale - v) < 0.02f, onClick = {
                                AppPrefs.setFontScale(ctx, v); refresh()
                            }, label = { Text(label) })
                        }
                    }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = CardBg), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("🔒 Lock & fingerprint", color = Amber, fontWeight = FontWeight.Bold)
                    Text(
                        if (hasPin) "PIN is ON — your money data is protected."
                        else "No PIN yet — anyone opening the app sees your money.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = newPin, onValueChange = { newPin = it.filter { c -> c.isDigit() }.take(8) },
                        label = { Text(if (hasPin) "New PIN (4+ digits)" else "Set PIN (4+ digits)") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                if (newPin.length >= 4) {
                                    AppLock.setPin(ctx, newPin); newPin = ""; refresh()
                                    Toast.makeText(ctx, "PIN saved", Toast.LENGTH_SHORT).show()
                                } else Toast.makeText(ctx, "PIN must be 4+ digits", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Midnight)
                        ) { Text(if (hasPin) "Change PIN" else "Enable PIN") }
                        if (hasPin) {
                            OutlinedButton(onClick = {
                                AppLock.clearPin(ctx)
                                refresh()
                                Toast.makeText(ctx, "PIN removed", Toast.LENGTH_SHORT).show()
                            }) { Text("Remove", color = Rose) }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable(
                        enabled = canBio
                    ) {
                        AppPrefs.setBioEnabled(ctx, !bioOn); refresh()
                    }) {
                        Checkbox(
                            checked = bioOn, enabled = canBio,
                            onCheckedChange = { AppPrefs.setBioEnabled(ctx, it); refresh() }
                        )
                        Text(
                            if (!canBio) "Fingerprint unavailable on this device"
                            else "Fingerprint unlock",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (canBio && bioOn && act != null) {
                        OutlinedButton(onClick = {
                            AppLock.promptBiometric(act) {
                                Toast.makeText(ctx, "Fingerprint works ✓", Toast.LENGTH_SHORT).show()
                            }
                        }) { Text("Test fingerprint", color = Mint) }
                    }
                    Text("Auto-lock after background (minutes)", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0 to "Now", 2 to "2m", 5 to "5m", 30 to "30m").forEach { (v, label) ->
                            FilterChip(selected = timeout == v, onClick = {
                                AppPrefs.setLockTimeout(ctx, v); refresh()
                            }, label = { Text(label) })
                        }
                    }
                    Button(
                        onClick = onLockNow,
                        colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Midnight)
                    ) { Text("🔒 Lock app now") }
                }
            }
        }
        item {
            val learned = remember { LearnStore.count(ctx) }
            Card(colors = CardDefaults.cardColors(containerColor = CardHi), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("💡 My recommendations for your app", color = Mint, fontWeight = FontWeight.Bold)
                    Text("• Backup: Export CSV weekly to Downloads (Home → Export CSV).", style = MaterialTheme.typography.bodySmall)
                    Text("• Teach $learned rules so far — tap any uncategorized → Teach Echo.", style = MaterialTheme.typography.bodySmall)
                    Text("• Set a monthly goal + 2–3 category caps to get red/amber warnings.", style = MaterialTheme.typography.bodySmall)
                    Text("• Next builds I suggest: budget alerts, PDF month report, search by amount.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item { QuoteFooter() }
    }
}
