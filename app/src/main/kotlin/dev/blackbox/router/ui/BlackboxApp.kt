package dev.blackbox.router.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.*
import dev.blackbox.core.*
import dev.blackbox.router.RouterViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Ink = Color(0xFF0C0F0D)
private val Panel = Color(0xFF171C18)
private val Lime = Color(0xFFC7F36B)
private val Muted = Color(0xFFABB3A8)
private val Amber = Color(0xFFF2C574)
private val Red = Color(0xFFFF9E94)
private val colors = darkColorScheme(primary = Lime, onPrimary = Ink, background = Ink, surface = Panel,
    onBackground = Color(0xFFF1F3EC), onSurface = Color(0xFFF1F3EC), onSurfaceVariant = Muted,
    secondary = Amber, secondaryContainer = Color(0xFF27331D), onSecondaryContainer = Lime,
    surfaceContainer = Panel, surfaceContainerHigh = Panel, surfaceContainerHighest = Panel,
    surfaceContainerLow = Ink, surfaceContainerLowest = Ink,
    outline = Color(0xFF343C33), error = Red)

private enum class Page(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Outlined.Dashboard), CLIENTS("Clients", Icons.Outlined.Devices),
    NETWORK("Network", Icons.Outlined.Router), SERVICES("Services", Icons.Outlined.Dns),
    LOGS("Logs", Icons.AutoMirrored.Outlined.ListAlt), SETTINGS("Settings", Icons.Outlined.Tune)
}

@Composable
fun BlackboxApp(vm: RouterViewModel) {
    MaterialTheme(colorScheme = colors) {
        val state by vm.state.collectAsStateWithLifecycle()
        val developer by vm.developer.collectAsStateWithLifecycle()
        val actionError by vm.actionError.collectAsStateWithLifecycle()
        actionError?.let { message -> AlertDialog(onDismissRequest=vm::clearActionError,title={Text("Action needs attention")},text={Text(message)},confirmButton={TextButton(onClick=vm::clearActionError){Text("OK")}}) }
        val nav = rememberNavController()
        val backStack by nav.currentBackStackEntryAsState()
        val current = backStack?.destination?.route ?: Page.HOME.name
        val snackbar = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()
        val context = LocalContext.current
        val copyReport: () -> Unit = {
            state.report?.let { report ->
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("BLACKBOX diagnostics", Sanitizer.report(report, developer)))
                scope.launch { snackbar.showSnackbar("Sanitized report copied") }
            }
        }
        Scaffold(
            containerColor = Ink,
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                NavigationBar(containerColor = Ink, tonalElevation = 0.dp) {
                    Page.entries.forEach { page ->
                        NavigationBarItem(selected = current == page.name,
                            onClick = {
                                nav.navigate(page.name) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }, icon = { Icon(page.icon, contentDescription = null, modifier = Modifier.size(21.dp)) },
                            label = { Text(page.label, fontSize = 10.sp, maxLines = 1) },
                            colors = NavigationBarItemDefaults.colors(selectedIconColor = Lime, selectedTextColor = Lime,
                                indicatorColor = Panel, unselectedIconColor = Muted, unselectedTextColor = Muted))
                    }
                }
            },
        ) { padding ->
            NavHost(nav, startDestination = Page.HOME.name, modifier = Modifier.padding(padding)) {
                composable(Page.HOME.name) { RouterHome(vm,state) {nav.navigate(Page.NETWORK.name)} }
                composable(Page.NETWORK.name) {
                    var inspect by remember { mutableStateOf(false) }
                    Column { TextButton(onClick={inspect=!inspect}) {Text(if(inspect) "Network profiles" else "Hardware compatibility")};Box(Modifier.weight(1f)) {if(inspect) Network(state,{vm.scan(true)},copyReport) else ProfileEditor(vm)} }
                }
                composable(Page.CLIENTS.name) { RouterClients(vm) }
                composable(Page.SERVICES.name) { RouterServices(vm) }
                composable(Page.LOGS.name) { var inspect by remember {mutableStateOf(false)};if(inspect) Logs(state,developer,copyReport) else RuntimeLogs(vm){inspect=true} }
                composable(Page.SETTINGS.name) { Settings(developer, vm::setDeveloper, state, copyReport) }
            }
        }
    }
}

@Composable
private fun Topline(section: String, busy: Boolean = false) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Column {
            Text("BLACKBOX", fontSize = 23.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp)
            Text(section.uppercase(), style = MaterialTheme.typography.labelSmall, color = Muted, letterSpacing = 2.sp)
        }
        Surface(color = Color(0xFF252C20), shape = RoundedCornerShape(50)) {
            Text(if (busy) "● SCANNING" else "◉ READ ONLY", Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                color = if (busy) Lime else Muted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun Network(state: RouterUiState, scan: () -> Unit, copy: () -> Unit) {
    val report = state.report
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Topline("Compatibility / 02", state.status == RouterStatus.CHECKING) }
        item { Text("Built on evidence.", fontSize = 30.sp, fontWeight = FontWeight.SemiBold) }
        item { Text("Supported means advertised or observed, as described below. Router readiness requires a separate runtime test.", color = Muted, fontSize = 14.sp) }
        if (report == null) item { Notice("Run diagnostics from Home to inspect this phone.") }
        report?.let { r ->
            item { Text("Snapshot · ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(r.snapshot.capturedAtMs))}", color = Muted, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
            items(r.capabilities) { CapabilityCard(it) }
            item { Eyebrow("EXECUTABLES") }
            items(r.binaries) { binary ->
                Surface(color = Panel, shape = RoundedCornerShape(12.dp)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        FactRow(binary.name, if (binary.path != null) "FOUND" else "NOT FOUND", binary.path != null)
                        Text(binary.path ?: "Not visible in the current shell and search paths. May need root or a later bundled tool.", fontSize = 12.sp, color = Muted)
                        if (binary.name in setOf("hostapd", "dnsmasq") && binary.path != null) Text("Discovery only; runtime startup checks the provisioned tool separately.", color = Amber, fontSize = 12.sp)
                    }
                }
            }
            item { Eyebrow("NETWORK DISCOVERY") }
            item {
                Surface(color = Panel, shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        FactRow("APP DEFAULT", r.facts.activeInterface ?: "Unknown")
                        FactRow("INTERFACES", (r.snapshot.interfaces.map { it.name } + r.snapshot.visibleInterfaceNames).distinct().size.takeIf { it > 0 }?.toString() ?: "Unknown / not visible")
                        FactRow("ROUTES", r.snapshot.routes.size.takeIf { it > 0 }?.toString() ?: "Unknown / not visible")
                        FactRow("POLICY RULES", r.snapshot.rules.size.takeIf { it > 0 }?.toString() ?: "Unknown / not visible")
                        Text("Android app routes do not establish how forwarded LAN traffic will be routed.", color = Muted, fontSize = 12.sp)
                        r.snapshot.interfaces.forEach { Text("${it.name} · ${it.state ?: "unknown state"}", fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
                        if (r.snapshot.interfaces.isEmpty() && r.snapshot.visibleInterfaceNames.isNotEmpty()) {
                            Text("Names visible through sysfs; link state and addresses could not be verified.", color = Amber, fontSize = 12.sp)
                            Text(r.snapshot.visibleInterfaceNames.joinToString(" · "), color = Muted, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        }
                        r.snapshot.wifi.forEach { Text("${it.name} · ${it.phy} · ${it.type} · channel ${it.channel ?: "unknown"}", fontSize = 12.sp, color = Lime) }
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = scan, enabled = state.status != RouterStatus.CHECKING, modifier = Modifier.weight(1f)) { Text("Rescan") }
                OutlinedButton(onClick = copy, enabled = report != null, modifier = Modifier.weight(1f)) { Text("Copy report") }
            }
        }
    }
}

@Composable
private fun CapabilityCard(capability: Capability) {
    Surface(color = Panel, shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(capability.title, fontWeight = FontWeight.Medium, fontSize = 15.sp)
            Text(capability.support.name, fontFamily = FontFamily.Monospace, fontSize = 11.sp, letterSpacing = 1.sp,
                color = when (capability.support) { Support.SUPPORTED -> Lime; Support.UNSUPPORTED -> Red; Support.UNKNOWN -> Amber })
            Text(capability.detail, color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
        }
    }
}

@Composable
private fun Logs(state: RouterUiState, developer: Boolean, copy: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Topline("Diagnostics / 03", state.status == RouterStatus.CHECKING) }
        item { Text("Every check.\nA clear result.", fontSize = 32.sp, lineHeight = 37.sp, fontWeight = FontWeight.SemiBold) }
        item { Text(if (developer) "Developer Mode · sanitized command evidence. Addresses and sensitive fields are redacted."
            else "Human-readable results. Enable Developer Mode in Settings for sanitized commands and output.", color = Muted, fontSize = 13.sp) }
        if (state.report == null) item { Notice("No completed diagnostic scan yet.") }
        items(state.report?.records.orEmpty()) { record ->
            Surface(color = Panel, shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(record.label, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                    Text(when {
                        record.result.truncated -> "Incomplete output · no capability claim"
                        record.result.success -> "Check completed"
                        record.result.failure == CommandFailure.SKIPPED -> "Skipped · required tool not found"
                        record.result.failure == CommandFailure.TIMEOUT -> "Timed out · retry diagnostics"
                        else -> "Unavailable or denied · no capability claim"
                    }, color = if (record.result.success) Lime else Amber, fontSize = 12.sp)
                    Text("${record.result.execution.name.lowercase()} shell · ${record.result.durationMs} ms", color = Muted, fontSize = 11.sp)
                    if (developer) SelectionContainer {
                        Text("$ ${record.command}\nexit ${record.result.exitCode}\n${record.result.stdout}\n${record.result.stderr}".trimEnd(),
                            fontSize = 11.sp, lineHeight = 16.sp, fontFamily = FontFamily.Monospace, color = Muted)
                    }
                }
            }
        }
        item { OutlinedButton(onClick = copy, enabled = state.report != null, modifier = Modifier.fillMaxWidth()) { Text("Copy sanitized report") } }
    }
}

@Composable
private fun Settings(developer: Boolean, setDeveloper: (Boolean) -> Unit, state: RouterUiState, copy: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        item { Topline("Settings / 04") }
        item { Text("Under your control.", fontSize = 30.sp, fontWeight = FontWeight.SemiBold) }
        item {
            Surface(color = Panel, shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Developer Mode", fontSize = 18.sp, fontWeight = FontWeight.Medium)
                            Text("Sanitized shell evidence", fontSize = 12.sp, color = Muted)
                        }
                        Switch(checked = developer, onCheckedChange = setDeveloper)
                    }
                    Text("Shows commands, exit codes and output in Logs. Wi-Fi passwords are never collected. Exports redact network identifiers.", fontSize = 13.sp, color = Muted, lineHeight = 20.sp)
                }
            }
        }
        item { OutlinedButton(onClick = copy, enabled = state.report != null, modifier = Modifier.fillMaxWidth()) { Text("Copy diagnostic report") } }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Eyebrow("THIS BUILD")
                FactRow("VERSION", "0.3.0 / DEVELOPMENT")
                FactRow("BACKEND", "Native AP + isolated routing")
                FactRow("NETWORK CHANGES", "Journaled and reversible")
                FactRow("ROUTER", "Device validation in progress")
                Text("Profiles use Room and Android Keystore encryption. Router mode uses a foreground service and a root recovery watchdog. Diagnostics alone are read-only.", color = Muted, fontSize = 13.sp, lineHeight = 20.sp)
                Text("BLACKBOX keeps Android in charge of your phone. Rooting, bootloader changes and flashing are outside this app.", color = Muted, fontSize = 13.sp, lineHeight = 20.sp)
            }
        }
    }
}

@Composable
private fun FuturePage(section: String, title: String, icon: ImageVector, milestone: String, description: String, planned: List<String>) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        item { Topline(section) }
        item { Text(title, fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.SemiBold) }
        item {
            Surface(color = Panel, shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Icon(icon, null, Modifier.size(42.dp), tint = Muted)
                    Text(milestone, fontSize = 21.sp)
                    Text(description, color = Muted, fontSize = 14.sp, lineHeight = 22.sp)
                }
            }
        }
        item { Eyebrow("PLANNED CAPABILITIES") }
        items(planned) { Text("+  $it", color = Muted, fontSize = 14.sp) }
    }
}

@Composable
private fun Eyebrow(text: String) { Text(text, color = Muted, fontFamily = FontFamily.Monospace, fontSize = 11.sp, letterSpacing = 1.5.sp) }

@Composable
private fun FactRow(label: String, value: String, highlight: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(0.85f), color = Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        Text(value, Modifier.weight(1.15f), color = if (highlight) Lime else colors.onSurface, fontSize = 12.sp)
    }
}

@Composable
private fun Metric(title: String, value: String, description: String, modifier: Modifier) {
    Surface(modifier, color = Panel, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Eyebrow(title)
            Text(value, fontSize = 21.sp, fontWeight = FontWeight.Medium)
            Text(description, fontSize = 11.sp, color = Muted)
        }
    }
}

@Composable
private fun Health(facts: DeviceFacts) {
    Surface(color = Panel, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Eyebrow("DEVICE HEALTH · AT LAST SCAN")
            FactRow("BATTERY", "${facts.batteryPercent?.let { "$it%" } ?: "Unknown"} · ${when (facts.charging) { true -> "Plugged in"; false -> "On battery"; null -> "Unknown" }}")
            FactRow("TEMPERATURE", facts.batteryCelsius?.let { String.format(Locale.getDefault(), "%.1f °C", it) } ?: "Unknown")
            FactRow("THERMAL STATE", facts.thermalStatus ?: "Not reported")
            if ((facts.batteryCelsius ?: 0f) >= 45f || facts.thermalStatus in listOf("Severe", "Critical", "Emergency", "Shutdown")) {
                Text("Device is hot. Reduce load and let it cool before sustained routing.", color = Amber, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun Notice(text: String) {
    Surface(color = Color(0xFF2C271D), shape = RoundedCornerShape(14.dp)) {
        Text(text, Modifier.fillMaxWidth().padding(18.dp), color = Amber, fontSize = 13.sp, lineHeight = 20.sp)
    }
}
