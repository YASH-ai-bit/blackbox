package dev.blackbox.core

enum class Support { SUPPORTED, UNSUPPORTED, UNKNOWN }
enum class RouterStatus { STOPPED, CHECKING, STARTING, RUNNING, DEGRADED, STOPPING, RECOVERING, ERROR }
enum class RootStatus { NOT_REQUESTED, GRANTED, UNAVAILABLE, DENIED, ERROR }
enum class Execution { ROOT, APP }
enum class CommandFailure { NONE, EXIT_CODE, TIMEOUT, SHELL, SKIPPED }

data class CommandResult(
    val stdout: String = "",
    val stderr: String = "",
    val exitCode: Int = -1,
    val durationMs: Long = 0,
    val execution: Execution = Execution.APP,
    val failure: CommandFailure = CommandFailure.NONE,
    val truncated: Boolean = false,
) {
    val success: Boolean get() = exitCode == 0 && failure == CommandFailure.NONE && !truncated
}

data class ProbeRecord(val label: String, val command: String, val result: CommandResult)
data class Capability(val title: String, val support: Support, val detail: String)
data class InterfaceState(val index: Int, val name: String, val flags: Set<String>, val state: String?, val mtu: Int?)
data class RouteState(val destination: String, val device: String?, val gateway: String?, val table: String, val metric: Int?, val type: String)
data class RuleState(val priority: Int, val table: String?, val mark: String?, val input: String?, val output: String?, val uidRange: String?, val action: String)
data class WifiInterface(val name: String, val phy: String?, val type: String?, val channel: Int?, val frequencyMhz: Int?)
data class InterfaceLimit(val types: Set<String>, val maximum: Int)
data class InterfaceCombination(val limits: List<InterfaceLimit>, val total: Int?, val channels: Int?) {
    val supportsStaAp: Boolean get() {
        val needed = setOf("managed", "AP")
        return (total ?: 0) >= 2 && limits.flatMap { it.types }.containsAll(needed) &&
            limits.all { limit -> limit.types.count { it in needed } <= limit.maximum }
    }
}
data class WifiPhy(
    val name: String,
    val modes: Set<String>,
    val combinations: List<InterfaceCombination>,
    val combinationsExplicitlyUnsupported: Boolean,
    val combinationsIncomplete: Boolean = false,
) {
    val staAp: Support get() = when {
        combinations.any { it.supportsStaAp } -> Support.SUPPORTED
        modes.isNotEmpty() && (!modes.contains("managed") || !modes.contains("AP")) -> Support.UNSUPPORTED
        combinationsIncomplete -> Support.UNKNOWN
        combinationsExplicitlyUnsupported || combinations.isNotEmpty() -> Support.UNSUPPORTED
        else -> Support.UNKNOWN
    }
    val sameChannelRequired: Boolean? get() = combinations.filter { it.supportsStaAp }.let { matches ->
        when {
            matches.isEmpty() || matches.any { it.channels == null } -> null
            else -> matches.all { it.channels == 1 }
        }
    }
}
data class BinaryInfo(val name: String, val path: String?, val discoverySucceeded: Boolean)
data class DeviceFacts(
    val manufacturer: String, val model: String, val android: String, val sdk: Int, val abi: String,
    val wifiPresent: Boolean, val frameworkStaAp: Boolean?, val activeInterface: String?,
    val transport: String?, val internetValidated: Boolean?, val dnsServers: List<String>,
    val addresses: List<String>, val batteryPercent: Int?, val charging: Boolean?, val batteryCelsius: Float?,
    val thermalStatus: String?,
)
data class NetworkSnapshot(
    val capturedAtMs: Long,
    val interfaces: List<InterfaceState>,
    val routes: List<RouteState>,
    val rules: List<RuleState>,
    val wifi: List<WifiInterface>,
    val phys: List<WifiPhy>,
    val forwarding: Boolean?,
    val dnsServers: List<String>,
    val addresses: List<String>,
    val visibleInterfaceNames: List<String> = emptyList(),
)
data class CompatibilityReport(
    val facts: DeviceFacts, val root: RootStatus, val snapshot: NetworkSnapshot,
    val binaries: List<BinaryInfo>, val capabilities: List<Capability>, val records: List<ProbeRecord>,
    val verdict: String, val nextSteps: List<String>,
) {
    // A read-only compatibility report cannot certify a mutating router session.
    val canStartRouter: Boolean get() = false
}
