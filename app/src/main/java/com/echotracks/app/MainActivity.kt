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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.navigation.compose.*
import com.echotracks.app.data.BillStore
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

val Mint = Color(0xFF00E5A0); val DeepMint = Color(0xFF00B87E); val Midnight = Color(0xFF0E1A2B)
val CardBg = Color(0xFF16263D); val CardHi = Color(0xFF1E3252); val Ink = Color(0xFFF2F7F5)
val Rose = Color(0xFFFF8A80); val Amber = Color(0xFFFFB020); val Sky = Color(0xFF7C9AFF)
val HeroBrush = Brush.horizontalGradient(listOf(Color(0xFF123B2F), Color(0xFF16263D)))

// ---------- THEME-AWARE SURFACES (light mode actually works) ----------
// Hardcoded dark colors broke light theme. These resolve per current mode.
val LocalEchoDark = compositionLocalOf { true }

@Composable fun echoCard(): Color = if (LocalEchoDark.current) CardBg else Color.White
@Composable fun echoCardHi(): Color = if (LocalEchoDark.current) CardHi else Color(0xFFE9F0EB)
@Composable fun echoDeep(): Color = if (LocalEchoDark.current) Midnight else Color(0xFFD9E5DD)
@Composable fun echoInk(): Color = if (LocalEchoDark.current) Ink else Color(0xFF0E1A2B)
@Composable fun heroBrush(): Brush = if (LocalEchoDark.current) HeroBrush
else Brush.horizontalGradient(listOf(Color(0xFFDFF5EA), Color.White))
@Composable fun goalBrush(): Brush = if (LocalEchoDark.current)
    Brush.verticalGradient(listOf(CardBg, Color(0xFF101F36)))
else Brush.verticalGradient(listOf(Color.White, Color(0xFFDFF0E5)))
@Composable fun dayBrush(): Brush = if (LocalEchoDark.current)
    Brush.horizontalGradient(listOf(CardHi, Color(0xFF0F2B26)))
else Brush.horizontalGradient(listOf(Color(0xFFE9F0EB), Color(0xFFD6EBDD)))

// Single shared display-title style (was copy-pasted serif-italic ~15 times).
@Composable fun echoTitleStyle(base: TextStyle = MaterialTheme.typography.titleMedium): TextStyle =
    base.copy(fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic, fontWeight = FontWeight.Bold)

@Composable fun EchoH(text: String, color: Color = Mint, style: TextStyle = MaterialTheme.typography.titleMedium) {
    Text(text, color = color, style = echoTitleStyle(style))
}

// Haptic tap helper shared by every screen (was local to DashboardHome only).
@Composable fun rememberTap(): (() -> Unit) -> Unit {
    val haptic = LocalHapticFeedback.current
    return { fn ->
        try { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) } catch (_: Exception) { }
        fn()
    }
}

// Tappable search hints + recent searches. Used by Home + Echoes.
@Composable fun SearchSuggestRow(
    onPick: (String) -> Unit,
    recents: List<String> = emptyList(),
    onClearRecents: () -> Unit = {}
) {
    val tap = rememberTap()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf("kplc", "naivas", ">5000", "<1000", "1k-5k", "1000-5000").forEach { s ->
                AssistChip(onClick = { tap { onPick(s) } }, label = { Text(s) })
            }
        }
        if (recents.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Recent:", style = MaterialTheme.typography.labelSmall, color = echoInk().copy(alpha = 0.6f))
                recents.forEach { r ->
                    AssistChip(onClick = { tap { onPick(r) } }, label = { Text(r) })
                }
                TextButton(onClick = onClearRecents) {
                    Text("Clear", color = Rose, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

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
        CompositionLocalProvider(LocalEchoDark provides dark) {
            EchoApp(
                onPrefsChanged = {
                    themeMode = AppPrefs.theme(ctx)
                    fontKey = AppPrefs.font(ctx)
                    fontScale = AppPrefs.fontScale(ctx)
                }
            )
        }
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
    var yearKey by remember { mutableStateOf("ALL") } // ALL or "2026"/"2025"… from data
    var query by remember { mutableStateOf("") }
    var dayKey by remember { mutableStateOf<Long?>(null) } // drill-in to one day (from chart tap)
    var monthKey by remember { mutableStateOf<Long?>(null) } // drill-in to one month (from 12-mo chart tap)
    var recents by remember { mutableStateOf(AppPrefs.recentQueries(ctx)) }
    var unlocked by remember { mutableStateOf(!AppLock.hasPin(ctx)) }
    var pinInput by remember { mutableStateOf("") }
    var pinSetup by remember { mutableStateOf("") }
    var update by remember { mutableStateOf<UpdateChecker.UpdateInfo?>(null) }
    var updateProgress by remember { mutableStateOf<Int?>(null) } // null = idle, else 0..100
    var updateErr by remember { mutableStateOf<String?>(null) }
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

    // remember meaningful searches (debounced) for tappable recent chips
    LaunchedEffect(query) {
        if (query.isBlank()) return@LaunchedEffect
        delay(900)
        AppPrefs.addRecentQuery(ctx, query)
        recents = AppPrefs.recentQueries(ctx)
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

    fun rescan() {
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
    }

    // Android 13+ needs POST_NOTIFICATIONS at runtime, otherwise update +
    // overspend alerts are silently dropped.
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { ok ->
        if (!ok) Toast.makeText(ctx, "Alerts off — enable notifications in Settings", Toast.LENGTH_SHORT).show()
    }
    LaunchedEffect(granted) {
        if (!granted) return@LaunchedEffect
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            val have = ContextCompat.checkSelfPermission(
                ctx, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!have) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
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
        val dl = updateProgress
        AlertDialog(
            onDismissRequest = {
                if (dl == null) { UpdateChecker.dismiss(ctx, info.tag); update = null }
            },
            title = { Text("Echo Tracks ${info.tag} available") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("A new version is live. Update right here in the app — no APK to fetch by hand.")
                    if (dl != null) {
                        LinearProgressIndicator(
                            progress = { dl / 100f },
                            color = Mint,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text("Downloading… $dl%", style = MaterialTheme.typography.bodySmall, color = Mint)
                    }
                    updateErr?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = Rose)
                    }
                    Text("Your data, PIN, budget and settings stay exactly as they are.",
                        style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { UpdateChecker.openUpdate(ctx, info.url) }) {
                        Text("Get it from GitHub instead", color = Sky)
                    }
                }
            },
            confirmButton = {
                if (dl == null) {
                    TextButton(onClick = {
                        updateErr = null
                        val staged = UpdateChecker.stagedApk(ctx)
                        if (staged != null) {
                            // already downloaded (e.g. user is granting "install unknown apps")
                            val started = UpdateChecker.install(ctx, staged)
                            if (started) {
                                UpdateChecker.dismiss(ctx, info.tag)
                                update = null
                                Toast.makeText(ctx, "Installer opened — tap Install to finish.",
                                    Toast.LENGTH_LONG).show()
                            } else {
                                updateErr = "Allow “install unknown apps” for Echo Tracks, then tap Update again."
                            }
                            return@TextButton
                        }
                        updateProgress = 0
                        scope.launch {
                            val file = UpdateChecker.download(ctx, info.url) { p ->
                                withContext(Dispatchers.Main) { updateProgress = p }
                            }
                            withContext(Dispatchers.Main) {
                                updateProgress = null
                                if (file == null) {
                                    updateErr = "Download failed — check your internet, or tap “Get it from GitHub instead”."
                                } else {
                                    val started = UpdateChecker.install(ctx, file)
                                    if (started) {
                                        UpdateChecker.dismiss(ctx, info.tag)
                                        update = null
                                        Toast.makeText(ctx, "Installer opened — tap Install to finish.",
                                            Toast.LENGTH_LONG).show()
                                    } else {
                                        updateErr = "Allow “install unknown apps” for Echo Tracks, then tap Update again."
                                    }
                                }
                            }
                        }
                    }) { Text("Update now") }
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    if (dl == null) {
                        UpdateChecker.dismiss(ctx, info.tag)
                        update = null
                    }
                }) { Text(if (dl == null) "Later" else "Downloading…") }
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
                        AssistChip(
                            onClick = {
                                query = t.category.name.lowercase(); dayKey = null; monthKey = null; selectedTx = null
                                nav.navigate("Echoes") { launchSingleTop = true }
                            },
                            label = { Text(t.category.name.lowercase() + " ›") }
                        )
                        AssistChip(
                            onClick = {
                                query = ""; dayKey = null; monthKey = null; sourceTab = t.source.name; selectedTx = null
                                nav.navigate("Echoes") { launchSingleTop = true }
                            },
                            label = { Text(t.source.name + " ›") }
                        )
                        AssistChip(
                            onClick = {
                                selectedTx = null
                                infoTitle = spendTypeTitle(t.spendType)
                                infoBody = spendTypeBody(t.spendType)
                            },
                            label = { Text(t.spendType.name + " ›") }
                        )
                    }
                    Text(java.text.DateFormat.getDateTimeInstance().format(java.util.Date(t.dateMillis)))
                    Text("Code: ${t.code}", style = MaterialTheme.typography.bodySmall)
                    if (t.balanceAfter != null) Text(
                        "Balance after: ${"%,.0f".format(t.balanceAfter)}",
                        style = MaterialTheme.typography.bodySmall, color = Sky
                    )
                    if (t.rawSms.isNotBlank()) Card(colors = CardDefaults.cardColors(containerColor = echoDeep())) {
                        Text(
                            t.rawSms.take(500),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                    // Teach / re-teach: available on EVERY transaction, not just uncategorized ones
                    if (!teachOpen) {
                        Button(
                            onClick = { teachOpen = true },
                            colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Midnight)
                        ) {
                            Text(if (t.category == Category.UNCATEGORIZED) "🏷️ Teach Echo — classify this"
                            else "🏷️ Re-teach Echo — fix category")
                        }
                    }
                    if (teachOpen) {
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
                                    // regroup ALL similar in memory instantly (incl. already-categorized ones)
                                    val key = teachKey.trim().lowercase()
                                    txs = txs.map { x ->
                                        if ("${x.who} ${x.rawSms}".lowercase().contains(key))
                                            x.copy(category = teachCat) else x
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

    // Day drill-in wins, then month drill-in, then history year, then quick ranges (mutually exclusive in UI).
    val yearBounds = remember(yearKey) {
        yearKey.toIntOrNull()?.let { EchoStats.yearBounds(it) }
    }
    val monthBounds = remember(monthKey) {
        monthKey?.let { EchoStats.monthBounds(it) }
    }
    val from = when {
        dayKey != null -> dayKey
        monthBounds != null -> monthBounds.first
        yearBounds != null -> yearBounds.first
        else -> when (rangeKey) {
            "TODAY" -> EchoStats.startOfDay(System.currentTimeMillis())
            "7D" -> EchoStats.daysAgo(7)
            "1M" -> EchoStats.monthsAgo(1); "3M" -> EchoStats.monthsAgo(3); "6M" -> EchoStats.monthsAgo(6); else -> null
        }
    }
    val to = when {
        dayKey != null -> dayKey!! + 24L * 60 * 60 * 1000 - 1
        monthBounds != null -> monthBounds.second
        else -> yearBounds?.second
    }
    val years = remember(txs) { EchoStats.yearsOf(txs) }
    val filtered = remember(txs, from, to, hideNoise, sourceTab, query) {
        var list = EchoStats.inRange(txs, from, to)
        if (sourceTab != "ALL") list = list.filter { it.source.name == sourceTab }
        list = EchoStats.visible(list, hideNoise)
        EchoStats.search(list, query)
    }
    val (spent, income) = EchoStats.totals(filtered)
    val net = income - spent

    fun filterToEchoes(q: String) {
        query = q
        dayKey = null
        monthKey = null
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
            NavigationBar(containerColor = echoCard()) {
                val tabs = listOf(
                    "Home" to Icons.Filled.Home,
                    "Echoes" to Icons.AutoMirrored.Filled.ReceiptLong,
                    "Stats" to Icons.Filled.PieChart,
                    "Sources" to Icons.Filled.AccountBalance,
                    "Menu" to Icons.Filled.Menu
                )
                tabs.forEach { (s, icon) ->
                    NavigationBarItem(
                        selected = currentRoute == s,
                        onClick = { nav.navigate(s) { launchSingleTop = true } },
                        label = { Text(s) },
                        icon = {
                            Icon(
                                imageVector = icon, contentDescription = s,
                                tint = if (currentRoute == s) Mint else echoInk().copy(alpha = 0.7f)
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
                        allTxs = txs,
                        loading = loading, granted = granted,
                        onGrant = { launcher.launch(Manifest.permission.READ_SMS) },
                        onRescan = { rescan() },
                        hideNoise = hideNoise, onToggleNoise = { hideNoise = !hideNoise },
                        rangeKey = rangeKey, onRange = { rangeKey = it; yearKey = "ALL"; dayKey = null; monthKey = null },
                        sourceTab = sourceTab, onSource = { sourceTab = it; dayKey = null; monthKey = null },
                        years = years, yearKey = yearKey,
                        onYear = { yearKey = it; if (it != "ALL") rangeKey = "ALL"; dayKey = null; monthKey = null },
                        dayKey = dayKey,
                        onDayClick = { start ->
                            dayKey = start; monthKey = null; rangeKey = "ALL"; yearKey = "ALL"
                            nav.navigate("Echoes") { launchSingleTop = true }
                        },
                        onClearDay = { dayKey = null },
                        monthKey = monthKey,
                        onMonthClick = { start ->
                            monthKey = start; dayKey = null; rangeKey = "ALL"; yearKey = "ALL"
                            nav.navigate("Echoes") { launchSingleTop = true }
                        },
                        onClearMonth = { monthKey = null },
                        recents = recents,
                        onClearRecents = {
                            AppPrefs.clearRecentQueries(ctx)
                            recents = emptyList()
                        },
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
                        loading = loading, onRefresh = { rescan() },
                        recents = recents,
                        onClearRecents = {
                            AppPrefs.clearRecentQueries(ctx)
                            recents = emptyList()
                        },
                        onTxClick = { selectedTx = it },
                        onClearFilters = { query = ""; rangeKey = "ALL"; sourceTab = "ALL"; yearKey = "ALL"; dayKey = null; monthKey = null; hideNoise = false },
                        dayKey = dayKey, monthKey = monthKey,
                        onClearDay = { dayKey = null }, onClearMonth = { monthKey = null }
                    )
                }
                composable("Stats") {
                    StatsDash(
                        txs = filtered, allTxs = txs,
                        onCategoryClick = { c -> filterToEchoes(c.name.lowercase()) },
                        onMonthClick = { start ->
                            monthKey = start; dayKey = null; rangeKey = "ALL"; yearKey = "ALL"
                            nav.navigate("Echoes") { launchSingleTop = true }
                        }
                    )
                }
                composable("Sources") {
                    SourcesScreen(
                        all = txs, tab = sourceTab, onTab = { sourceTab = it },
                        onUpdateFound = { info -> update = info; updateErr = null; updateProgress = null }
                    )
                }
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

fun spendTypeTitle(s: SpendType): String = when (s) {
    SpendType.REAL_SPEND -> "Real spend"
    SpendType.INCOME -> "Income"
    SpendType.SELF_TRANSFER -> "Self transfer (hidden by default)"
    SpendType.FEE -> "Fee / charge (hidden by default)"
    SpendType.LOAN -> "Loan / Fuliza (hidden by default)"
    SpendType.UNKNOWN -> "Reversal / refund (hidden by default)"
}

fun spendTypeBody(s: SpendType): String = when (s) {
    SpendType.REAL_SPEND -> "Money that actually left your pocket — counts in totals, charts and budgets."
    SpendType.INCOME -> "Money received — counts as income in totals and the trend chart."
    SpendType.SELF_TRANSFER -> "You moved your own money (e.g. M-PESA to bank). Untick “Hide fees, loans & reversals” on Home to see these."
    SpendType.FEE -> "Transaction costs and charges. Excluded from spend totals so they don't inflate your budget."
    SpendType.LOAN -> "Fuliza / M-Shwari / loan activity. Kept out of spend totals — repayments show as their own echoes."
    SpendType.UNKNOWN -> "Reversals and refunds. Hidden from totals so returned money isn't double-counted."
}

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
        colors = CardDefaults.cardColors(containerColor = echoDeep().copy(alpha = 0.65f)),
        shape = RoundedCornerShape(14.dp), modifier = modifier
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = echoInk().copy(alpha = 0.7f))
            Text(
                "%,.0f".format(value),
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = color
            )
        }
    }
}

@Composable
fun LegendDot(color: Color, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(10.dp)) { drawCircle(color) }
        Text(text, style = MaterialTheme.typography.labelSmall)
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
fun GoalCard(monthSpend: Double, onSaved: (Double) -> Unit = {},
             onDetails: (String, String) -> Unit = { _, _ -> }) {
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
        colors = CardDefaults.cardColors(containerColor = echoCard()),
        shape = RoundedCornerShape(18.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            Modifier.background(goalBrush()).padding(18.dp)
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "🎯 Monthly budget goal", color = Mint, fontWeight = FontWeight.Bold,
                    fontStyle = FontStyle.Italic, fontFamily = FontFamily.Serif,
                    style = MaterialTheme.typography.titleMedium
                )
                TextButton(onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    val left = goal - monthSpend
                    val days = EchoStats.daysLeftInMonth()
                    onDetails(
                        "Monthly goal: ${"%,.0f".format(goal)}",
                        "Spent ${"%,.0f".format(monthSpend)} (${(pct * 100).toInt()}% used), " +
                            (if (left >= 0) "${"%,.0f".format(left)} left" else "${"%,.0f".format(-left)} over") +
                            " with $days day(s) left in the month." +
                            if (days > 0 && left > 0) " That's ~${"%,.0f".format(left / days)} per day to stay on track." else ""
                    )
                }) { Text(if (savedFlash) "✓ Saved!" else "Details", color = Mint) }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Spent ${"%,.0f".format(monthSpend)} of ${"%,.0f".format(goal)}",
                style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = barColor
            )
            Text(
                if (left >= 0) "${(pct * 100).toInt()}% used • ${"%,.0f".format(left)} left"
                else "${(pct * 100).toInt()}% used • ${"%,.0f".format(-left)} over",
                style = MaterialTheme.typography.bodySmall, color = echoInk().copy(alpha = 0.75f)
            )
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { animPct },
                color = barColor, trackColor = echoDeep(),
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
        colors = CardDefaults.cardColors(containerColor = echoCardHi()),
        shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("💬 Daily wisdom", color = Amber, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                "“${q.text}”",
                fontStyle = FontStyle.Italic, fontFamily = FontFamily.Serif,
                style = MaterialTheme.typography.bodyMedium, color = echoInk()
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
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DashboardHome(spent: Double, income: Double, net: Double, txs: List<EchoTransaction>,
                  loading: Boolean, granted: Boolean, onGrant: () -> Unit, onRescan: () -> Unit,
                  hideNoise: Boolean, onToggleNoise: () -> Unit,
                  rangeKey: String, onRange: (String) -> Unit,
                  sourceTab: String, onSource: (String) -> Unit, onExport: () -> Unit,
                  years: List<Int> = emptyList(), yearKey: String = "ALL", onYear: (String) -> Unit = {},
                  query: String = "", onQuery: (String) -> Unit = {},
                  dayKey: Long? = null, onDayClick: (Long) -> Unit = {}, onClearDay: () -> Unit = {},
                  monthKey: Long? = null, onMonthClick: (Long) -> Unit = {}, onClearMonth: () -> Unit = {},
                  allTxs: List<EchoTransaction> = txs,
                  recents: List<String> = emptyList(), onClearRecents: () -> Unit = {},
                  onTxClick: (EchoTransaction) -> Unit = {},
                  onCategoryClick: (Category) -> Unit = {},
                  onPersonClick: (String) -> Unit = {},
                  onGoEchoes: () -> Unit = {}, onGoStats: () -> Unit = {},
                  onInfo: (String, String) -> Unit = { _, _ -> },
                  onWeeklyPdf: () -> Unit = {}, onMonthlyPdf: () -> Unit = {}) {
    val spendBars = remember(txs) { EchoStats.perDay(txs, 14, Direction.OUT) }
    val inBars = remember(txs) { EchoStats.perDay(txs, 14, Direction.IN) }
    val maxT = ((spendBars + inBars).maxOfOrNull { it.amount } ?: 1.0).coerceAtLeast(1.0)
    val peak = spendBars.maxByOrNull { it.amount }
    val byCat = remember(txs) { EchoStats.byCategory(txs).take(5) }
    val maxC = (byCat.firstOrNull()?.second ?: 1.0).coerceAtLeast(1.0)
    val top = remember(txs) { EchoStats.topRecipients(txs, 5) }
    val ctx2 = LocalContext.current
    // Global-scope stats must ignore view filters (day/month/year/range) so the
    // budget goal, bills and 12-month history stay correct while drilling in.
    val monthSpend = remember(allTxs) { EchoStats.monthSpent(EchoStats.visible(allTxs, true)) }
    var billsTick by remember { mutableStateOf(0) }
    val bills = remember(allTxs, billsTick) {
        EchoStats.upcomingBills(EchoStats.visible(allTxs, false), BillStore.all(ctx2))
    }
    val yearMonths = remember(allTxs) { EchoStats.perMonth(EchoStats.visible(allTxs, true), 12) }
    val (greet, greetSub, greetEmoji) = remember { MoneyQuotes.greeting() }
    val tap = rememberTap()
    val uncatCount = remember(allTxs) { allTxs.count { it.category == Category.UNCATEGORIZED } }
    val activeFilters = (if (rangeKey != "ALL") 1 else 0) + (if (sourceTab != "ALL") 1 else 0) +
        (if (yearKey != "ALL") 1 else 0) + (if (monthKey != null) 1 else 0) +
        (if (dayKey != null) 1 else 0) + (if (!hideNoise) 1 else 0) + (if (query.isNotBlank()) 1 else 0)
    var showFilters by remember { mutableStateOf(activeFilters > 0) }

    PullToRefreshBox(
        isRefreshing = loading, onRefresh = onRescan, modifier = Modifier.fillMaxSize()
    ) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            stickyHeader {
                Column(
                    Modifier.background(MaterialTheme.colorScheme.background).padding(bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = query, onValueChange = onQuery,
                        label = { Text("Search name, amount, category… e.g. naivas, >5000, 1k-5k") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true
                    )
                    SearchSuggestRow(onPick = onQuery, recents = recents, onClearRecents = onClearRecents)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatPill("Spent", spent, Rose, Modifier.weight(1f))
                        StatPill("In", income, Mint, Modifier.weight(1f))
                        StatPill("Net", net, if (net >= 0) Mint else Amber, Modifier.weight(1f))
                    }
                    if (dayKey != null) {
                        AssistChip(onClick = onClearDay, label = {
                            Text("📅 ${java.text.DateFormat.getDateInstance().format(java.util.Date(dayKey))} — tap ✕ to clear")
                        })
                    }
                    if (monthKey != null) {
                        AssistChip(onClick = onClearMonth, label = {
                            Text("🗓 ${java.text.SimpleDateFormat("MMM yyyy", java.util.Locale.US).format(java.util.Date(monthKey))} — tap ✕ to clear")
                        })
                    }
                }
            }
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = echoCard()),
                shape = RoundedCornerShape(20.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    Modifier.background(heroBrush()).padding(20.dp)
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "$greetEmoji  $greet",
                                color = Amber, style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic,
                                fontFamily = FontFamily.Serif
                            )
                            Text(greetSub, style = MaterialTheme.typography.bodySmall, color = echoInk().copy(alpha = 0.8f))
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            TextButton(onClick = onExport) { Text("CSV", color = Mint) }
                            TextButton(onClick = onWeeklyPdf) { Text("Week PDF", color = Amber) }
                            TextButton(onClick = onMonthlyPdf) { Text("Month PDF", color = Sky) }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
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
                            AssistChip(onClick = { tap { onGoStats() } },
                                label = { Text("Saved $rate%") })
                            AssistChip(onClick = { tap { onGoStats() } }, label = {
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
                        Modifier.fillMaxWidth().clickable { showFilters = !showFilters },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (activeFilters > 0) "🎛 Filters • $activeFilters on" else "🎛 Filters",
                            color = Mint, fontWeight = FontWeight.Bold
                        )
                        Text(if (showFilters) "▾" else "▸", color = Mint, fontWeight = FontWeight.Bold)
                    }
                    if (showFilters) {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("TODAY", "7D", "ALL", "1M", "3M", "6M").forEach { k ->
                            FilterChip(selected = rangeKey == k, onClick = { tap { onRange(k) } }, label = { Text(k) })
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("ALL", "MPESA", "BANK").forEach { k ->
                            FilterChip(selected = sourceTab == k, onClick = { tap { onSource(k) } }, label = { Text(k) })
                        }
                    }
                    if (years.size > 1) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "📚 History",
                            style = MaterialTheme.typography.labelMedium, color = Sky,
                            fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic,
                            fontFamily = FontFamily.Serif
                        )
                        Spacer(Modifier.height(4.dp))
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(selected = yearKey == "ALL", onClick = { tap { onYear("ALL") } }, label = { Text("All years") })
                            years.forEach { y ->
                                FilterChip(
                                    selected = yearKey == y.toString(),
                                    onClick = { tap { onYear(y.toString()) } },
                                    label = { Text(y.toString()) }
                                )
                            }
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { onToggleNoise() }
                    ) {
                        Checkbox(checked = hideNoise, onCheckedChange = { onToggleNoise() })
                        Text("Hide fees, loans & reversals", style = MaterialTheme.typography.bodySmall)
                    }
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
                onDetails = onInfo,
                onSaved = { g ->
                    OverspendAlerter.check(ctx2, monthSpend, g)
                }
            )
        }
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = echoCard()),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        EchoH("Bill reminders", Amber)
                        if (bills.isNotEmpty()) TextButton(onClick = onGoEchoes) { Text("View all", color = Amber) }
                    }
                    Spacer(Modifier.height(6.dp))
                    if (bills.isEmpty()) Text("No repeating bills detected yet. Pay KPLC / DSTV / rent twice and I'll track them.",
                        style = MaterialTheme.typography.bodySmall)
                    bills.forEach { b ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(
                                Modifier.weight(1f).clickable { tap { onPersonClick(b.name) } }
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(b.name, fontWeight = FontWeight.SemiBold)
                                    if (b.userMarked) Text("✓ paid", style = MaterialTheme.typography.labelSmall, color = Mint)
                                }
                                Text(
                                    if (b.avgAmount > 0) "Avg ${"%,.0f".format(b.avgAmount)}" else "Confirmed by you",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(if (b.overdue) "OVERDUE ${-b.daysLeft}d" else "due in ${b.daysLeft}d",
                                    color = if (b.overdue) Rose else Amber, fontWeight = FontWeight.Bold)
                                if (b.userMarked) {
                                    TextButton(onClick = {
                                        tap {
                                            BillStore.clearPaid(ctx2, b.key.ifBlank { b.name })
                                            billsTick++
                                            Toast.makeText(ctx2, "${b.name} paid mark undone", Toast.LENGTH_SHORT).show()
                                        }
                                    }) { Text("Undo", color = Rose, style = MaterialTheme.typography.labelMedium) }
                                } else {
                                    TextButton(onClick = {
                                        tap {
                                            BillStore.setPaid(ctx2, b.key.ifBlank { b.name })
                                            billsTick++
                                            Toast.makeText(ctx2, "${b.name} marked paid — due date reset", Toast.LENGTH_SHORT).show()
                                        }
                                    }) { Text("✓ Paid", color = Mint, style = MaterialTheme.typography.labelMedium) }
                                }
                            }
                        }
                    }
                }
            }
        }
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = echoCard()),
                modifier = Modifier.fillMaxWidth().clickable { tap { onGoEchoes() } }
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        EchoH("Spend trend — last 14 days")
                        TextButton(onClick = onGoEchoes) { Text("Open", color = Mint) }
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        LegendDot(Mint, "Spent")
                        LegendDot(Sky, "In")
                        Text("Tap a day to open it", style = MaterialTheme.typography.labelSmall,
                            color = echoInk().copy(alpha = 0.6f))
                    }
                    Spacer(Modifier.height(4.dp))
                    Canvas(
                        Modifier.fillMaxWidth().height(120.dp).pointerInput(spendBars, inBars, dayKey) {
                            detectTapGestures { off ->
                                val n = spendBars.size; if (n == 0) return@detectTapGestures
                                val slot = size.width / (n * 1.6f)
                                val i = (off.x / (1.6f * slot)).toInt()
                                if (i in spendBars.indices) tap { onDayClick(spendBars[i].dayStart) }
                            }
                        }
                    ) {
                        val n = spendBars.size; if (n == 0) return@Canvas
                        val slot = size.width / (n * 1.6f)
                        val bw = slot * 0.55f
                        spendBars.forEachIndexed { i, b ->
                            val x = i * 1.6f * slot
                            val hs = (b.amount / maxT * (size.height - 20)).toFloat()
                            val hi = ((inBars.getOrNull(i)?.amount ?: 0.0) / maxT * (size.height - 20)).toFloat()
                            drawRect(
                                if (dayKey == b.dayStart) Amber else Mint,
                                topLeft = androidx.compose.ui.geometry.Offset(x + slot * 0.1f, size.height - hs),
                                size = Size(bw, hs)
                            )
                            if (hi > 1f) drawRect(
                                Sky,
                                topLeft = androidx.compose.ui.geometry.Offset(x + slot * 0.85f, size.height - hi),
                                size = Size(bw, hi)
                            )
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(spendBars.firstOrNull()?.label ?: "", style = MaterialTheme.typography.labelSmall)
                        if (peak != null && peak.amount > 0) Text(
                            "Peak ${peak.label}: ${"%,.0f".format(peak.amount)}",
                            style = MaterialTheme.typography.labelSmall, color = Amber
                        )
                        Text(spendBars.lastOrNull()?.label ?: "today", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        item {
            val maxM = (yearMonths.maxOfOrNull { it.amount } ?: 1.0).coerceAtLeast(1.0)
            Card(colors = CardDefaults.cardColors(containerColor = echoCard())) {
                Column(Modifier.padding(16.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        EchoH("Last 12 months — tap a month")
                        TextButton(onClick = onGoStats) { Text("Stats", color = Mint) }
                    }
                    Spacer(Modifier.height(4.dp))
                    Canvas(
                        Modifier.fillMaxWidth().height(90.dp).pointerInput(yearMonths, monthKey) {
                            detectTapGestures { off ->
                                val n = yearMonths.size; if (n == 0) return@detectTapGestures
                                val bw = size.width / (n * 1.6f)
                                val i = (off.x / (1.6f * bw)).toInt()
                                if (i in yearMonths.indices) tap { onMonthClick(yearMonths[i].monthStart) }
                            }
                        }
                    ) {
                        val n = yearMonths.size; if (n == 0) return@Canvas
                        val bw = size.width / (n * 1.6f)
                        yearMonths.forEachIndexed { i, m ->
                            val h = (m.amount / maxM * (size.height - 20)).toFloat()
                            drawRect(
                                if (monthKey == m.monthStart) Amber else if (i == n - 1) Amber.copy(alpha = 0.85f) else Mint,
                                topLeft = androidx.compose.ui.geometry.Offset(i * 1.6f * bw + bw * 0.3f, size.height - h),
                                size = Size(bw, h)
                            )
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(yearMonths.firstOrNull()?.label ?: "", style = MaterialTheme.typography.labelSmall)
                        Text("%,.0f total".format(yearMonths.sumOf { it.amount }), style = MaterialTheme.typography.labelSmall, color = Amber)
                        Text(yearMonths.lastOrNull()?.label ?: "", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = echoCard())) {
                Column(Modifier.padding(16.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        EchoH("Where it went")
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
                            LinearProgressIndicator(progress = { (v / maxC).toFloat() }, color = Mint, trackColor = echoDeep(), modifier = Modifier.fillMaxWidth().height(6.dp))
                        }
                    }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = echoCard())) {
                Column(Modifier.padding(16.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        EchoH("Top destinations")
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
                EchoH("Recent echoes")
                TextButton(onClick = onGoEchoes) { Text("See all", color = Mint) }
            }
        }
        items(txs.take(30)) { t ->
            Card(
                colors = CardDefaults.cardColors(containerColor = echoCard()),
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
}

// ---------- ECHOES grouped by day ----------
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun EchoesGrouped(
    txs: List<EchoTransaction>, query: String, onQuery: (String) -> Unit,
    loading: Boolean = false, onRefresh: () -> Unit = {},
    recents: List<String> = emptyList(), onClearRecents: () -> Unit = {},
    onTxClick: (EchoTransaction) -> Unit = {},
    onClearFilters: () -> Unit = {},
    dayKey: Long? = null, monthKey: Long? = null,
    onClearDay: () -> Unit = {}, onClearMonth: () -> Unit = {}
) {
    val tap = rememberTap()
    val groups = remember(txs) {
        txs.groupBy { EchoStats.startOfDay(it.dateMillis) }.toList().sortedByDescending { it.first }.take(60)
    }
    PullToRefreshBox(
        isRefreshing = loading, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()
    ) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            stickyHeader {
                Column(
                    Modifier.background(MaterialTheme.colorScheme.background).padding(bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(value = query, onValueChange = onQuery, label = { Text("Search name, amount, category… e.g. >5000") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    SearchSuggestRow(onPick = onQuery, recents = recents, onClearRecents = onClearRecents)
                    if (dayKey != null) {
                        AssistChip(onClick = onClearDay, label = {
                            Text("📅 ${java.text.DateFormat.getDateInstance().format(java.util.Date(dayKey))} — ✕")
                        })
                    }
                    if (monthKey != null) {
                        AssistChip(onClick = onClearMonth, label = {
                            Text("🗓 ${java.text.SimpleDateFormat("MMM yyyy", java.util.Locale.US).format(java.util.Date(monthKey))} — ✕")
                        })
                    }
                }
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
                    colors = CardDefaults.cardColors(containerColor = echoCardHi()),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().clickable { onQuery("") }
                ) {
                    Column(
                        Modifier.background(
                            dayBrush()
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
                    colors = CardDefaults.cardColors(containerColor = echoCard()),
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
                                color = if (t.category == Category.UNCATEGORIZED) Amber else echoInk().copy(alpha = 0.7f),
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
}

// ---------- STATS ----------
@Composable
fun StatsDash(txs: List<EchoTransaction>, onCategoryClick: (Category) -> Unit = {},
              allTxs: List<EchoTransaction> = txs, onMonthClick: (Long) -> Unit = {}) {
    val byCat = remember(txs) { EchoStats.byCategory(txs) }
    val total = (byCat.sumOf { it.second }).coerceAtLeast(1.0)
    val palette = listOf(Mint, Amber, Rose, Sky, Color(0xFF9D7BFF), Color(0xFF5BD8C8))
    val ctx = LocalContext.current
    val tap = rememberTap()
    val (spent, income) = remember(txs) { EchoStats.totals(txs) }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = echoCard()),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    EchoH("Share by category — tap a slice to drill in")
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.size(190.dp), contentAlignment = Alignment.Center) {
                        Canvas(
                            Modifier.size(190.dp).pointerInput(byCat) {
                                detectTapGestures { off ->
                                    if (byCat.isEmpty()) return@detectTapGestures
                                    val cx = size.width / 2f; val cy = size.height / 2f
                                    val dx = off.x - cx; val dy = off.y - cy
                                    val inner = 60.dp.toPx()
                                    if (dx * dx + dy * dy < inner * inner) return@detectTapGestures
                                    val ang = (Math.toDegrees(
                                        kotlin.math.atan2(dy.toDouble(), dx.toDouble())
                                    ) + 360) % 360
                                    val rel = (ang + 90) % 360
                                    var start = 0.0
                                    var hit: Category? = null
                                    for ((c, v) in byCat) {
                                        val sweep = v / total * 360
                                        if (rel >= start && rel < start + sweep) { hit = c; break }
                                        start += sweep
                                    }
                                    if (hit != null) tap { onCategoryClick(hit) }
                                }
                            }
                        ) {
                            var start = -90f
                            byCat.forEachIndexed { i, (_, v) ->
                                val sweep = (v / total * 360).toFloat()
                                drawArc(palette[i % palette.size], start, sweep, useCenter = false, style = Stroke(30f))
                                start += sweep
                            }
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "%,.0f".format(total),
                                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold
                            )
                            Text("total spent", style = MaterialTheme.typography.labelSmall,
                                color = echoInk().copy(alpha = 0.6f))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    byCat.forEachIndexed { i, (c, v) ->
                        Row(
                            Modifier.fillMaxWidth().clickable { tap { onCategoryClick(c) } }.padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("● ${c.name.lowercase()} ›", color = palette[i % palette.size])
                            Text("${"%,.0f".format(v)} (${(v / total * 100).toInt()}%)", fontWeight = FontWeight.Bold)
                        }
                    }
                    if (byCat.isEmpty()) Text("No data in this view.")
                    else TextButton(onClick = {
                        tap {
                            Toast.makeText(ctx, "Total ${"%,.0f".format(total)} across ${byCat.size} categories", Toast.LENGTH_SHORT).show()
                        }
                    }) { Text("Show total", color = Mint) }
                }
            }
        }
        item {
            SavingsGauge(spent = spent, income = income)
        }
        item {
            MonthCompareCard(txs = allTxs, onCategoryClick = onCategoryClick)
        }
        item {
            YearChart(txs = allTxs, onMonthClick = onMonthClick)
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
fun SavingsGauge(spent: Double, income: Double) {
    val rate = EchoStats.savingsRate(spent, income)
    val anim by animateFloatAsState(
        targetValue = rate.coerceIn(0, 100) / 100f, label = "gauge"
    )
    val color = when {
        rate >= 20 -> Mint
        rate >= 0 -> Amber
        else -> Rose
    }
    val track = echoDeep()
    Card(colors = CardDefaults.cardColors(containerColor = echoCard())) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(92.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(92.dp)) {
                    drawArc(track, -90f, 360f, useCenter = false, style = Stroke(14f))
                    if (rate > 0) drawArc(color, -90f, 360f * anim, useCenter = false, style = Stroke(14f))
                }
                Text("$rate%", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            }
            Column(Modifier.weight(1f)) {
                EchoH("Savings rate")
                Spacer(Modifier.height(4.dp))
                Text(
                    if (income > 0)
                        "Kept ${"%,.0f".format(income - spent)} of ${"%,.0f".format(income)} in this view."
                    else "No income in this view yet — savings rate needs income.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    if (rate >= 20) "Excellent — 20%+ is the pro zone."
                    else if (rate >= 0) "Tip: pay yourself first on income day."
                    else "Overspent — check category caps below.",
                    style = MaterialTheme.typography.bodySmall, color = color
                )
            }
        }
    }
}

@Composable
fun MonthCompareCard(txs: List<EchoTransaction>, onCategoryClick: (Category) -> Unit = {}) {
    val tap = rememberTap()
    val thisM = remember(txs) { EchoStats.byCategoryMonth(txs, 0).toMap() }
    val lastM = remember(txs) { EchoStats.byCategoryMonth(txs, 1).toMap() }
    val cats = remember(thisM, lastM) {
        (thisM.keys + lastM.keys).sortedByDescending { thisM[it] ?: 0.0 }.take(6)
    }
    Card(colors = CardDefaults.cardColors(containerColor = echoCard())) {
        Column(Modifier.padding(16.dp)) {
            EchoH("This month vs last", Sky)
            Spacer(Modifier.height(4.dp))
            if (cats.isEmpty()) Text("Not enough history yet — check back next month.",
                style = MaterialTheme.typography.bodySmall)
            cats.forEach { c ->
                val a = thisM[c] ?: 0.0
                val b = lastM[c] ?: 0.0
                val d = if (b > 0) ((a - b) / b * 100).toInt() else if (a > 0) 100 else 0
                Row(
                    Modifier.fillMaxWidth().clickable { tap { onCategoryClick(c) } }.padding(vertical = 5.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(c.name.lowercase() + " ›", fontWeight = FontWeight.SemiBold)
                        Text("last month ${"%,.0f".format(b)}", style = MaterialTheme.typography.bodySmall,
                            color = echoInk().copy(alpha = 0.6f))
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("%,.0f".format(a), fontWeight = FontWeight.Bold)
                        Text(
                            when {
                                d == 0 -> "— same"
                                d > 0 -> "▲ $d%"
                                else -> "▼ ${-d}%"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (d <= 0) Mint else Rose, fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun YearChart(txs: List<EchoTransaction>, onMonthClick: (Long) -> Unit = {}) {
    val months = remember(txs) { EchoStats.perMonth(EchoStats.visible(txs, true), 12) }
    val maxM = (months.maxOfOrNull { it.amount } ?: 1.0).coerceAtLeast(1.0)
    val tap = rememberTap()
    Card(colors = CardDefaults.cardColors(containerColor = echoCard())) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                EchoH("Last 12 months — tap a month")
                Text("%,.0f".format(months.sumOf { it.amount }), fontWeight = FontWeight.Bold, color = Amber)
            }
            Spacer(Modifier.height(8.dp))
            Text("Tap any bar to open that month in Echoes.", style = MaterialTheme.typography.labelSmall,
                color = echoInk().copy(alpha = 0.6f))
            Spacer(Modifier.height(4.dp))
            Canvas(Modifier.fillMaxWidth().height(110.dp).pointerInput(months) {
                detectTapGestures { off ->
                    val n = months.size; if (n == 0) return@detectTapGestures
                    val bw = size.width / (n * 1.6f)
                    val i = (off.x / (1.6f * bw)).toInt()
                    if (i in months.indices) tap { onMonthClick(months[i].monthStart) }
                }
            }) {
                val n = months.size; if (n == 0) return@Canvas
                val bw = size.width / (n * 1.6f)
                months.forEachIndexed { i, m ->
                    val h = (m.amount / maxM * (size.height - 20)).toFloat()
                    drawRect(
                        if (i == n - 1) Amber else Mint,
                        topLeft = androidx.compose.ui.geometry.Offset(i * 1.6f * bw + bw * 0.3f, size.height - h),
                        size = Size(bw, h)
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(months.firstOrNull()?.label ?: "", style = MaterialTheme.typography.labelSmall)
                Text(months.lastOrNull()?.label ?: "", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
fun CategoryCapsCard(txs: List<EchoTransaction>, onCategoryClick: (Category) -> Unit = {}) {
    val ctx = LocalContext.current
    val spentByCat = remember(txs) { EchoStats.byCategory(txs).toMap() }
    var refresh by remember { mutableStateOf(0) }
    Card(colors = CardDefaults.cardColors(containerColor = echoCard())) {
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
                                color = if (over) Rose else echoInk())
                            Text("spent ${"%,.0f".format(spent)}" +
                                if (cap > 0) " / cap ${"%,.0f".format(cap)}" else " / no cap",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (over) Rose else echoInk().copy(alpha = 0.7f))
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
fun SourcesScreen(all: List<EchoTransaction>, tab: String, onTab: (String) -> Unit,
                  onUpdateFound: (UpdateChecker.UpdateInfo) -> Unit = {}) {
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
                    checkMsg = "${info.tag} available — updating in-app."
                    UpdateChecker.notify(ctx, info)
                    onUpdateFound(info)
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
            colors = CardDefaults.cardColors(containerColor = echoCard()),
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
            colors = CardDefaults.cardColors(containerColor = echoCard()),
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
            colors = CardDefaults.cardColors(containerColor = echoCard()),
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
            Card(colors = CardDefaults.cardColors(containerColor = echoCard()), shape = RoundedCornerShape(16.dp)) {
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
            Card(colors = CardDefaults.cardColors(containerColor = echoCard()), shape = RoundedCornerShape(16.dp)) {
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
            Card(colors = CardDefaults.cardColors(containerColor = echoCardHi()), shape = RoundedCornerShape(16.dp)) {
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
