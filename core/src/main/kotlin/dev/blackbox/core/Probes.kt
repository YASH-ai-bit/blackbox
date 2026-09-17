package dev.blackbox.core

/** Read-only diagnostic command catalogue. No user shell input. */
class ProbeCommand private constructor(val label: String, val shell: String) {
    companion object {
        val identity = ProbeCommand("Shell identity", "id")
        val kernel = ProbeCommand("Kernel", "uname -a")
        val abi = ProbeCommand("Architecture", "getprop ro.product.cpu.abi")
        val android = ProbeCommand("Android version", "getprop ro.build.version.release")
        val selinux = ProbeCommand("SELinux", "getenforce")
        val forwarding = ProbeCommand("IPv4 forwarding state", "cat /proc/sys/net/ipv4/ip_forward")
        val phys = ProbeCommand("Visible Wi-Fi PHYs", "ls /sys/class/ieee80211")
        val interfaces = ProbeCommand("Visible interface names", "for d in /sys/class/net/*; do if [ -d \"\$d\" ]; then printf '%s\\n' \"\${d##*/}\"; fi; done")
        val wireguard = ProbeCommand("Loaded WireGuard module", "test -d /sys/module/wireguard")
        private val names = setOf("ip", "iw", "iptables", "iptables-nft", "nft", "hostapd", "dnsmasq", "wg")
        fun discover(name: String): ProbeCommand {
            require(name in names)
            return ProbeCommand("Find $name", "command -v $name || { for d in /system/bin /system/xbin /vendor/bin /vendor/bin/hw /odm/bin /product/bin /sbin /data/adb/magisk /data/adb/blackbox/tools; do if [ -x \"\$d/$name\" ]; then printf '%s\\n' \"\$d/$name\"; exit 0; fi; done; exit 1; }")
        }
        val binaryNames: List<String> get() = names.toList()
        private val args = mapOf(
            "ip" to setOf("link show", "addr show", "route show table all", "rule show", "-details link show"),
            "iw" to setOf("dev", "phy"),
            "iptables" to setOf("--version"), "iptables-nft" to setOf("--version"), "nft" to setOf("--version"),
        )
        fun binary(info: BinaryInfo, arguments: String): ProbeCommand {
            require(arguments in args[info.name].orEmpty())
            val path = requireNotNull(info.path)
            require(Regex("/[A-Za-z0-9_./+-]+").matches(path))
            return ProbeCommand("${info.name} $arguments", "'$path' $arguments")
        }
    }
}

interface RootExecutor {
    suspend fun requestRoot(): RootStatus
    suspend fun isAvailable(): Boolean
    suspend fun execute(command: ProbeCommand): CommandResult
    suspend fun close()
}

/** Test support belongs in tests; production never substitutes command success. */
interface RouterBackend {
    val name: String
    suspend fun inspect(facts: DeviceFacts, requestRoot: Boolean = false): CompatibilityReport
}
