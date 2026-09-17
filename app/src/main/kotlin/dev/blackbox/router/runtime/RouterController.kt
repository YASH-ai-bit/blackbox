package dev.blackbox.router.runtime

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dev.blackbox.core.*
import dev.blackbox.router.data.ProfileStore
import dev.blackbox.router.diagnostics.AndroidFacts
import dev.blackbox.router.root.LibsuRootExecutor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import java.util.UUID

data class LiveRouter(
    val status:RouterStatus=RouterStatus.STOPPED,val message:String="Ready",val profile:RouterProfile?=null,
    val lan:String?=null,val upstream:String?=null,val clients:List<RouterClient> = emptyList(),
    val downBps:Long=0,val upBps:Long=0,val log:List<String> = emptyList(),val recovery:Boolean=false,
    val profiles:List<RouterProfile> = emptyList(),val vpnInterfaces:List<String> = emptyList(),
    val health:DeviceFacts?=null,
)
class RouterController private constructor(private val context:Context) {
    companion object {
        @android.annotation.SuppressLint("StaticFieldLeak") // Constructor receives applicationContext only; process-lifetime router owner.
        @Volatile private var instance:RouterController?=null
        fun get(context:Context):RouterController = instance ?: synchronized(this) { instance ?: RouterController(context.applicationContext).also { instance=it } }
        const val TOOLS="/data/adb/blackbox/tools"
    }
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val lock=Mutex(); private val root=LibsuRootExecutor();private val store=ProfileStore(context)
    private val sessions=File(context.filesDir,"router-sessions").apply { mkdirs() }
    private val mutable=MutableStateFlow(LiveRouter(recovery=pending().isNotEmpty()))
    val state=mutable.asStateFlow()
    private var heartbeat:Job?=null;private var monitor:Job?=null;private var journal:RuntimeJournal?=null
    private var sessionPrefix="";private var routeBase=0;private var prefBase=0;private var ifaceIndex=""
    private var active:RouterProfile?=null; private val policies=mutableMapOf<String,ClientPolicy>()
    private var vpn:String?=null;private var dnsPid="";private var apPid="";private var upstreamGateway:String?=null
    private var dashboard:LanDashboard?=null
    private val trafficBase=mutableMapOf<String,ClientTraffic>()
    private var vpnIndex:String?=null;private var upstreamIdentity:String?=null
    init { scope.launch { runCatching { refreshProfiles() }.onFailure { message("Profile storage unavailable: ${it.message}") } } }
    private fun pending()=sessions.listFiles().orEmpty().filter { File(it,"active").exists() }
    fun inspectRecovery():String = pending().joinToString("\n\n") {dir->
        val changes=runCatching {JSONObject(File(dir,"journal.json").readText()).getJSONArray("changes")}.getOrNull()
        val labels=changes?.let {a->(maxOf(0,a.length()-12) until a.length()).map {a.getJSONObject(it).getString("label")}}.orEmpty()
        val result=File(dir,"result").takeIf(File::exists)?.readText()?.trim()?:"Interrupted / not yet recovered"
        "Session ${dir.name}\n${changes?.length()?:0} recorded steps\n$result\n\nRecent steps:\n${labels.joinToString("\n")}" 
    }.ifBlank {"No interrupted session remains. Recovery may already have completed."}
    private fun message(s:String) { mutable.update { it.copy(message=s,log=(it.log+"${java.text.SimpleDateFormat("HH:mm:ss",java.util.Locale.US).format(java.util.Date())} $s").takeLast(200)) } }
    suspend fun refreshProfiles() { mutable.update { it.copy(profiles=store.all(),recovery=pending().isNotEmpty()) } }
    suspend fun saveProfile(p:RouterProfile) { p.validate();store.save(p);refreshProfiles();message("Profile saved") }
    suspend fun deleteProfile(id:String) { check(active?.id!=id);store.delete(id);refreshProfiles() }
    fun newProfile():RouterProfile {
        val alphabet="abcdefghjkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val random=SecureRandom();return RouterProfile(password=(1..16).map { alphabet[random.nextInt(alphabet.length)] }.joinToString(""),services=listOf(LocalService("dashboard",8080)))
    }
    private suspend fun cmd(s:String, required:Boolean=true):CommandResult {
        val r=root.executeRuntime(s)
        if(required && !r.success) throw IllegalStateException("Command failed (${r.exitCode}): ${Sanitizer.clean(r.stderr).take(220)}")
        return r
    }
    private suspend fun tunnel(name:String):Boolean {
        if(!validInterface(name)) return false
        if(cmd("test -f /sys/class/net/$name/tun_flags",false).success) return true
        return Regex("(?m)^\\s+wireguard(?:\\s|$)").containsMatchIn(cmd("ip -d link show dev $name",false).stdout)
    }
    private suspend fun change(label:String,apply:String,undo:String) {
        journal!!.append(NetworkChange(label,apply,undo));message(label);cmd(apply)
    }
    suspend fun discoverVpn() {
        if(root.requestRoot()!=RootStatus.GRANTED) return
        val links=cmd("ip -d link show").stdout
        val all=NetworkParsers.ipLink(links)
        val names=all.filter { it.name.startsWith("tun") || it.name.startsWith("wg") || it.name.startsWith("ppp") }.map { it.name }
        mutable.update { it.copy(vpnInterfaces=names) }
    }
    private data class Upstream(val name:String,val dns:List<String>,val wifi:Boolean,val identity:String)
    private fun upstream():Upstream? {
        val cm=context.getSystemService(ConnectivityManager::class.java)
        val candidates=cm.allNetworks.mapNotNull { n ->
            val caps=cm.getNetworkCapabilities(n)?:return@mapNotNull null
            if(caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return@mapNotNull null
            val lp=cm.getLinkProperties(n)?:return@mapNotNull null
            Upstream(lp.interfaceName?:return@mapNotNull null,lp.dnsServers.mapNotNull { it.hostAddress },caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                "${n.networkHandle}:${lp.linkAddresses}:${lp.routes}") to (n==cm.activeNetwork)
        }
        return candidates.firstOrNull { it.second }?.first ?: candidates.firstOrNull { it.first.wifi }?.first ?: candidates.firstOrNull()?.first
    }
    suspend fun start(p:RouterProfile) = lock.withLock {
        if(mutable.value.status in setOf(RouterStatus.RUNNING,RouterStatus.DEGRADED,RouterStatus.CHECKING,RouterStatus.STARTING,RouterStatus.STOPPING,RouterStatus.RECOVERING)) return@withLock
        try {
            p.validate()
            check(pending().isEmpty()) { "Recover the interrupted session before starting" }
            mutable.update { it.copy(status=RouterStatus.CHECKING,profile=p) };message("Checking root and network ownership")
            check(root.requestRoot()==RootStatus.GRANTED) { "Grant BLACKBOX root access in your root manager" }
            check(cmd("command -v flock",false).success) { "The native backend needs flock for coordinated crash recovery" }
            val firewallVersion=cmd("iptables --version").stdout.trim()
            check(cmd("command -v iptables-save && command -v iptables-restore && command -v ip6tables && command -v ip6tables-save",false).success) { "This backend requires compatible IPv4/IPv6 xtables tools; nft-only routing is unavailable" }
            message("Firewall backend: ${Sanitizer.clean(firewallVersion)}")
            check(cmd("sha256sum $TOOLS/iw").stdout.startsWith("3314eceb85558bd5c81556b225bd4bb9a77d494d83bbc3c27a3f5eb512a937c3")) { "Verified iw tool is missing" }
            check(cmd("sha256sum $TOOLS/dnsmasq").stdout.startsWith("916c1455f639b175e34f9eb775e0d6dc1570d141c83cd50913ffca68bc92edee")) { "Verified dnsmasq tool is missing" }
            check(cmd("sha256sum $TOOLS/hostapd").stdout.startsWith("f5b6fd733b71ce7662189cd4c86dc26933225673376bdc1ccfc1ec4999befbe9")) { "Verified hostapd tool is missing" }
            val beforeWifi=NetworkParsers.iwDev(cmd("$TOOLS/iw dev").stdout)
            val beforeLinks=NetworkParsers.ipLink(cmd("ip link show").stdout)
            check(beforeWifi.none { w -> (w.type=="AP" || w.type=="P2P-GO") && beforeLinks.any { it.name==w.name && "UP" in it.flags } }) { "An existing hotspot/AP is active. Stop it first; BLACKBOX will not take it over" }
            val up=upstream()
            upstreamIdentity=up?.identity
            if(p.mode!=RouterMode.OFFLINE) check(up!=null) { "Connect an Internet upstream first" }
            if(p.mode==RouterMode.REPEATER) {
                check(up?.wifi==true) { "Repeater mode needs a connected Wi-Fi upstream" }
                check(NetworkParsers.iwPhy(cmd("$TOOLS/iw phy").stdout).any { it.staAp==Support.SUPPORTED }) { "Driver has not advertised STA + AP concurrency" }
            }
            val phyOutput=cmd("$TOOLS/iw phy").stdout
            val phy=NetworkParsers.iwPhy(phyOutput).firstOrNull { it.name==(beforeWifi.firstOrNull { w->w.name==up?.name }?.phy) && "AP" in it.modes }
                ?: NetworkParsers.iwPhy(phyOutput).firstOrNull { "AP" in it.modes }
            check(phy!=null && phy.name.matches(Regex("phy[0-9]+"))) { "No AP-capable Wi-Fi PHY found" }
            if(p.mode==RouterMode.REPEATER) check(phy.staAp==Support.SUPPORTED) { "The upstream Wi-Fi PHY does not advertise STA + AP concurrency" }
            val channelEvidence=phyOutput.substringAfter("Wiphy ${phy.name}").substringBefore("Wiphy ")
            val sta=beforeWifi.firstOrNull { it.type=="managed" && it.phy==phy.name && it.channel!=null }
            val channel=if(sta!=null && phy.sameChannelRequired!=false) checkNotNull(sta.channel) { "Cannot determine required upstream channel" }
                else listOf(6,1,11).firstOrNull { ch->channelEvidence.lines().any { "[$ch]" in it && "MHz" in it && "disabled" !in it && "no IR" !in it } } ?: error("No usable 2.4 GHz channel advertised")
            check(channel !in 52..144) { "This backend does not yet validate DFS/CAC. Use a non-DFS upstream channel on same-channel hardware" }
            vpn=p.vpnInterface.takeIf { p.vpn==VpnPolicy.ALL_CLIENTS && validInterface(it) && cmd("test -e /sys/class/net/$it",false).success }
            if(p.vpn==VpnPolicy.ALL_CLIENTS) check(vpn!=null && tunnel(vpn!!)) { "Select an existing TUN or WireGuard interface; a physical upstream is not a VPN" }
            vpnIndex=vpn?.let {cmd("cat /sys/class/net/$it/ifindex").stdout.trim()}
            val routes=cmd("ip -4 route show table all").stdout
            check(NetworkParsers.ipRoute(routes).none { it.destination!="default" && Ipv4.overlaps(p.subnet,it.destination) }) { "Private subnet overlaps an existing network; choose another subnet" }
            val before=JSONObject().put("interfaces",cmd("ip addr show").stdout).put("routes",routes).put("rules",cmd("ip rule show").stdout)
                .put("ipv6rules",cmd("ip -6 rule show").stdout).put("firewall",cmd("iptables-save").stdout).put("firewall6",cmd("ip6tables-save").stdout)
                .put("forwarding",cmd("cat /proc/sys/net/ipv4/ip_forward").stdout.trim()).put("dns",up?.dns?.joinToString(",").orEmpty())
                .put("forwarding6",cmd("cat /proc/sys/net/ipv6/conf/all/forwarding").stdout.trim())
            if(p.mode!=RouterMode.OFFLINE && before.getString("forwarding")=="0") check(before.getString("forwarding6")=="0") { "Mixed forwarding state needs an explicitly supported netd backend" }
            val id=UUID.randomUUID().toString().replace("-","").take(8);sessionPrefix="BB_${id}";routeBase=30000+(id.take(4).toInt(16)%10000)*2;prefBase=8000+(id.take(3).toInt(16)%1000)*2
            for(t in routeBase..routeBase+1) check(cmd("ip route show table $t",false).stdout.isBlank()) { "Routing table collision" }
            check(NetworkParsers.ipRule(before.getString("rules")).none { it.priority in prefBase..prefBase+3 }) { "Routing priority collision" }
            journal=RuntimeJournal(File(sessions,id));val j=journal!!;j.write("snapshot.json",before.toString(2));j.heartbeat()
            j.append(NetworkChange("session","true","true"));j.write("watchdog.sh",j.watchdog());j.write("active","starting")
            cmd("chmod 700 ${shellQuote(j.directory.absolutePath)}; setsid sh ${shellQuote(File(j.directory,"watchdog.sh").path)} </dev/null >${shellQuote(File(j.directory,"watchdog.log").path)} 2>&1 &")
            heartbeat=scope.launch { while(isActive) { j.heartbeat();delay(5000) } }
            val remembered=store.clients(p.id)
            active=p;policies.clear();policies.putAll(remembered.associate {it.mac to it.policy});trafficBase.clear()
            mutable.update { it.copy(status=RouterStatus.STARTING,upstream=up?.name,clients=remembered,health=AndroidFacts(context).read()) }
            val l="bb${id.take(8)}";check(!cmd("test -e /sys/class/net/$l",false).success) { "Interface name collision" }
            val identityFile=shellQuote(File(j.directory,"ifindex").path)
            val owns="[ -s $identityFile ] && [ \"\$(cat /sys/class/net/$l/ifindex 2>/dev/null)\" = \"\$(cat $identityFile)\" ]"
            change("Creating owned AP interface","$TOOLS/iw phy ${phy.name} interface add $l type __ap && cat /sys/class/net/$l/ifindex > $identityFile",
                "if [ -e /sys/class/net/$l ]; then $owns && $TOOLS/iw dev $l del; fi")
            ifaceIndex=cmd("cat /sys/class/net/$l/ifindex").stdout.trim()
            mutable.update { it.copy(lan=l) };j.write("identity.json",JSONObject().put("lan",l).put("ifindex",ifaceIndex).put("prefix",sessionPrefix).put("table",routeBase).put("priority",prefBase).toString())
            j.write("verify.sh",buildString {
                appendLine("#!/system/bin/sh\nfailed=0\ntest ! -e /sys/class/net/$l || failed=1")
                for(tool in listOf("iptables-save","ip6tables-save")) appendLine("$tool > firewall-check.tmp || exit 1\nif grep -F $sessionPrefix firewall-check.tmp >/dev/null; then failed=1; fi")
                for(t in routeBase..routeBase+1) appendLine("test -z \"\$(ip route show table $t 2>/dev/null)\" || failed=1")
                appendLine("ip rule show > rule-check.tmp || exit 1")
                for(priority in prefBase..prefBase+3) appendLine("if grep -E '^$priority:' rule-check.tmp >/dev/null; then failed=1; fi")
                appendLine("rm -f firewall-check.tmp rule-check.tmp\nexit \$failed")
            })
            val apAddresses=cmd("ip -4 addr show dev $l").stdout
            j.write("initial-ap-addresses.txt",apAddresses)
            j.write("initial-ap-sockets.txt",cmd("ss -luntp",false).stdout)
            check(apAddresses.lines().none { it.trim().startsWith("inet ") }) { "Android assigned its own LAN address; refusing DHCP conflict" }
            setupFirewall(p,l,up?.name)
            change("Assigning private gateway","ip addr add ${p.gateway}/${p.prefix} dev $l","if ip addr show dev $l 2>/dev/null | grep -F ${shellQuote("inet ${p.gateway}/${p.prefix} ")} >/dev/null; then $owns && ip addr del ${p.gateway}/${p.prefix} dev $l; fi")
            val apConfig=File(j.directory,"hostapd.conf");val apPidFile=File(j.directory,"hostapd.pid")
            j.write("hostapd.conf",HostapdConfig.generate(p,l,channel,j.directory.path))
            change("Starting secure access point on channel $channel","chmod 600 ${shellQuote(apConfig.path)} && $TOOLS/hostapd -B -t -f ${shellQuote(File(j.directory,"hostapd.log").path)} -P ${shellQuote(apPidFile.path)} ${shellQuote(apConfig.path)}",daemonUndo(apPidFile.path,apConfig.path))
            apPid=cmd("cat ${shellQuote(apPidFile.path)}").stdout.trim()
            check(apPid.toIntOrNull()!=null && cmd("kill -0 $apPid",false).success) { "Access point daemon did not stay alive" }
            val apLog=cmd("cat ${shellQuote(File(j.directory,"hostapd.log").path)}",false).stdout
            check("AP-ENABLED" in apLog) { "Access point not enabled: ${Sanitizer.clean(apLog.takeLast(700))}" }
            setupRouting(p,l,up,routes)
            val resolverInterface=if(p.vpn==VpnPolicy.ALL_CLIENTS) vpn else up?.name
            val config=DnsmasqConfig.generate(p,l,j.directory.absolutePath,up?.dns.orEmpty()).let { c -> c.lines().joinToString("\n") { if(it.startsWith("server=") && resolverInterface!=null) "$it@$resolverInterface" else it } }
            j.write("dnsmasq.conf",config);cmd("$TOOLS/dnsmasq --test --conf-file=${shellQuote(File(j.directory,"dnsmasq.conf").path)}")
            val pid=File(j.directory,"dnsmasq.pid").path;val cfg=File(j.directory,"dnsmasq.conf").path
            val kill=daemonUndo(pid,cfg)
            // VPN resolver uses an isolated effective group so its OUTPUT guard cannot affect Android apps/netd.
            val resolverGroup=60000+(id.take(3).toInt(16)%1000)
            if(p.vpn==VpnPolicy.ALL_CLIENTS) {
                check(!Regex("(?m)^\\s*$resolverGroup\\s*$").containsMatchIn(cmd("ps -A -o GID").stdout)) { "Resolver group collision" }
                val outChain="${sessionPrefix}_O"
                change("Creating VPN resolver boundary","iptables -w 5 -N $outChain","if iptables -w 5 -S $outChain >/dev/null 2>&1; then iptables -w 5 -F $outChain && iptables -w 5 -X $outChain; fi")
                for(out in listOf(l,"lo",vpn!!)) cmd("iptables -w 5 -A $outChain -o $out -j RETURN")
                cmd("iptables -w 5 -A $outChain -j DROP")
                val spec="-m owner --gid-owner $resolverGroup -j $outChain"
                change("Installing VPN DNS kill switch","iptables -w 5 -I OUTPUT 1 $spec","if iptables -w 5 -C OUTPUT $spec 2>/dev/null; then iptables -w 5 -D OUTPUT $spec; fi")
            }
            val dnsCommand="$TOOLS/dnsmasq --conf-file=${shellQuote(cfg)}"
            change("Starting private DHCP and DNS",if(p.vpn==VpnPolicy.ALL_CLIENTS) "su -g $resolverGroup -c ${shellQuote(dnsCommand)}" else dnsCommand,kill)
            dnsPid=cmd("cat ${shellQuote(pid)}").stdout.trim();check(dnsPid.toIntOrNull()!=null && cmd("kill -0 $dnsPid",false).success) { "DHCP/DNS process did not stay alive" }
            if(p.vpn==VpnPolicy.ALL_CLIENTS) check(Regex("(?m)^Gid:\\s+$resolverGroup\\s+$resolverGroup\\s").containsMatchIn(cmd("cat /proc/$dnsPid/status").stdout)) { "Root manager cannot isolate VPN DNS credentials; no VPN routing started" }
            if(p.mode!=RouterMode.OFFLINE && before.getString("forwarding")=="0") change("Requesting forwarding from Android netd",
                "ndc ipfwd enable blackbox_$id | grep '^200 ' && test \"\$(cat /proc/sys/net/ipv4/ip_forward)\" = 1",
                "ndc ipfwd disable blackbox_$id | grep '^200 '")
            // Own route and DNS tests; downstream end-to-end validation remains a separate test.
            check(cmd("ping -c 1 -W 2 ${p.gateway}",false).success) { "Private gateway did not answer" }
            p.services.firstOrNull {it.name.removeSuffix(".blackbox")=="dashboard"}?.let { service ->
                dashboard=LanDashboard(p.gateway,service.port){mutable.value};message("Offline dashboard ready at dashboard.blackbox:${service.port}")
            }
            mutable.update { it.copy(status=RouterStatus.RUNNING,recovery=false) };message("Router running · downstream connectivity needs a client test")
            monitor=scope.launch { monitor(p,l,up?.name) }
        } catch(e:Exception) {
            val reason=if(e is CancellationException) "Startup cancelled" else e.message?:"Startup interrupted"
            message(reason)
            withContext(NonCancellable) { cleanup() }
            if(e is CancellationException) throw e
            mutable.update { it.copy(status=RouterStatus.ERROR,message=reason,recovery=pending().isNotEmpty()) }
        }
    }
    private fun daemonUndo(pid:String,config:String):String = "if [ -f ${shellQuote(pid)} ]; then p=\$(cat ${shellQuote(pid)}); case \"\$p\" in ''|*[!0-9]*) exit 1;; esac; if [ -e /proc/\$p/cmdline ] && tr '\\000' ' ' </proc/\$p/cmdline | grep -F ${shellQuote(config)} >/dev/null; then kill \$p; fi; fi"
    private suspend fun setupFirewall(p:RouterProfile,l:String,up:String?) {
        val prefix=sessionPrefix
        for((tool,chain) in listOf("iptables" to "${prefix}_C","iptables" to "${prefix}_F","iptables" to "${prefix}_I","ip6tables" to "${prefix}_6")) {
            change("Creating private firewall chain","$tool -w 5 -N $chain","if $tool -w 5 -S $chain >/dev/null 2>&1; then $tool -w 5 -F $chain && $tool -w 5 -X $chain; fi")
        }
        cmd("ip6tables -w 5 -A ${prefix}_6 -j DROP")
        cmd("iptables -w 5 -A ${prefix}_F -j DROP");cmd("iptables -w 5 -A ${prefix}_I -j DROP")
        for((tool,hook,spec) in listOf(Triple("iptables","FORWARD","-i $l -j ${prefix}_F"),Triple("iptables","FORWARD","-o $l -j ${prefix}_F"),Triple("iptables","INPUT","-i $l -j ${prefix}_I"),Triple("ip6tables","FORWARD","-i $l -j ${prefix}_6"),Triple("ip6tables","FORWARD","-o $l -j ${prefix}_6"),Triple("ip6tables","INPUT","-i $l -j ${prefix}_6"),Triple("ip6tables","OUTPUT","-o $l -j ${prefix}_6"))) {
            change("Attaching scoped firewall","$tool -w 5 -I $hook 1 $spec","if $tool -w 5 -C $hook $spec 2>/dev/null; then $tool -w 5 -D $hook $spec; fi")
        }
        // Update both chains in one xtables restore transaction, retaining unrelated Android rules.
        updateFilter(p,l,up)
        val nat="${prefix}_N"
        change("Creating private NAT chain","iptables -w 5 -t nat -N $nat","if iptables -w 5 -t nat -S $nat >/dev/null 2>&1; then iptables -w 5 -t nat -F $nat && iptables -w 5 -t nat -X $nat; fi")
        if(p.mode!=RouterMode.OFFLINE) for(out in (if(p.vpn==VpnPolicy.ALL_CLIENTS) listOfNotNull(vpn) else listOfNotNull(up,vpn)).distinct()) cmd("iptables -w 5 -t nat -A $nat -s ${p.subnet} -o $out -j MASQUERADE")
        change("Attaching private NAT","iptables -w 5 -t nat -I POSTROUTING 1 -s ${p.subnet} -j $nat","if iptables -w 5 -t nat -C POSTROUTING -s ${p.subnet} -j $nat 2>/dev/null; then iptables -w 5 -t nat -D POSTROUTING -s ${p.subnet} -j $nat; fi")
        val dns="${prefix}_D"
        change("Creating DNS enforcement chain","iptables -w 5 -t nat -N $dns","if iptables -w 5 -t nat -S $dns >/dev/null 2>&1; then iptables -w 5 -t nat -F $dns && iptables -w 5 -t nat -X $dns; fi")
        for(proto in listOf("udp","tcp")) cmd("iptables -w 5 -t nat -A $dns -p $proto --dport 53 -j DNAT --to-destination ${p.gateway}:53")
        change("Enforcing LAN DNS","iptables -w 5 -t nat -I PREROUTING 1 -i $l -j $dns","if iptables -w 5 -t nat -C PREROUTING -i $l -j $dns 2>/dev/null; then iptables -w 5 -t nat -D PREROUTING -i $l -j $dns; fi")
        // Explicitly published service ports are LAN/localhost-only, even if Termux binds 0.0.0.0.
        for(tool in listOf("iptables","ip6tables")) {
            if(p.services.isEmpty()) continue
            val service="${prefix}_S"
            change("Creating LAN-only service boundary","$tool -w 5 -N $service","if $tool -w 5 -S $service >/dev/null 2>&1; then $tool -w 5 -F $service && $tool -w 5 -X $service; fi")
            cmd("$tool -w 5 -A $service -i lo -j RETURN")
            if(tool=="iptables") cmd("$tool -w 5 -A $service -i $l -j RETURN")
            cmd("$tool -w 5 -A $service -j DROP")
            for(s in p.services) {
                val spec="-p ${if(s.udp) "udp" else "tcp"} --dport ${s.port} -j $service"
                change("Restricting ${s.name} to the private LAN","$tool -w 5 -I INPUT 1 $spec","if $tool -w 5 -C INPUT $spec 2>/dev/null; then $tool -w 5 -D INPUT $spec; fi")
            }
        }
    }
    private suspend fun updateFilter(p:RouterProfile,l:String,up:String?) {
        val f="${sessionPrefix}_F";val i="${sessionPrefix}_I";val clients=mutable.value.clients.map { it.copy(policy=policies[it.mac]?:ClientPolicy.NORMAL) }
        val c="${sessionPrefix}_C"
        val counters=TrafficCounters.parse(cmd("iptables -w 5 -nvx -L $c").stdout)
        val countRules=clients.flatMap {client->buildList {
            val mac=client.mac.replace(":","")
            add("-A $c -i $l -m mac --mac-source ${client.mac} -m comment --comment bb_up_$mac")
            if(client.leaseCurrent) add("-A $c -o $l -d ${client.ip} -m comment --comment bb_down_$mac")
        }}+"-A $c -j ACCEPT"
        val rules="*filter\n-F $f\n-F $i\n-F $c\n"+(FirewallPlan.filter(f,p,l,up,vpn,clients,c)+FirewallPlan.input(i,p,l,clients)+countRules).joinToString("\n")+"\nCOMMIT\n"
        val file=File(journal!!.directory,"filter.rules");journal!!.write(file.name,rules)
        cmd("iptables-restore -w 5 --noflush < ${shellQuote(file.path)}")
        counters.forEach {(mac,bytes)->trafficBase[mac]=(trafficBase[mac]?:ClientTraffic())+bytes}
    }
    private suspend fun setupRouting(p:RouterProfile,l:String,up:Upstream?,routes:String) {
        // A dedicated LAN table supplies phone-to-LAN return routes; iif rules select upstream only for clients.
        val table=routeBase;val pref=prefBase
        change("Creating private LAN route","ip route add ${p.subnet} dev $l table $table","ip route del ${p.subnet} dev $l table $table 2>/dev/null || test -z \"\$(ip route show table $table 2>/dev/null)\"")
        change("Routing phone services to LAN","ip rule add pref $pref to ${p.subnet} lookup $table","if ip rule show | grep -E '^$pref:' >/dev/null; then ip rule del pref $pref to ${p.subnet} lookup $table; fi")
        val out=if(p.vpn==VpnPolicy.ALL_CLIENTS) vpn else up?.name
        if(p.mode!=RouterMode.OFFLINE && out!=null) {
            val default=NetworkParsers.ipRoute(routes).firstOrNull { it.device==out && it.destination=="default" }
            val gw=default?.gateway?.takeIf { runCatching { Ipv4.number(it) }.isSuccess };upstreamGateway=gw
            val via=if(gw==null) "" else "via $gw "
            change("Creating private upstream route","ip route add default ${via}dev $out ${if(gw!=null) "onlink " else ""}table ${table+1}","ip route del default ${via}dev $out table ${table+1} 2>/dev/null || test -z \"\$(ip route show table ${table+1} 2>/dev/null)\"")
            change("Selecting LAN upstream","ip rule add pref ${pref+1} iif $l lookup ${table+1}","if ip rule show | grep -E '^${pref+1}:' >/dev/null; then ip rule del pref ${pref+1} iif $l lookup ${table+1}; fi")
            change("Binding resolver routing to selected upstream","ip rule add pref ${pref+2} iif lo oif $out lookup ${table+1}","if ip rule show | grep -E '^${pref+2}:' >/dev/null; then ip rule del pref ${pref+2} iif lo oif $out lookup ${table+1}; fi")
        }
        change("Installing no-fallback routing guard","ip rule add pref ${pref+3} iif $l unreachable","if ip rule show | grep -E '^${pref+3}:' >/dev/null; then ip rule del pref ${pref+3} iif $l unreachable; fi")
    }
    private suspend fun monitor(p:RouterProfile,l:String,up:String?) {
        var previous=0L;var rx=0L;var tx=0L;var persisted=0L;var healthUpdated=0L
        while(currentCoroutineContext().isActive) {
            delay(if(p.balanced) 5000 else 2000)
            try {
                val valid=cmd("cat /sys/class/net/$l/ifindex",false).stdout.trim()==ifaceIndex
                check(valid) { "Hotspot interface disappeared or changed; recover/restart required" }
                check(cmd("kill -0 $dnsPid",false).success) { "DHCP/DNS stopped; recover/restart required" }
                check(cmd("kill -0 $apPid",false).success) { "Access point daemon stopped; recover/restart required" }
                val neighbors=cmd("ip neigh show dev $l").stdout
                val leases=cmd("cat ${shellQuote(File(journal!!.directory,"leases").path)}",false).stdout
                val now=System.currentTimeMillis();val old=mutable.value.clients.associateBy { it.mac }
                val live=ClientParser.leases(leases,neighbors,now).filter {it.leaseUntil==0L || it.leaseUntil>now/1000}.map { c -> c.copy(firstSeen=old[c.mac]?.firstSeen?:now,lastSeen=if(c.online) now else old[c.mac]?.lastSeen?:now,policy=policies[c.mac]?:ClientPolicy.NORMAL) }
                val bytes=cmd("cat /sys/class/net/$l/statistics/rx_bytes /sys/class/net/$l/statistics/tx_bytes").stdout.lines().mapNotNull(String::toLongOrNull)
                val seconds=if(previous==0L) 0.0 else (now-previous)/1000.0
                val vpnAlive=vpn?.let { cmd("cat /sys/class/net/$it/ifindex",false).stdout.trim()==vpnIndex }?:false
                val upstreamAlive=p.mode==RouterMode.OFFLINE || (if(p.vpn==VpnPolicy.ALL_CLIENTS) vpnAlive else up!=null && cmd("test -e /sys/class/net/$up",false).success && upstream()?.identity==upstreamIdentity)
                val status=if(upstreamAlive) RouterStatus.RUNNING else RouterStatus.DEGRADED
                lock.withLock {
                if(active?.id!=p.id || journal==null)return
                val counters=TrafficCounters.parse(cmd("iptables -w 5 -nvx -L ${sessionPrefix}_C").stdout)
                val clients=(live+old.values.filter { c->live.none { it.mac==c.mac } }.map { it.copy(online=false,leaseCurrent=false) }).take(128).map { client ->
                    val bytes=(trafficBase[client.mac]?:ClientTraffic())+(counters[client.mac]?:ClientTraffic())
                    val oldClient=old[client.mac];val oldDown=oldClient?.receivedBytes;val oldUp=oldClient?.sentBytes
                    client.copy(receivedBytes=bytes.down,sentBytes=bytes.up,
                        receivedBps=if(seconds>0 && oldDown!=null) ((bytes.down-oldDown).coerceAtLeast(0)/seconds).toLong() else 0,
                        sentBps=if(seconds>0 && oldUp!=null) ((bytes.up-oldUp).coerceAtLeast(0)/seconds).toLong() else 0)
                }
                mutable.update { it.copy(clients=clients,status=status,
                    message=if(status==RouterStatus.DEGRADED) "Upstream changed or unavailable · stop and restart to select it again" else "Router running",downBps=if(seconds>0 && bytes.size==2) ((bytes[1]-tx).coerceAtLeast(0)/seconds).toLong() else 0,
                    upBps=if(seconds>0 && bytes.size==2) ((bytes[0]-rx).coerceAtLeast(0)/seconds).toLong() else 0) }
                if(clients.map {Triple(it.mac,it.ip,it.leaseCurrent)}!=old.values.map {Triple(it.mac,it.ip,it.leaseCurrent)}) updateFilter(p,l,up)
                if(now-persisted>=60000 || clients.any {it.mac !in old}) {store.saveClients(p.id,clients);persisted=now}
                if(now-healthUpdated>=30000) {mutable.update {it.copy(health=AndroidFacts(context).read())};healthUpdated=now}
                }
                if(bytes.size==2) {rx=bytes[0];tx=bytes[1]};previous=now
            } catch(e:Exception) {
                if(e is CancellationException) throw e
                message(e.message?:"Monitoring failed");mutable.update { it.copy(status=RouterStatus.DEGRADED) }
                // Stop cleanly on loss of owned infrastructure; a network switch requires explicit restart.
                scope.launch { stop() };return
            }
        }
    }
    suspend fun setPolicy(mac:String,policy:ClientPolicy) = lock.withLock {
        val p=checkNotNull(active);val l=checkNotNull(mutable.value.lan)
        check(mutable.value.clients.any { it.mac==mac }) { "Client lease no longer known" }
        check(policy!=ClientPolicy.VPN_REQUIRED || p.vpn==VpnPolicy.ALL_CLIENTS) { "Enable VPN ALL CLIENTS in the active profile first" }
        val previous=policies[mac]
        policies[mac]=policy
        try {updateFilter(p,l,mutable.value.upstream)} catch(e:Exception) {if(previous==null)policies.remove(mac) else policies[mac]=previous;throw e}
        mutable.update { it.copy(clients=it.clients.map { c->if(c.mac==mac)c.copy(policy=policy) else c }) };message("Client policy updated")
        store.saveClients(p.id,mutable.value.clients)
    }
    suspend fun stop() = lock.withLock { mutable.update { it.copy(status=RouterStatus.STOPPING) };withContext(NonCancellable) {cleanup()} }
    suspend fun recover() = lock.withLock {
        mutable.update { it.copy(status=RouterStatus.RECOVERING) }
        if(root.requestRoot()!=RootStatus.GRANTED) {message("Recovery needs root");mutable.update { it.copy(status=RouterStatus.ERROR,recovery=true) };return@withLock}
        withContext(NonCancellable) { cleanup() }
    }
    private suspend fun cleanup() {
        monitor?.cancelAndJoin();monitor=null
        dashboard?.close();dashboard=null
        active?.let {p ->runCatching {store.saveClients(p.id,mutable.value.clients)}.onFailure {message("Client history could not be saved")}}
        var clean=true
        // Cancelling/timing out a command destroys its private shell. Rollback must obtain a fresh verified shell.
        if(pending().isNotEmpty() && !root.isAvailable() && root.requestRoot()!=RootStatus.GRANTED) clean=false
        for(dir in pending()) {
            val recovery=File(dir,"recover.sh")
            if(!recovery.exists() || !cmd("sh ${shellQuote(recovery.path)}",false).success) clean=false
        }
        heartbeat?.cancel();heartbeat=null;active=null;journal=null
        mutable.update { it.copy(status=if(clean) RouterStatus.STOPPED else RouterStatus.ERROR,recovery=!clean,lan=null,downBps=0,upBps=0,clients=emptyList()) }
        message(if(clean) "Router stopped · owned changes removed" else "Recovery incomplete · inspect logs and retry recovery")
    }
}
