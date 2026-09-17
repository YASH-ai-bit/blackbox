package dev.blackbox.core

import kotlinx.coroutines.CancellationException

class DiagnosticEngine(private val executor: RootExecutor, private val now: () -> Long = System::currentTimeMillis) : RouterBackend {
    override val name = "Native read-only diagnostics"

    override suspend fun inspect(facts: DeviceFacts, requestRoot: Boolean): CompatibilityReport {
        val root = if (requestRoot) executor.requestRoot() else if (executor.isAvailable()) RootStatus.GRANTED else RootStatus.NOT_REQUESTED
        val records = mutableListOf<ProbeRecord>()
        suspend fun run(command: ProbeCommand): CommandResult {
            val result = try { executor.execute(command) } catch (e: CancellationException) { throw e } catch (_: Exception) {
                CommandResult(stderr = "Shell execution failed", failure = CommandFailure.SHELL)
            }
            // Only sanitized evidence can escape the diagnostic engine; parsers receive originals locally.
            records += ProbeRecord(command.label, command.shell, result.copy(stdout = Sanitizer.clean(result.stdout), stderr = Sanitizer.clean(result.stderr)))
            return result
        }
        run(ProbeCommand.identity)
        run(ProbeCommand.kernel)
        run(ProbeCommand.abi)
        run(ProbeCommand.android)
        run(ProbeCommand.selinux)
        val binaries = ProbeCommand.binaryNames.map { name ->
            val result = run(ProbeCommand.discover(name))
            val path = result.stdout.lineSequence().firstOrNull { it.startsWith('/') && Regex("/[A-Za-z0-9_./+-]+").matches(it) }
            BinaryInfo(name, path.takeIf { result.success }, result.success || result.exitCode == 1 && result.failure == CommandFailure.EXIT_CODE)
        }
        suspend fun tool(name: String, args: String): CommandResult {
            val info = binaries.first { it.name == name }
            return if (info.path != null) run(ProbeCommand.binary(info, args)) else {
                val skipped = CommandResult(stderr = "Executable not found in accessible search paths", failure = CommandFailure.SKIPPED)
                records += ProbeRecord("$name $args", "$name $args [not executed]", skipped)
                skipped
            }
        }
        val links = tool("ip", "link show")
        tool("ip", "addr show")
        val routes = tool("ip", "route show table all")
        val rules = tool("ip", "rule show")
        val details = tool("ip", "-details link show")
        val wifi = tool("iw", "dev")
        val phy = tool("iw", "phy")
        val iptables = tool("iptables", "--version")
        tool("iptables-nft", "--version")
        val nft = tool("nft", "--version")
        val forwarding = run(ProbeCommand.forwarding)
        val visiblePhys = run(ProbeCommand.phys)
        val visibleInterfaces = run(ProbeCommand.interfaces)
        val wg = run(ProbeCommand.wireguard)
        val snapshot = NetworkSnapshot(now(),
            if (links.success) NetworkParsers.ipLink(links.stdout) else emptyList(),
            if (routes.success) NetworkParsers.ipRoute(routes.stdout) else emptyList(),
            if (rules.success) NetworkParsers.ipRule(rules.stdout) else emptyList(),
            if (wifi.success) NetworkParsers.iwDev(wifi.stdout) else emptyList(),
            if (phy.success) NetworkParsers.iwPhy(phy.stdout) else emptyList(),
            if (forwarding.success) when (forwarding.stdout.trim()) { "1" -> true; "0" -> false; else -> null } else null,
            facts.dnsServers, facts.addresses,
            if (visibleInterfaces.success) visibleInterfaces.stdout.lines().filter { Regex("[A-Za-z0-9_.-]{1,15}").matches(it) } else emptyList())
        val activeWifi = snapshot.wifi.firstOrNull { it.name == facts.activeInterface && it.type == "managed" }
            ?: snapshot.wifi.filter { it.type == "managed" }.singleOrNull()
        val relevantPhy = if (activeWifi != null) snapshot.phys.firstOrNull { it.name == activeWifi.phy }
            else snapshot.phys.singleOrNull()
        val driverStaAp = relevantPhy?.staAp ?: Support.UNKNOWN
        val frameworkSupport = when (facts.frameworkStaAp) { true -> Support.SUPPORTED; false -> Support.UNSUPPORTED; null -> Support.UNKNOWN }
        val combined = when {
            driverStaAp != Support.UNKNOWN && frameworkSupport != Support.UNKNOWN && driverStaAp != frameworkSupport -> Support.UNKNOWN
            driverStaAp != Support.UNKNOWN -> driverStaAp
            else -> frameworkSupport
        }
        val concurrencyDetail = when {
            driverStaAp != Support.UNKNOWN && frameworkSupport != Support.UNKNOWN && driverStaAp != frameworkSupport -> "Driver and Android framework disagree. A controlled test is needed."
            driverStaAp == Support.SUPPORTED -> "Advertised by ${relevantPhy?.name}. " + when (relevantPhy?.sameChannelRequired) {
                true -> "STA and AP must use the same channel."
                false -> "A combination permits multiple channels; runtime limits still apply."
                null -> "Channel constraints were not reported."
            }
            frameworkSupport == Support.SUPPORTED -> "Advertised by Android's Wi-Fi API. Driver combinations unavailable; custom AP operation remains unverified."
            combined == Support.UNSUPPORTED -> "Not advertised by the available capability source."
            else -> "No conclusive capability evidence. Missing iw or permission is not a hardware failure."
        }
        fun mode(name: String) = relevantPhy?.modes?.takeIf { it.isNotEmpty() }?.let { if (name in it) Support.SUPPORTED else Support.UNSUPPORTED } ?: Support.UNKNOWN
        val wgObserved = wg.success || details.success && Regex("(?m)^\\s*wireguard\\s").containsMatchIn(details.stdout)
        val capabilities = listOf(
            Capability("Root access", when (root) { RootStatus.GRANTED -> Support.SUPPORTED; RootStatus.UNAVAILABLE, RootStatus.DENIED -> Support.UNSUPPORTED; else -> Support.UNKNOWN },
                when (root) {
                    RootStatus.GRANTED -> "Verified root shell."
                    RootStatus.NOT_REQUESTED -> "Tap Request root & rescan. Approve BLACKBOX in your root manager."
                    RootStatus.UNAVAILABLE -> "No usable su executable in this app. If already rooted, check BLACKBOX access in your root manager. Root provisioning stays outside BLACKBOX."
                    RootStatus.DENIED -> "Root was not granted. Check your root manager and retry."
                    RootStatus.ERROR -> "Root shell could not be verified. Retry or inspect Developer Mode."
                }),
            Capability("Wi-Fi hardware", if (facts.wifiPresent) Support.SUPPORTED else Support.UNSUPPORTED, "Android hardware feature declaration."),
            Capability("Wi-Fi PHY", if (snapshot.phys.isNotEmpty() || visiblePhys.success && visiblePhys.stdout.isNotBlank()) Support.SUPPORTED else Support.UNKNOWN,
                snapshot.phys.joinToString { it.name }.ifEmpty { if (visiblePhys.success) visiblePhys.stdout.trim().ifEmpty { "No PHY visible" } else "Not accessible" }),
            Capability("STA mode", mode("managed"), "Driver advertisement; active interface: ${activeWifi?.name ?: "not established via iw"}."),
            Capability("AP mode", mode("AP"), "Requires driver evidence. Framework concurrency is reported separately."),
            Capability("Simultaneous Wi-Fi client + hotspot", combined, concurrencyDetail),
            Capability("IP forwarding", if (snapshot.forwarding != null) Support.SUPPORTED else Support.UNKNOWN,
                "Current state: ${snapshot.forwarding?.let { if (it) "enabled" else "disabled" } ?: "unreadable"}. Write permission not tested."),
            Capability("Firewall tooling", if (iptables.success || nft.success) Support.SUPPORTED else Support.UNKNOWN,
                when {
                    iptables.success && iptables.stdout.contains("nf_tables") -> "iptables uses nf_tables. Rule changes not tested."
                    iptables.success && iptables.stdout.contains("legacy") -> "iptables reports the legacy backend. Rule changes not tested."
                    iptables.success -> "iptables executable responds; backend variant is not explicitly reported. Rule changes not tested."
                    nft.success -> "nft executable responds. Rule changes not tested."
                    else -> "No working firewall CLI verified."
                }),
            Capability("WireGuard kernel evidence", if (wgObserved) Support.SUPPORTED else Support.UNKNOWN,
                if (wgObserved) "Loaded module or typed interface observed; tunnel operation not tested." else "No loaded module or typed interface observed. This does not rule out userspace WireGuard."),
            Capability("Upstream candidate", if (NetworkParsers.upstreamCandidate(snapshot.routes, snapshot.rules, facts.activeInterface) != null) Support.SUPPORTED else Support.UNKNOWN,
                NetworkParsers.upstreamCandidate(snapshot.routes, snapshot.rules, facts.activeInterface)?.let {
                    "$it from Android's active app network or default policy rule. Forwarded-client routing remains untested."
                } ?: "No unambiguous default network identified."),
            Capability("Router operation", Support.UNKNOWN, "Hardware inspection does not start or certify routing. The runtime engine validates its own session; a client test verifies connectivity."),
        )
        val missing = binaries.filter { it.name in setOf("ip", "iw", "hostapd", "dnsmasq") && it.path == null }.map { it.name }
        val next = buildList {
            if (root != RootStatus.GRANTED) add("Grant BLACKBOX root access on an already rooted device. BLACKBOX cannot root or unlock the phone.")
            if (missing.isNotEmpty()) add("Later backend needs verified tools: ${missing.joinToString()}. Android HAL services are not standalone daemons.")
            if (driverStaAp == Support.UNKNOWN) add("Repeat the driver probe with root and an audited iw binary before selecting a custom AP backend.")
            if (combined == Support.UNSUPPORTED) add("Plan mobile-data/Ethernet hotspot or offline LAN; Wi-Fi repeater is not advertised.")
            add("Create a network profile, then start the router. Runtime prerequisites, conflicts and recovery are checked separately.")
        }
        val verdict = when {
            root != RootStatus.GRANTED -> "Root required for router control"
            !facts.wifiPresent -> "No Wi-Fi hardware advertised"
            missing.isNotEmpty() -> "Backend prerequisites missing"
            combined == Support.SUPPORTED -> "Repeater candidate · runtime test pending"
            combined == Support.UNSUPPORTED -> "Repeater not advertised · assess fallback modes"
            else -> "Compatibility needs more evidence"
        }
        return CompatibilityReport(facts, root, snapshot, binaries, capabilities, records, verdict, next)
    }
}
