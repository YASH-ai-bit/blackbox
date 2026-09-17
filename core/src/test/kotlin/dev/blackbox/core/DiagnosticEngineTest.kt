package dev.blackbox.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class DiagnosticEngineTest {
    @Test fun unconfiguredFakeNeverReportsSuccess() = runTest {
        val fake = FakeRootExecutor()
        val report = DiagnosticEngine(fake).inspect(facts, false)
        assertEquals(0, fake.rootRequests)
        assertFalse(report.canStartRouter)
        assertTrue(report.records.none { it.result.success })
        assertEquals(Support.UNKNOWN, report.capabilities.first { it.title.startsWith("Simultaneous") }.support)
    }

    @Test fun frameworkConcurrencyIsUsefulWithoutRootButNotRouterReadiness() = runTest {
        val fake = FakeRootExecutor()
        val report = DiagnosticEngine(fake).inspect(facts.copy(frameworkStaAp = true), true)
        assertEquals(1, fake.rootRequests)
        assertEquals(RootStatus.UNAVAILABLE, report.root)
        assertEquals(Support.SUPPORTED, report.capabilities.first { it.title.startsWith("Simultaneous") }.support)
        assertEquals("Root required for router control", report.verdict)
        assertFalse(report.canStartRouter)
    }

    @Test fun failedIwOutputIsNotTreatedAsSuccess() = runTest {
        val fake = FakeRootExecutor(RootStatus.GRANTED).withTools()
        fake.results["iw phy"] = CommandResult(phyFixture("* #{ managed, AP } <= 2, total <= 2, #channels <= 1"), "denied", 1, failure = CommandFailure.EXIT_CODE)
        val report = DiagnosticEngine(fake).inspect(facts, true)
        assertTrue(report.snapshot.phys.isEmpty())
        assertEquals(Support.UNKNOWN, report.capabilities.first { it.title.startsWith("Simultaneous") }.support)
    }

    @Test fun truncatedIwOutputIsNotCapabilityEvidence() = runTest {
        val fake = FakeRootExecutor().withTools()
        fake.results["iw phy"] = CommandResult(phyFixture("* #{ managed, AP } <= 2, total <= 2, #channels <= 1"), exitCode = 0, truncated = true)
        assertTrue(DiagnosticEngine(fake).inspect(facts, false).snapshot.phys.isEmpty())
    }

    @Test fun conflictingFrameworkAndDriverEvidenceStaysUnknown() = runTest {
        val fake = FakeRootExecutor(RootStatus.GRANTED).withTools()
        fake.success("iw phy", phyFixture("* #{ managed, AP } <= 1, total <= 1, #channels <= 1"))
        val report = DiagnosticEngine(fake).inspect(facts.copy(frameworkStaAp = true), true)
        assertEquals(Support.UNKNOWN, report.capabilities.first { it.title.startsWith("Simultaneous") }.support)
    }

    @Test fun onlyActiveStaPhyCanSupportTheRepeaterVerdict() = runTest {
        val fake = FakeRootExecutor().withTools()
        fake.success("iw phy", phyFixture("* #{ managed, AP } <= 1, total <= 1, #channels <= 1", "phy0") + "\n" + phyFixture("* #{ managed, AP } <= 2, total <= 2, #channels <= 1", "phy1"))
        fake.success("iw dev", "phy#0\n    Interface wlan5\n        type managed")
        val report = DiagnosticEngine(fake).inspect(facts.copy(activeInterface = "wlan5"), false)
        assertEquals(Support.UNSUPPORTED, report.capabilities.first { it.title.startsWith("Simultaneous") }.support)
    }

    @Test fun missingWireguardModuleDoesNotMeanUnsupported() = runTest {
        val report = DiagnosticEngine(FakeRootExecutor()).inspect(facts, false)
        assertEquals(Support.UNKNOWN, report.capabilities.first { it.title.startsWith("WireGuard") }.support)
    }

    @Test fun evidenceIsSanitizedBeforeReachingUi() = runTest {
        val fake = FakeRootExecutor()
        fake.success("Shell identity", "password=private-password\n192.0.2.17\n02:11:22:33:44:55")
        val report = DiagnosticEngine(fake).inspect(facts, false)
        assertFalse(report.records.joinToString().contains("private-password"))
        assertFalse(report.records.joinToString().contains("02:11:22:33:44:55"))
        assertFalse(Sanitizer.report(report, true).contains("192.0.2.17"))
    }

    @Test fun commandsAreReadOnlyAndNoDaemonIsStarted() = runTest {
        val fake = FakeRootExecutor().withTools()
        DiagnosticEngine(fake).inspect(facts, true)
        val commands = fake.calls.map { it.shell }
        assertTrue(commands.none { Regex("\\b(add|del|flush|setenforce|kill|reboot|start|stop)\\b|>\\s*/").containsMatchIn(it) })
        assertTrue(fake.calls.none { it.label.startsWith("hostapd ") || it.label.startsWith("dnsmasq ") })
    }

    @Test fun probeCatalogueRejectsInjectionAndMutations() {
        assertFailsWith<IllegalArgumentException> { ProbeCommand.discover("iw; reboot") }
        assertFailsWith<IllegalArgumentException> { ProbeCommand.binary(BinaryInfo("ip", "/system/bin/ip", true), "link set wlan0 down") }
        assertFailsWith<IllegalArgumentException> { ProbeCommand.binary(BinaryInfo("ip", "/tmp/ip';reboot", true), "link show") }
    }

    @Test fun missingDaemonPathBlocksCandidateEvenWithRoot() = runTest {
        val fake = FakeRootExecutor(RootStatus.GRANTED).withTools()
        fake.results.remove("Find hostapd")
        assertEquals("Backend prerequisites missing", DiagnosticEngine(fake).inspect(facts.copy(frameworkStaAp = true), true).verdict)
    }

    @Test fun cancellationIsNotConvertedIntoSuccess() = runTest {
        val fake = object : RootExecutor {
            override suspend fun requestRoot() = RootStatus.DENIED
            override suspend fun isAvailable() = false
            override suspend fun execute(command: ProbeCommand): CommandResult = throw CancellationException()
            override suspend fun close() {}
        }
        assertFailsWith<CancellationException> { DiagnosticEngine(fake).inspect(facts, false) }
    }
}
