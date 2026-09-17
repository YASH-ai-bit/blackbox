package dev.blackbox.router.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.blackbox.core.*
import dev.blackbox.router.RouterViewModel
import java.util.UUID

@Composable private fun RouterCard(title:String,content:@Composable ColumnScope.()->Unit) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text(title.uppercase(),fontFamily=FontFamily.Monospace,fontSize=12.sp,color=MaterialTheme.colorScheme.primary);content()
    } }
}
@Composable fun RouterHome(vm:RouterViewModel,diagnostics:RouterUiState,network:()->Unit) {
    val s by vm.runtime.collectAsStateWithLifecycle()
    val inspection by vm.recoveryInspection.collectAsStateWithLifecycle()
    inspection?.let {text->AlertDialog(onDismissRequest=vm::closeRecoveryInspection,title={Text("Interrupted session")},text={Text(text,fontSize=12.sp)},confirmButton={TextButton(onClick=vm::closeRecoveryInspection){Text("CLOSE")}})}
    var selected by remember { mutableStateOf<String?>(null) }
    val profile=s.profiles.firstOrNull { it.id==selected }?:s.profiles.firstOrNull()
    val active=s.status in setOf(RouterStatus.RUNNING,RouterStatus.DEGRADED)
    val busy=s.status in setOf(RouterStatus.CHECKING,RouterStatus.STARTING,RouterStatus.STOPPING,RouterStatus.RECOVERING)
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(24.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
        item { Text("BLACKBOX",fontSize=30.sp,fontWeight=FontWeight.Black,letterSpacing=4.sp);Text("YOUR PORTABLE NETWORK",fontFamily=FontFamily.Monospace,color=MaterialTheme.colorScheme.primary) }
        item { RouterCard(s.status.name) {
            Text(if(active) s.profile?.ssid?:"BLACKBOX" else "Take your network\nwith you.",fontSize=32.sp,lineHeight=37.sp)
            Text(s.message,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if(s.status in setOf(RouterStatus.CHECKING,RouterStatus.STARTING)) TextButton(onClick={vm.routerCommand("STOP")}) {Text("CANCEL STARTUP")}
            if(s.recovery) {
                Text("An interrupted session needs recovery before starting.",color=MaterialTheme.colorScheme.error)
                Button(onClick={vm.routerCommand("RECOVER")},enabled=!busy) {Text("RECOVER NETWORK")}
                TextButton(onClick=vm::inspectRecovery) {Text("INSPECT")}
            } else if(active) Button(onClick={vm.routerCommand("STOP")},modifier=Modifier.fillMaxWidth()) {Text("STOP ROUTER")}
            else {
                s.profiles.forEach { p -> FilterChip(selected=p.id==profile?.id,onClick={selected=p.id},label={Text("${p.name} · ${p.mode.name}")}) }
                Button(onClick={profile?.let {vm.routerCommand("START",it.id)}},enabled=profile!=null&&!busy,modifier=Modifier.fillMaxWidth()) {Text("START ROUTER")}
                TextButton(onClick=network) {Text(if(profile==null) "Create your first network" else "Edit network profiles")}
            }
        } }
        if(active) {
            item { RouterCard("Private network") {
                Text("Gateway  ${s.profile?.gateway}");Text("Upstream  ${s.upstream?:"Offline"}");Text("VPN  ${s.profile?.vpn}")
                Text("${s.clients.count {it.online}} clients · ${s.lan}")
                Text("↓ ${"%.2f".format(s.downBps*8/1_000_000.0)} Mbps     ↑ ${"%.2f".format(s.upBps*8/1_000_000.0)} Mbps",fontSize=20.sp)
                Text("Interface totals include LAN traffic. Client presence is estimated from leases and neighbors.",fontSize=12.sp)
            } }
        }
        item { RouterCard("Hardware & diagnostics") {
            Text(diagnostics.report?.verdict?:"Inspect your device before routing")
            Text("Root: ${diagnostics.report?.root?:RootStatus.NOT_REQUESTED}")
            Button(onClick={vm.scan(true)},enabled=diagnostics.status!=RouterStatus.CHECKING) {Text("REQUEST ROOT & RESCAN")}
            (s.health?:diagnostics.report?.facts)?.let { f ->
                Text("${f.model} · Android ${f.android}");Text("Battery ${f.batteryPercent?:"?"}% · ${if(f.charging==true) "Charging" else "On battery"} · ${f.batteryCelsius?:"?"} °C · ${f.thermalStatus?:"unknown thermal state"}")
                Text(if(active) "Health updates every 30 seconds." else "Health at last scan/session.",fontSize=12.sp)
                if((f.batteryCelsius?:0f)>=45f || f.thermalStatus in setOf("Severe","Critical","Emergency","Shutdown")) Text("Device is hot. Stop sustained routing and let it cool.",color=MaterialTheme.colorScheme.error)
            }
        } }
        item { Text("Development build · IPv4 routing. IPv6 is blocked on the private network to prevent unintended forwarding.",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable fun ProfileEditor(vm:RouterViewModel) {
    val s by vm.runtime.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf(vm.controller.newProfile()) }
    var dnsText by remember { mutableStateOf("") };var blocked by remember { mutableStateOf("") };var services by remember { mutableStateOf("") }
    var reveal by remember { mutableStateOf(false) };var error by remember { mutableStateOf<String?>(null) }
    fun load(p:RouterProfile) {draft=p;dnsText=p.dns.joinToString(", ");blocked=p.blockedDomains.joinToString("\n");services=p.services.joinToString("\n") { "${it.name}:${it.port}:${if(it.udp) "udp" else "tcp"}" }}
    val locked=s.status in setOf(RouterStatus.RUNNING,RouterStatus.DEGRADED,RouterStatus.CHECKING,RouterStatus.STARTING,RouterStatus.STOPPING,RouterStatus.RECOVERING)
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        item { Text("NETWORK",fontSize=28.sp,fontWeight=FontWeight.Bold);Text("Profiles stay encrypted on this phone.") }
        item { Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {TextButton(onClick={load(vm.controller.newProfile())}) {Text("New")};TextButton(onClick={load(draft.copy(id=UUID.randomUUID().toString(),name=draft.name+" copy"))}) {Text("Duplicate")}} }
        items(s.profiles) { p -> OutlinedButton(onClick={load(p)},modifier=Modifier.fillMaxWidth()) {Text(p.name)} }
        item { Field("Profile name",draft.name){draft=draft.copy(name=it)} }
        item { Field("SSID",draft.ssid){draft=draft.copy(ssid=it)} }
        item { OutlinedTextField(draft.password,{draft=draft.copy(password=it)},label={Text("Wi-Fi password")},visualTransformation=if(reveal) VisualTransformation.None else PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth(),singleLine=true)
            TextButton(onClick={reveal=!reveal}) {Text(if(reveal) "Hide password" else "Show password")} }
        item { Choice("Mode",RouterMode.entries,draft.mode){draft=draft.copy(mode=it)} }
        item { Text("Offline LAN works without Internet. Repeater requires connected Wi-Fi; Internet mode also accepts mobile data or Ethernet.",fontSize=12.sp) }
        item { Field("Gateway",draft.gateway){draft=draft.copy(gateway=it)};Field("Subnet prefix (24–28)",draft.prefix.toString()){it.toIntOrNull()?.let {n->draft=draft.copy(prefix=n)}} }
        item { Field("DHCP first",draft.first){draft=draft.copy(first=it)};Field("DHCP last",draft.last){draft=draft.copy(last=it)} }
        item { Choice("DNS",DnsMode.entries,draft.dnsMode){draft=draft.copy(dnsMode=it)} }
        item { Field("IPv4 DNS servers (comma separated)",dnsText){dnsText=it} }
        if(draft.dnsMode==DnsMode.AD_BLOCK) item { OutlinedTextField(blocked,{blocked=it},label={Text("Blocked domains, one per line")},modifier=Modifier.fillMaxWidth(),minLines=3);Text("Blocks these DNS names and subdomains. Encrypted DNS can bypass DNS filtering.",fontSize=12.sp) }
        item { Choice("VPN routing",VpnPolicy.entries,draft.vpn){draft=draft.copy(vpn=it)} }
        item { Text("PHONE_ONLY leaves the phone's existing VPN alone and gives clients the normal upstream. ALL_CLIENTS requires an existing VPN and blocks fallback.",fontSize=12.sp)
            Field("Existing VPN interface",draft.vpnInterface){draft=draft.copy(vpnInterface=it)}
            TextButton(onClick=vm::discoverVpn){Text("Discover VPN interfaces")}
            s.vpnInterfaces.forEach { name -> FilterChip(selected=draft.vpnInterface==name,onClick={draft=draft.copy(vpnInterface=name)},label={Text(name)}) }
        }
        item { Row {Text("WPA3",Modifier.weight(1f));Switch(draft.wpa3,{draft=draft.copy(wpa3=it)})};Text("WPA2 is the compatible default. WPA3 requires Android support.",fontSize=12.sp) }
        item { Row {Text("Balanced monitoring",Modifier.weight(1f));Switch(draft.balanced,{draft=draft.copy(balanced=it)})};Text("Balanced: 5 seconds. Performance: 2 seconds.",fontSize=12.sp) }
        item { Row {Text("Client isolation",Modifier.weight(1f));Switch(draft.isolation,{draft=draft.copy(isolation=it)})};Text("Requests hostapd layer-2 isolation. Verify with two clients before relying on it.",fontSize=12.sp) }
        item { OutlinedTextField(services,{services=it},label={Text("LAN services: name:port:tcp or udp")},modifier=Modifier.fillMaxWidth(),minLines=3)
            Text("dashboard:8080:tcp starts the built-in offline dashboard. Other entries, such as ssh:8022:tcp, allow your own Termux/Android server. Bind external servers to the private gateway address.",fontSize=12.sp) }
        item { error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
            Button(onClick={runCatching {
                val svc=services.lines().filter(String::isNotBlank).map { line -> val a=line.trim().split(':');require(a.size in 2..3){"Service format: name:port:tcp"};require(a.size<3 || a[2] in listOf("tcp","udp"));LocalService(a[0],a[1].toInt(),a.getOrNull(2)=="udp") }
                val p=draft.copy(dns=dnsText.split(',').map(String::trim).filter(String::isNotEmpty),blockedDomains=blocked.lines().map(String::trim).filter(String::isNotEmpty),services=svc)
                p.validate();vm.saveProfile(p);error=null
            }.onFailure { error=it.message }},enabled=!locked,modifier=Modifier.fillMaxWidth()){Text("SAVE PROFILE")}
            TextButton(onClick={vm.deleteProfile(draft.id)},enabled=!locked&&s.profiles.any {it.id==draft.id}){Text("Delete profile")}
        }
    }
}
@Composable private fun Field(label:String,value:String,change:(String)->Unit) {OutlinedTextField(value,change,label={Text(label)},singleLine=true,modifier=Modifier.fillMaxWidth())}
@Composable private fun <T:Enum<T>> Choice(label:String,values:List<T>,selected:T,change:(T)->Unit) {
    Text(label,fontWeight=FontWeight.SemiBold)
    values.forEach {value->FilterChip(selected=selected==value,onClick={change(value)},label={Text(value.name.replace('_',' '))})}
}
@Composable fun RouterClients(vm:RouterViewModel) {
    val s by vm.runtime.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        item {Text("CLIENTS",fontSize=28.sp,fontWeight=FontWeight.Bold);Text("${s.clients.count {it.online}} recently reachable")}
        if(s.clients.isEmpty()) item {Text("No BLACKBOX DHCP leases yet. Connect a device to the private network.")}
        items(s.clients,key={it.mac}) {c->RouterCard(c.hostname?:"Unnamed device") {
            Text("${c.ip}\n${c.mac}",fontFamily=FontFamily.Monospace);Text(if(c.online) "Recently reachable" else "Presence unknown / offline")
            Text("Lease expires: ${if(c.leaseUntil==0L) "infinite/unknown" else java.util.Date(c.leaseUntil*1000)}",fontSize=12.sp)
            Text("First seen: ${java.util.Date(c.firstSeen)}\nLast seen: ${java.util.Date(c.lastSeen)}",fontSize=12.sp)
            if(c.receivedBytes!=null) {
                Text("↓ ${"%.2f".format((c.receivedBps?:0)*8/1_000_000.0)} Mbps  ↑ ${"%.2f".format((c.sentBps?:0)*8/1_000_000.0)} Mbps")
                Text("Session: ↓ ${c.receivedBytes} B · ↑ ${c.sentBytes} B. Accepted forwarded traffic only; local services and DNS are excluded.",fontSize=12.sp)
            } else Text("Traffic counters start with the router session.",fontSize=12.sp)
            Choice("Access",ClientPolicy.entries,c.policy){vm.policy(c.mac,it)}
            Text("Paused blocks Internet and phone services. Peer-to-peer Wi-Fi follows the profile's client-isolation setting.",fontSize=12.sp)
            if(s.profile?.vpn!=VpnPolicy.ALL_CLIENTS) Text("VPN required is available when the profile routes all clients through VPN.",fontSize=12.sp)
        }}
    }
}
@Composable fun RouterServices(vm:RouterViewModel) {
    val s by vm.runtime.collectAsStateWithLifecycle();val p=s.profile?:s.profiles.firstOrNull()
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        item {Text("SERVICES",fontSize=28.sp,fontWeight=FontWeight.Bold);Text("Your offline world, on your private LAN.")}
        item {RouterCard("Local DNS") {Text("router.blackbox → ${p?.gateway?:"Set a profile first"}");Text("Names resolve while the BLACKBOX DNS service runs, including in offline mode.")}}
        items(p?.services.orEmpty()) {service->RouterCard(service.name) {Text("${service.name.removeSuffix(".blackbox")}.blackbox:${service.port}");Text("${p?.gateway}:${service.port} · ${if(service.udp) "UDP" else "TCP"}");Text(if(service.name.removeSuffix(".blackbox")=="dashboard") "Built-in read-only dashboard. Starts with the router." else "External server port configured. Bind your server to the private gateway address.",fontSize=12.sp)}}
        item {Text("Add service names and ports in Network. Run the actual SSH, file, game or AI server separately. BLACKBOX does not install Termux packages or expose a WAN port.")}
        item {RouterCard("Experimental backends") {Text("OpenWrt is not installed. A container alone cannot supply Android Wi-Fi/driver ownership; this backend remains unavailable until its device and rollback requirements are met.")}}
    }
}
@Composable fun RuntimeLogs(vm:RouterViewModel,showDiagnostics:()->Unit) {
    val s by vm.runtime.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item {Text("ROUTER LOG",fontSize=28.sp,fontWeight=FontWeight.Bold);TextButton(onClick=showDiagnostics){Text("Hardware command diagnostics")}}
        items(s.log) {Text(it,fontFamily=FontFamily.Monospace,fontSize=12.sp)}
        if(s.recovery) item {Button(onClick={vm.routerCommand("RECOVER")}){Text("RECOVER NETWORK")}}
    }
}
