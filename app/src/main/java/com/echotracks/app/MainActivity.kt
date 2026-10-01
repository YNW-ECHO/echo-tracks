package com.echotracks.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.compose.*
import com.echotracks.app.data.BudgetStore
import com.echotracks.app.data.EchoStats
import com.echotracks.app.data.Exporter
import com.echotracks.app.data.SmsReader
import com.echotracks.app.data.UpdateChecker
import com.echotracks.app.model.*
import com.echotracks.app.security.AppLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

val Mint = Color(0xFF00E5A0); val Midnight = Color(0xFF0E1A2B)
val CardBg = Color(0xFF16263D); val Ink = Color(0xFFF2F7F5)
val Rose = Color(0xFFFF8A80); val Amber = Color(0xFFFFB020)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_SECURE,
            android.view.WindowManager.LayoutParams.FLAG_SECURE
        )
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Mint, background = Midnight, surface = CardBg,
                    onBackground = Ink, onSurface = Ink
                )
            ) { EchoApp() }
        }
    }
}

@Composable
fun EchoApp() {
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
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { ok ->
        granted = ok
        if (ok) {
            loading = true
            scope.launch(Dispatchers.IO) {
                val tx = SmsReader.toTransactions(SmsReader.readAll(ctx))
                withContext(Dispatchers.Main) { txs = tx; loading = false }
            }
        }
    }
    LaunchedEffect(granted) {
        if (granted && txs.isEmpty() && !loading) {
            loading = true
            scope.launch(Dispatchers.IO) {
                val tx = SmsReader.toTransactions(SmsReader.readAll(ctx))
                withContext(Dispatchers.Main) { txs = tx; loading = false }
            }
        }
    }
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
                }) { Text("Download") }
            },
            dismissButton = {
                TextButton(onClick = {
                    UpdateChecker.dismiss(ctx, info.tag); update = null
                }) { Text("Later") }
            }
        )
    }

    if (!unlocked) {
        LockGate(
            pinInput = pinInput, onInput = { pinInput = it },
            onUnlock = {
                if (AppLock.checkPin(ctx, pinInput)) { unlocked = true; pinInput = "" }
                else Toast.makeText(ctx, "Wrong PIN", Toast.LENGTH_SHORT).show()
            },
            setupMode = !AppLock.hasPin(ctx), setup = pinSetup, onSetup = { pinSetup = it },
            onSavePin = {
                if (pinSetup.length >= 4) { AppLock.setPin(ctx, pinSetup); unlocked = true }
                else Toast.makeText(ctx, "PIN must be 4+ digits", Toast.LENGTH_SHORT).show()
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

    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = CardBg) {
                listOf("Home", "Echoes", "Stats", "Sources").forEach { s ->
                    NavigationBarItem(
                        selected = backStack?.destination?.route == s,
                        onClick = { nav.navigate(s) { launchSingleTop = true } },
                        label = { Text(s) },
                        icon = {
                            Text(
                                when (s) { "Home" -> "◉"; "Echoes" -> "☰"; "Stats" -> "◈"; else -> "⬣" },
                                color = if (backStack?.destination?.route == s) Mint else Ink
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
                                val tx = SmsReader.toTransactions(SmsReader.readAll(ctx))
                                withContext(Dispatchers.Main) { txs = tx; loading = false }
                            }
                        },
                        hideNoise = hideNoise, onToggleNoise = { hideNoise = !hideNoise },
                        rangeKey = rangeKey, onRange = { rangeKey = it },
                        sourceTab = sourceTab, onSource = { sourceTab = it },
                        onExport = {
                            scope.launch(Dispatchers.IO) {
                                val csv = Exporter.toCsv(filtered)
                                val f = Exporter.savePublic(ctx, "echo_tracks_${System.currentTimeMillis()}.csv", csv)
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(ctx, "Saved: ${f.absolutePath}", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    )
                }
                composable("Echoes") { EchoesGrouped(txs = filtered, query = query, onQuery = { query = it }) }
                composable("Stats") { StatsDash(txs = filtered) }
                composable("Sources") { SourcesScreen(all = txs, tab = sourceTab, onTab = { sourceTab = it }) }
            }
        }
    }
}

fun hasSms(ctx: android.content.Context) =
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

// ---------- LOCK ----------
@Composable
fun LockGate(pinInput: String, onInput: (String) -> Unit, onUnlock: () -> Unit,
             setupMode: Boolean, setup: String, onSetup: (String) -> Unit, onSavePin: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text("◉ Echo Tracks", color = Mint, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        if (setupMode) {
            Text("Set a 4+ digit PIN to protect your money data")
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(value = setup, onValueChange = onSetup, label = { Text("New PIN") })
            Spacer(Modifier.height(12.dp))
            Button(onClick = onSavePin, colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Midnight)) { Text("Save & Enter") }
        } else {
            Text("Enter PIN (fingerprint coming on device with biometrics)")
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(value = pinInput, onValueChange = onInput, label = { Text("PIN") })
            Spacer(Modifier.height(12.dp))
            Button(onClick = onUnlock, colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Midnight)) { Text("Unlock") }
        }
    }
}

// ---------- HOME DASHBOARD ----------
@Composable
fun DashboardHome(spent: Double, income: Double, net: Double, txs: List<EchoTransaction>,
                  loading: Boolean, granted: Boolean, onGrant: () -> Unit, onRescan: () -> Unit,
                  hideNoise: Boolean, onToggleNoise: () -> Unit,
                  rangeKey: String, onRange: (String) -> Unit,
                  sourceTab: String, onSource: (String) -> Unit, onExport: () -> Unit) {
    val trend = remember(txs) { EchoStats.perDay(txs, 14) }
    val maxT = (trend.maxOfOrNull { it.second } ?: 1.0).coerceAtLeast(1.0)
    val byCat = remember(txs) { EchoStats.byCategory(txs).take(5) }
    val maxC = (byCat.firstOrNull()?.second ?: 1.0).coerceAtLeast(1.0)
    val top = remember(txs) { EchoStats.topRecipients(txs, 5) }
    val ctx2 = LocalContext.current
    var goalTxt by remember { mutableStateOf(BudgetStore.get(ctx2).toInt().toString()) }
    val monthSpend = remember(txs) { EchoStats.monthSpent(EchoStats.visible(txs, true)) }
    val bills = remember(txs) { EchoStats.upcomingBills(EchoStats.visible(txs, false)) }

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = CardBg)) {
                Column(Modifier.padding(18.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Today's Echo", color = Mint, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        TextButton(onClick = onExport) { Text("Export CSV", color = Mint) }
                    }
                    Text("Spent ${"%,.0f".format(spent)}  •  In ${"%,.0f".format(income)}",
                        style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(if (net >= 0) "Net +${"%,.0f".format(net)} — saving" else "Net ${"%,.0f".format(net)} — overspent",
                        color = if (net >= 0) Mint else Rose)
                    Spacer(Modifier.height(6.dp))
                    // savings rate + month compare — dashboard polish
                    run {
                        val rate = EchoStats.savingsRate(spent, income)
                        val (thisM, lastM) = EchoStats.thisMonthVsLast(txs)
                        val delta = if (lastM > 0) (((thisM - lastM) / lastM * 100).toInt()) else 0
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AssistChip(onClick = {}, label = { Text("Saved $rate%") })
                            AssistChip(onClick = {}, label = {
                                Text(if (delta <= 0) "↓ ${-delta}% vs last month" else "↑ $delta% vs last month")
                            })
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(EchoStats.dailyStory(txs), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(4.dp))
                    Text("💡 ${EchoStats.insight(txs)}", style = MaterialTheme.typography.bodySmall, color = Amber)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
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
            Card(colors = CardDefaults.cardColors(containerColor = CardBg)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Monthly budget goal", color = Mint, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    run {
                        val goal = goalTxt.toDoubleOrNull()?.coerceAtLeast(1.0) ?: 1.0
                        val pct = (monthSpend / goal).coerceIn(0.0, 1.0)
                        Text("Spent ${"%,.0f".format(monthSpend)} of ${"%,.0f".format(goal)} this month (${(pct * 100).toInt()}%)",
                            fontWeight = FontWeight.Bold, color = if (pct >= 1.0) Rose else if (pct >= 0.8) Amber else Mint)
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(progress = { pct.toFloat() },
                            color = if (pct >= 1.0) Rose else if (pct >= 0.8) Amber else Mint,
                            trackColor = Midnight, modifier = Modifier.fillMaxWidth().height(10.dp))
                        Spacer(Modifier.height(4.dp))
                        Text(if (pct >= 1.0) "⚠ Over budget — freeze non-essentials." else if (pct >= 0.8) "Caution: 80%+ used." else "On track. Keep it under.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = goalTxt, onValueChange = { goalTxt = it.filter { c -> c.isDigit() }.take(9) },
                            label = { Text("Goal (Ksh)") }, modifier = Modifier.weight(1f), singleLine = true)
                        Button(onClick = { BudgetStore.set(ctx2, goalTxt.toDoubleOrNull() ?: 30000.0) },
                            colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Midnight)) { Text("Save") }
                    }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = CardBg)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Bill reminders", color = Amber, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    if (bills.isEmpty()) Text("No repeating bills detected yet. Pay KPLC / DSTV twice and I'll track them.",
                        style = MaterialTheme.typography.bodySmall)
                    bills.forEach { b ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically) {
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
            Card(colors = CardDefaults.cardColors(containerColor = CardBg)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Spend trend — last 14 days", color = Mint, fontWeight = FontWeight.Bold)
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
                    Text("Where it went", color = Mint, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    if (byCat.isEmpty()) Text("No spend in this view yet.")
                    byCat.forEach { (c, v) ->
                        Column(Modifier.padding(vertical = 4.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(c.name.lowercase()); Text("%,.0f".format(v), fontWeight = FontWeight.Bold)
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
                    Text("Top destinations", color = Mint, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    if (top.isEmpty()) Text("Nothing yet.")
                    top.forEach { (w, v) -> Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(w.take(28), modifier = Modifier.weight(1f)); Text("%,.0f".format(v), fontWeight = FontWeight.Bold)
                    } }
                }
            }
        }
        item {
            Text("Recent echoes", color = Mint, fontWeight = FontWeight.Bold)
        }
        items(txs.take(30)) { t ->
            Card(colors = CardDefaults.cardColors(containerColor = CardBg)) {
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(t.who.take(30), fontWeight = FontWeight.SemiBold)
                        Text("${t.category.name.lowercase()} • ${t.source.name} • ${java.text.DateFormat.getDateInstance().format(java.util.Date(t.dateMillis))}",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Text("${if (t.direction == Direction.OUT) "−" else "+"} ${"%,.0f".format(t.amount)}",
                        color = if (t.direction == Direction.OUT) Rose else Mint, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// ---------- ECHOES grouped by day ----------
@Composable
fun EchoesGrouped(txs: List<EchoTransaction>, query: String, onQuery: (String) -> Unit) {
    val groups = remember(txs) {
        txs.groupBy { EchoStats.startOfDay(it.dateMillis) }.toList().sortedByDescending { it.first }.take(60)
    }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            OutlinedTextField(value = query, onValueChange = onQuery, label = { Text("Search name, till, category…") }, modifier = Modifier.fillMaxWidth())
        }
        if (txs.isEmpty()) item { Text("No echoes in this view. Try ALL + unhide noise.") }
        groups.forEach { (day, list) ->
            item {
                val (s, _) = EchoStats.totals(list)
                Text("${java.text.DateFormat.getDateInstance().format(java.util.Date(day))} — spent ${"%,.0f".format(s)} (${list.size})",
                    color = Mint, fontWeight = FontWeight.Bold)
            }
            items(list) { t ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${t.who.take(26)} • ${t.category.name.lowercase()}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text("${if (t.direction == Direction.OUT) "−" else "+"} ${"%,.0f".format(t.amount)}",
                        color = if (t.direction == Direction.OUT) Rose else Mint)
                }
            }
        }
    }
}

// ---------- STATS ----------
@Composable
fun StatsDash(txs: List<EchoTransaction>) {
    val byCat = remember(txs) { EchoStats.byCategory(txs) }
    val total = (byCat.sumOf { it.second }).coerceAtLeast(1.0)
    val palette = listOf(Mint, Amber, Rose, Color(0xFF7C9AFF), Color(0xFF9D7BFF), Color(0xFF5BD8C8))
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = CardBg)) {
                Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Share by category", color = Mint, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Canvas(Modifier.size(150.dp)) {
                        var start = -90f
                        byCat.forEachIndexed { i, (_, v) ->
                            val sweep = (v / total * 360).toFloat()
                            drawArc(palette[i % palette.size], start, sweep, useCenter = false, style = Stroke(28f))
                            start += sweep
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    byCat.forEachIndexed { i, (c, v) ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("● ${c.name.lowercase()}", color = palette[i % palette.size])
                            Text("${"%,.0f".format(v)} (${(v / total * 100).toInt()}%)", fontWeight = FontWeight.Bold)
                        }
                    }
                    if (byCat.isEmpty()) Text("No data in this view.")
                }
            }
        }
    }
}

@Composable
fun SourcesScreen(all: List<EchoTransaction>, tab: String, onTab: (String) -> Unit) {
    val m = remember(all) { all.filter { it.source == Source.MPESA } }
    val b = remember(all) { all.filter { it.source == Source.BANK } }
    val (ms, mi) = EchoStats.totals(EchoStats.visible(m, true))
    val (bs, bi) = EchoStats.totals(EchoStats.visible(b, true))
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("ALL", "MPESA", "BANK").forEach { t ->
                FilterChip(selected = tab == t, onClick = { onTab(t) }, label = { Text(t) })
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = CardBg)) {
            Column(Modifier.padding(16.dp)) {
                Text("M-PESA  •  ${m.size} echoes", color = Mint, fontWeight = FontWeight.Bold)
                Text("Spent ${"%,.0f".format(ms)}  •  In ${"%,.0f".format(mi)}")
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = CardBg)) {
            Column(Modifier.padding(16.dp)) {
                Text("BANKS  •  ${b.size} echoes", color = Amber, fontWeight = FontWeight.Bold)
                Text("Spent ${"%,.0f".format(bs)}  •  In ${"%,.0f".format(bi)}")
            }
        }
        Text("M-PESA is your main track. Banks stay separate so totals never mix. Switch the tab to filter Home + Echoes.",
            style = MaterialTheme.typography.bodySmall)
    }
}
