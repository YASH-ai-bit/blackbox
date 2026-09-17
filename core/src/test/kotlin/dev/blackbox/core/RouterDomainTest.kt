package dev.blackbox.core

import kotlinx.coroutines.test.runTest
import kotlin.test.*

class RouterDomainTest {
    private val profile=RouterProfile(password="test password")
    @Test fun subnetRangeRejectsGatewayAndInjection() {
        profile.validate()
        assertFails { profile.copy(first=profile.gateway).validate() }
        assertFails { profile.copy(dnsMode=DnsMode.CUSTOM,dns=listOf("1.1.1.1\nserver=evil")).validate() }
        assertFails { profile.copy(ssid="test\nother").validate() }
        assertTrue(Ipv4.overlaps("192.168.50.0/24","192.168.0.0/16"))
        assertFalse(Ipv4.overlaps("192.168.50.0/24","192.168.51.0/24"))
    }
    @Test fun offlineDnsNeverContainsUpstream() {
        val c=DnsmasqConfig.generate(profile,"ap7","/data/app/session",listOf("1.1.1.1"))
        assertFalse(c.contains("server="));assertTrue(c.contains("host-record=router.blackbox,192.168.50.1"))
    }
    @Test fun vpnRequiredHasNoDirectFallback() {
        val p=profile.copy(mode=RouterMode.INTERNET,vpn=VpnPolicy.ALL_CLIENTS,vpnInterface="tun7")
        val gone=FirewallPlan.filter("BB_aabbccdd_F",p,"ap7","wlan8",null,emptyList())
        assertFalse(gone.any { "ACCEPT" in it }); assertEquals("-A BB_aabbccdd_F -j DROP",gone.last())
        val live=FirewallPlan.filter("BB_aabbccdd_F",p,"ap7","wlan8","tun7",emptyList())
        assertFalse(live.any { "wlan8" in it }); assertTrue(live.any { "-o tun7" in it })
    }
    @Test fun blockedClientPrecedesEstablishedTraffic() {
        val c=RouterClient("00:11:22:33:44:55","192.168.50.12",null,0,true,0,0,ClientPolicy.LAN_ONLY)
        val r=FirewallPlan.filter("BB_aabbccdd_F",profile.copy(mode=RouterMode.INTERNET),"ap7","wlan8",null,listOf(c))
        assertTrue(r.indexOfFirst { c.mac in it } < r.indexOfFirst { "ACCEPT" in it })
    }
    @Test fun failedPartialStepIsRolledBackAfterJournal() = runTest {
        val events=mutableListOf<String>()
        val journal=object:ChangeJournal {
            override suspend fun append(change:NetworkChange) {events+="record:${change.label}"}
            override suspend fun completed(){events+="clean"}
            override suspend fun failed(message:String){events+="failed"}
        }
        val t=NetworkTransaction(journal) {events+=it; it!="second"}
        t.apply(NetworkChange("one","first","undo first"))
        assertFails { t.apply(NetworkChange("two","second","undo second")) }
        assertTrue(t.rollback())
        assertEquals(listOf("record:one","first","record:two","second","undo second","undo first","clean"),events)
    }
    @Test fun failedRecoveryDoesNotClearJournal() = runTest {
        var cleared=false;var failed=false
        val j=object:ChangeJournal {override suspend fun append(change:NetworkChange){}; override suspend fun completed(){cleared=true};override suspend fun failed(message:String){failed=true}}
        val t=NetworkTransaction(j){it!="undo"};t.apply(NetworkChange("x","do","undo"))
        assertFalse(t.rollback());assertFalse(cleared);assertTrue(failed)
    }
    @Test fun leaseWithoutHostnameRemainsVisible() {
        val c=ClientParser.leases("9999999999 00:11:22:33:44:55 192.168.50.10 * *","192.168.50.10 dev ap0 lladdr 00:11:22:33:44:55 REACHABLE",100).single()
        assertNull(c.hostname);assertTrue(c.online)
    }
    @Test fun hostapdUsesHexSsidAndDerivedWpa2Key() {
        val c=HostapdConfig.generate(profile.copy(ssid="A # test",isolation=true),"bb1234",6,"/data/app/session")
        assertTrue("ssid2=4120232074657374" in c);assertTrue("ap_isolate=1" in c)
        assertFalse(profile.password in c);assertTrue(Regex("wpa_psk=[a-f0-9]{64}").containsMatchIn(c))
    }
    @Test fun historicalAddressCannotBlockAReassignedClient() {
        val c=RouterClient("00:11:22:33:44:55","192.168.50.12",null,0,false,0,0,ClientPolicy.LAN_ONLY,leaseCurrent=false)
        val r=FirewallPlan.filter("BB_aabbccdd_F",profile.copy(mode=RouterMode.INTERNET),"ap7","wlan8",null,listOf(c))
        assertTrue(r.any {c.mac in it && "DROP" in it})
        assertFalse(r.any {"-d ${c.ip} " in it})
    }
    @Test fun servicesRejectAmbiguousBindings() {
        assertFails {profile.copy(services=listOf(LocalService("dashboard",8080,true))).validate()}
        assertFails {profile.copy(services=listOf(LocalService("dashboard",8080),LocalService("files",8080))).validate()}
        val p=profile.copy(services=listOf(LocalService("dashboard",8080)))
        assertTrue("host-record=dashboard.blackbox,192.168.50.1" in DnsmasqConfig.generate(p,"ap7","/data/app/session",emptyList()))
    }
    @Test fun trafficCountersUseBytesAndIgnoreUnownedRules() {
        val c=TrafficCounters.parse("""
             pkts bytes target prot opt in out source destination
               12 4096 all -- bb123 * 0.0.0.0/0 0.0.0.0/0 /* bb_up_001122334455 */
               14 8192 all -- * bb123 0.0.0.0/0 192.168.50.12 /* bb_down_001122334455 */
               99 2000 ACCEPT all -- * * 0.0.0.0/0 0.0.0.0/0
        """.trimIndent())
        assertEquals(mapOf("00:11:22:33:44:55" to ClientTraffic(8192,4096)),c)
    }
}
