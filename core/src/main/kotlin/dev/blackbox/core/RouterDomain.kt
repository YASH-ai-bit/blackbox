package dev.blackbox.core

import java.util.UUID

enum class RouterMode { OFFLINE, INTERNET, REPEATER }
enum class VpnPolicy { OFF, PHONE_ONLY, ALL_CLIENTS }
enum class DnsMode { SYSTEM, CUSTOM, AD_BLOCK, LOCAL }
enum class ClientPolicy { NORMAL, LAN_ONLY, VPN_REQUIRED, PAUSED }
data class LocalService(val name: String, val port: Int, val udp: Boolean = false)
data class RouterProfile(
    val id: String = UUID.randomUUID().toString(), val name: String = "Travel",
    val ssid: String = "BLACKBOX", val password: String,
    val mode: RouterMode = RouterMode.OFFLINE, val gateway: String = "192.168.50.1",
    val prefix: Int = 24, val first: String = "192.168.50.10", val last: String = "192.168.50.200",
    val dnsMode: DnsMode = DnsMode.SYSTEM, val dns: List<String> = emptyList(),
    val blockedDomains: List<String> = emptyList(), val vpn: VpnPolicy = VpnPolicy.OFF,
    val vpnInterface: String = "", val wpa3: Boolean = false, val isolation: Boolean = false,
    val services: List<LocalService> = emptyList(), val balanced: Boolean = true,
) {
    val subnet get() = Ipv4.subnet(gateway, prefix)
    fun validate() {
        require(name.isNotBlank() && name.length <= 64) { "Enter a profile name (up to 64 characters)" }
        require(ssid.toByteArray().size in 1..32 && ssid.none { it.code < 32 || it.code == 127 }) { "SSID must be 1–32 UTF-8 bytes without control characters" }
        require(password.length in 8..63 && password.all { it.code in 32..126 }) { "Password must be 8–63 printable ASCII characters" }
        require(prefix in 24..28) { "Choose a /24 to /28 private subnet" }
        val g = Ipv4.number(gateway); val a = Ipv4.number(first); val b = Ipv4.number(last)
        require(Ipv4.private(g)) { "Gateway must be a private IPv4 address" }
        val net = Ipv4.network(g, prefix); val end = net + (1L shl (32-prefix)) - 1
        require(g in net+1 until end && a in net+1 until end && b in a until end && g !in a..b) { "DHCP range and gateway must be distinct usable addresses in the subnet" }
        require(dns.size <= 4 && dns.all { runCatching { Ipv4.number(it) != 0L }.getOrDefault(false) }) { "Use up to four IPv4 DNS servers" }
        require(gateway !in dns) { "The gateway cannot forward DNS queries back to itself" }
        require(dnsMode !in setOf(DnsMode.CUSTOM, DnsMode.AD_BLOCK) || dns.isNotEmpty()) { "Select DNS servers for custom or ad-block mode" }
        require(blockedDomains.size <= 10000 && blockedDomains.all(::validDomain)) { "Use valid DNS names, one per line (maximum 10,000)" }
        require(services.size <= 32 && services.all { validDomain(it.name) && it.port in 1024..65535 }) { "Service names must be DNS labels; ports must be 1024–65535" }
        require(services.map { it.name.lowercase().removeSuffix(".blackbox") }.distinct().size == services.size) { "Service names must be unique" }
        require(services.none {it.name.lowercase().removeSuffix(".blackbox")=="router"}) { "router.blackbox is reserved for the gateway" }
        require(services.all {it.name==it.name.lowercase()}) { "Use lowercase service names" }
        require(services.map { it.port to it.udp }.distinct().size == services.size) { "Each service needs a distinct port and protocol" }
        require(services.none { it.name.removeSuffix(".blackbox")=="dashboard" && it.udp }) { "The built-in dashboard requires TCP" }
        if (vpn == VpnPolicy.ALL_CLIENTS) require(validInterface(vpnInterface)) { "Select an existing VPN interface" }
    }
}
fun validDomain(s: String) = s.length in 1..253 && s.split('.').all { it.length in 1..63 && Regex("[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?").matches(it) }
fun validInterface(s: String) = Regex("[A-Za-z0-9_][A-Za-z0-9_.-]{0,14}").matches(s)
fun shellQuote(s: String): String { require('\u0000' !in s); return "'" + s.replace("'", "'\"'\"'") + "'" }
object Ipv4 {
    fun number(s: String): Long {
        val parts=s.split('.'); require(parts.size==4 && parts.all { it.matches(Regex("0|[1-9][0-9]{0,2}")) && it.toInt() in 0..255 }) { "Invalid IPv4 address: $s" }
        return parts.fold(0L) { a,p -> (a shl 8) or p.toLong() }
    }
    fun text(n: Long) = (3 downTo 0).joinToString(".") { ((n shr (it*8)) and 255).toString() }
    fun network(n: Long, prefix: Int): Long { require(prefix in 0..32); return n and ((0xffffffffL shl (32-prefix)) and 0xffffffffL) }
    fun subnet(ip: String, prefix: Int) = "${text(network(number(ip),prefix))}/$prefix"
    fun private(n: Long) = (n shr 24 == 10L) || (n shr 20 == 0xac1L) || (n shr 16 == 0xc0a8L)
    fun overlaps(a: String, b: String): Boolean = runCatching {
        val ap=a.substringAfter('/',"32").toInt(); val bp=b.substringAfter('/',"32").toInt()
        network(number(a.substringBefore('/')),minOf(ap,bp)) == network(number(b.substringBefore('/')),minOf(ap,bp))
    }.getOrDefault(false)
}
data class RouterClient(val mac: String, val ip: String, val hostname: String?, val leaseUntil: Long,
    val online: Boolean, val firstSeen: Long, val lastSeen: Long, val policy: ClientPolicy = ClientPolicy.NORMAL,
    val receivedBytes: Long? = null, val sentBytes: Long? = null, val leaseCurrent:Boolean=true,
    val receivedBps:Long?=null,val sentBps:Long?=null)
object ClientParser {
    fun leases(text: String, neighbors: String, now: Long): List<RouterClient> = text.lineSequence().mapNotNull { line ->
        val p=line.trim().split(Regex("\\s+")); if(p.size<4) return@mapNotNull null
        if(!Regex("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}").matches(p[1]) || runCatching { Ipv4.number(p[2]) }.isFailure) return@mapNotNull null
        val online=neighbors.lines().any { it.startsWith(p[2]+" ") && p[1].lowercase() in it.lowercase() && listOf("REACHABLE","DELAY","PROBE","STALE").any(it::contains) }
        RouterClient(p[1].lowercase(),p[2],p[3].takeUnless { it=="*" },p[0].toLongOrNull()?:0,online,now,now)
    }.toList()
}
object DnsmasqConfig {
    fun generate(p: RouterProfile, lan: String, directory: String, systemDns: List<String>): String {
        p.validate(); require(validInterface(lan)); require(directory.matches(Regex("/[A-Za-z0-9_./-]+")))
        val resolvers=if(p.dnsMode==DnsMode.SYSTEM) systemDns.filter { runCatching { Ipv4.number(it) }.isSuccess } else p.dns
        require(p.mode==RouterMode.OFFLINE || p.dnsMode==DnsMode.LOCAL || resolvers.isNotEmpty()) { "No IPv4 upstream DNS available; configure custom DNS" }
        return buildString {
            // The pinned Android/musl build explicitly supports root without a passwd database.
            appendLine("user=root")
            appendLine("interface=$lan\nbind-interfaces\nlisten-address=${p.gateway}\nexcept-interface=lo")
            appendLine("no-resolv\nno-hosts\ndomain-needed\nbogus-priv\nstop-dns-rebind\nlocal=/blackbox/\ndomain=blackbox\nexpand-hosts\ncache-size=1000")
            appendLine("dhcp-authoritative\ndhcp-range=${p.first},${p.last},255.255.255.${256-(1 shl (32-p.prefix))},12h")
            appendLine("dhcp-option=3,${p.gateway}\ndhcp-option=6,${p.gateway}\ndhcp-option=15,blackbox")
            appendLine("dhcp-leasefile=$directory/leases\npid-file=$directory/dnsmasq.pid\nlog-facility=$directory/dnsmasq.log")
            appendLine("host-record=router.blackbox,${p.gateway}")
            p.services.forEach { appendLine("host-record=${it.name.removeSuffix(".blackbox")}.blackbox,${p.gateway}") }
            if(p.mode!=RouterMode.OFFLINE && p.dnsMode!=DnsMode.LOCAL) resolvers.forEach { appendLine("server=$it") }
            if(p.dnsMode==DnsMode.AD_BLOCK) p.blockedDomains.forEach { appendLine("address=/$it/\nlocal=/$it/") }
        }
    }
}
data class NetworkChange(val label: String, val apply: String, val undo: String)
interface ChangeJournal { suspend fun append(change: NetworkChange); suspend fun completed(); suspend fun failed(message: String) }
/** Journal intent before executing; include a partially applied failing step in rollback. */
class NetworkTransaction(private val journal: ChangeJournal, private val run: suspend (String)->Boolean) {
    private val changes=mutableListOf<NetworkChange>()
    suspend fun apply(change: NetworkChange) { journal.append(change); changes+=change; check(run(change.apply)) { change.label } }
    suspend fun rollback(): Boolean {
        var clean=true
        for(c in changes.asReversed()) if(!run(c.undo)) clean=false
        if(clean) journal.completed() else journal.failed("Recovery incomplete")
        return clean
    }
}
