package dev.blackbox.core

import kotlin.test.*

class NetworkParsersTest {
    @Test fun parsesLinuxAndAndroidLinkOutputWithoutAssumingNames() {
        val links = NetworkParsers.ipLink("""
            1: lo: <LOOPBACK,UP,LOWER_UP> mtu 65536 qdisc noqueue state UNKNOWN mode DEFAULT
                link/loopback 00:00:00:00:00:00 brd 00:00:00:00:00:00
            32: swlan0@if31: <BROADCAST,MULTICAST,UP> mtu 1500 qdisc mq state UP mode DEFAULT
                link/ether 02:11:22:33:44:55
            33: ap0: <> mtu 1500 qdisc noop state DOWN
        """.trimIndent())
        assertEquals(listOf("lo", "swlan0", "ap0"), links.map { it.name })
        assertEquals(1500, links[1].mtu)
        assertEquals("UNKNOWN", links[0].state)
        assertTrue(links[2].flags.isEmpty())
        assertTrue(NetworkParsers.ipLink("request send failed: Permission denied").isEmpty())
    }

    @Test fun preservesRouteTablesAndRejectsErrorLines() {
        val routes = NetworkParsers.ipRoute("""
            default via 192.0.2.1 dev wlan0 table 1030 proto static
            default dev tun8 table 1042 metric 5
            192.168.50.0/24 dev softap0 proto kernel scope link src 192.168.50.1
            local 192.168.50.1 dev softap0 table local proto kernel
            unreachable default table 99
            fe80::/64 dev wlan0 table 1030 proto kernel metric 256 pref medium
            RTNETLINK answers: Permission denied
        """.trimIndent())
        assertEquals(6, routes.size)
        assertEquals("1030", routes[0].table)
        assertEquals("192.0.2.1", routes[0].gateway)
        assertEquals("main", routes[2].table)
        assertEquals("unreachable", routes[4].type)
        assertEquals(256, routes[5].metric)
    }

    @Test fun followsAndroidDefaultRuleNotFirstDefaultRoute() {
        val routes = NetworkParsers.ipRoute("default dev dummy0 table 1003\ndefault dev tun0 table 1050\ndefault via 192.0.2.1 dev wlan0 table 1030")
        val rules = NetworkParsers.ipRule("""
            12000: from all fwmark 0x0/0xffff iif lo uidrange 10000-19999 lookup 1050
            31000: from all fwmark 0x0/0xffff iif lo lookup 1030
            32000: from all unreachable
        """.trimIndent())
        assertEquals("10000-19999", rules[0].uidRange)
        assertEquals("unreachable", rules[2].action)
        assertEquals("wlan0", NetworkParsers.upstreamCandidate(routes, rules, null))
        assertEquals("tun0", NetworkParsers.upstreamCandidate(routes, rules, "tun0"))
        assertNull(NetworkParsers.upstreamCandidate(routes, emptyList(), null))
    }

    @Test fun doesNotGuessWhenDefaultRuleTableHasNoRoute() {
        val rules = NetworkParsers.ipRule("31000: from all fwmark 0x0/0xffff iif lo lookup 1030")
        assertNull(NetworkParsers.upstreamCandidate(NetworkParsers.ipRoute("default dev rmnet_data0 table 1010"), rules, null))
    }

    @Test fun rejectsMalformedRoutesAndErrorMessages() {
        assertTrue(NetworkParsers.ipRoute("Error: unable to read routes\n999.1.1.1/24 dev x\n10.0.0.0/99 dev x\nfe80::/200 dev x").isEmpty())
    }

    @Test fun parsesMultiplePhysAndChannels() {
        val wifi = NetworkParsers.iwDev("""
            phy#2
                Interface wlan7
                    type managed
                    channel 132 (5660 MHz), width: 40 MHz, center1: 5670 MHz
                Interface softap0
                    type AP
                    channel 132 (5660 MHz), width: 20 MHz
            phy#5
                Interface wlan8
                    type managed
        """.trimIndent())
        assertEquals(3, wifi.size)
        assertEquals("phy2", wifi[1].phy)
        assertEquals(5660, wifi[0].frequencyMhz)
        assertEquals(132, wifi[1].channel)
        assertNull(wifi[2].channel)
        assertEquals("phy5", wifi[2].phy)
    }

    @Test fun parsesWrappedCombinationAndSameChannelConstraint() {
        val phy = NetworkParsers.iwPhy(phyFixture("* #{ managed } <= 1, #{ AP } <= 1,\n          total <= 2, #channels <= 1, STA/AP BI must match")).single()
        assertEquals(Support.SUPPORTED, phy.staAp)
        assertEquals(true, phy.sameChannelRequired)
        assertEquals(setOf("managed", "AP"), phy.modes)
    }

    @Test fun sharedLimitOfOneCannotHostStaAndAp() {
        val phy = NetworkParsers.iwPhy(phyFixture("* #{ managed, AP } <= 1, total <= 2, #channels <= 1")).single()
        assertEquals(Support.UNSUPPORTED, phy.staAp)
    }

    @Test fun sharedLimitOfTwoCanHostStaAndAp() {
        val phy = NetworkParsers.iwPhy(phyFixture("* #{ managed, AP } <= 2, total <= 2, #channels <= 2")).single()
        assertEquals(Support.SUPPORTED, phy.staAp)
        assertEquals(false, phy.sameChannelRequired)
    }

    @Test fun totalLimitIsEnforced() {
        assertEquals(Support.UNSUPPORTED, NetworkParsers.iwPhy(phyFixture("* #{ managed } <= 1, #{ AP } <= 1, total <= 1, #channels <= 1")).single().staAp)
    }

    @Test fun separateCombinationsCannotBeCombined() {
        val phy = NetworkParsers.iwPhy(phyFixture("* #{ managed } <= 1, total <= 1, #channels <= 1\n        * #{ AP } <= 1, total <= 1, #channels <= 1")).single()
        assertEquals(Support.UNSUPPORTED, phy.staAp)
    }

    @Test fun p2pGoDoesNotMeanAp() {
        assertEquals(Support.UNSUPPORTED, NetworkParsers.iwPhy(phyFixture("* #{ managed } <= 1, #{ P2P-GO } <= 1, total <= 2, #channels <= 1")).single().staAp)
    }

    @Test fun malformedAndMissingCombinationsStayUnknown() {
        assertEquals(Support.UNKNOWN, NetworkParsers.iwPhy(phyFixture("* #{ managed } <= 1, #{ AP } <= 1")).single().staAp)
        assertEquals(Support.UNKNOWN, NetworkParsers.iwPhy(phyFixture("")).single().staAp)
        assertTrue(NetworkParsers.iwPhy("nl80211 not found").isEmpty())
    }

    @Test fun explicitNonSupportAndMissingApModeAreDifferentFromMissingIw() {
        assertEquals(Support.UNSUPPORTED, NetworkParsers.iwPhy("Wiphy phy0\n    interface combinations are not supported").single().staAp)
        assertEquals(Support.UNSUPPORTED, NetworkParsers.iwPhy(phyFixture("", modes = "* managed")).single().staAp)
    }

    @Test fun neverCombinesEvidenceFromDifferentPhys() {
        val phys = NetworkParsers.iwPhy(phyFixture("", "phy0", "* managed") + "\n" + phyFixture("", "phy1", "* AP"))
        assertEquals(2, phys.size)
        assertTrue(phys.all { it.staAp == Support.UNSUPPORTED })
    }

    @Test fun zeroLimitCannotSatisfyCombination() {
        assertFalse(InterfaceCombination(listOf(InterfaceLimit(setOf("managed"), 0), InterfaceLimit(setOf("AP"), 1)), 2, 1).supportsStaAp)
    }
}
