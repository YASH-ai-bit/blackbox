package dev.blackbox.core

object NetworkParsers {
    private fun token(line: String, key: String): String? =
        Regex("(?:^|\\s)${Regex.escape(key)}\\s+(\\S+)").find(line)?.groupValues?.get(1)

    fun ipLink(output: String): List<InterfaceState> = output.lineSequence().mapNotNull { line ->
        val match = Regex("^\\s*(\\d+):\\s+([^:]+):\\s+<([^>]*)>(.*)$").find(line) ?: return@mapNotNull null
        InterfaceState(match.groupValues[1].toInt(), match.groupValues[2].substringBefore('@'),
            match.groupValues[3].split(',').filter { it.isNotBlank() }.toSet(),
            token(match.groupValues[4], "state"), token(match.groupValues[4], "mtu")?.toIntOrNull())
    }.toList()

    fun ipRoute(output: String): List<RouteState> = output.lineSequence().mapNotNull { line ->
        val words = line.trim().split(Regex("\\s+"))
        if (words.isEmpty() || words[0].isBlank()) return@mapNotNull null
        val kinds = setOf("unicast", "local", "broadcast", "multicast", "unreachable", "blackhole", "prohibit", "throw", "anycast")
        val type = words[0].takeIf { it in kinds } ?: "unicast"
        val destination = words.getOrNull(if (words[0] in kinds) 1 else 0) ?: return@mapNotNull null
        if (!isDestination(destination)) return@mapNotNull null
        RouteState(destination, token(line, "dev"), token(line, "via"), token(line, "table") ?: "main", token(line, "metric")?.toIntOrNull(), type)
    }.toList()

    private fun isDestination(value: String): Boolean {
        if (value == "default") return true
        val address = value.substringBefore('/')
        val prefix = if ('/' in value) value.substringAfter('/').toIntOrNull() ?: return false else null
        if (':' in address) return Regex("[0-9a-fA-F:]+").matches(address) && (prefix == null || prefix in 0..128)
        val octets = address.split('.')
        return octets.size == 4 && octets.all { it.toIntOrNull() in 0..255 } && (prefix == null || prefix in 0..32)
    }

    fun ipRule(output: String): List<RuleState> = output.lineSequence().mapNotNull { line ->
        val priority = Regex("^\\s*(\\d+):").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
        RuleState(priority, token(line, "lookup") ?: token(line, "table"), token(line, "fwmark"),
            token(line, "iif"), token(line, "oif"), token(line, "uidrange"),
            if (line.contains("unreachable")) "unreachable" else "lookup")
    }.toList()

    fun iwDev(output: String): List<WifiInterface> {
        val result = mutableListOf<WifiInterface>()
        var phy: String? = null
        var current: WifiInterface? = null
        fun finish() { current?.let(result::add); current = null }
        output.lineSequence().forEach { line ->
            val text = line.trim()
            when {
                Regex("^phy#\\d+$").matches(text) -> { finish(); phy = "phy${text.substringAfter('#')}" }
                text.startsWith("Interface ") -> { finish(); current = WifiInterface(text.removePrefix("Interface "), phy, null, null, null) }
                text.startsWith("type ") -> current = current?.copy(type = text.removePrefix("type "))
                text.startsWith("channel ") -> {
                    val match = Regex("channel (\\d+) \\((\\d+) MHz\\)").find(text)
                    current = current?.copy(channel = match?.groupValues?.get(1)?.toIntOrNull(), frequencyMhz = match?.groupValues?.get(2)?.toIntOrNull())
                }
            }
        }
        finish()
        return result
    }

    fun iwPhy(output: String): List<WifiPhy> {
        val headers = Regex("(?m)^\\s*Wiphy (\\S+)\\s*$").findAll(output).toList()
        return headers.mapIndexed { index, match ->
            val body = output.substring(match.range.last + 1, headers.getOrNull(index + 1)?.range?.first ?: output.length)
            val modes = mutableSetOf<String>()
            val combinationStrings = mutableListOf<String>()
            var section: String? = null
            var headerIndent = 0
            var unsupported = false
            body.lines().forEach { line ->
                val t = line.trim()
                val indent = line.takeWhile { it.isWhitespace() }.replace("\t", "        ").length
                when {
                    t == "Supported interface modes:" -> { section = "modes"; headerIndent = indent }
                    t.startsWith("valid interface combinations:") -> { section = "combos"; headerIndent = indent }
                    t.contains("interface combinations are not supported") -> { unsupported = true; section = null }
                    t.isEmpty() -> Unit
                    section != null && indent <= headerIndent -> section = null
                    section == "modes" && t.startsWith("* ") -> modes += t.removePrefix("* ").trim()
                    section == "combos" && t.startsWith("* ") -> combinationStrings += t.removePrefix("* ")
                    section == "combos" && combinationStrings.isNotEmpty() -> combinationStrings[combinationStrings.lastIndex] += " $t"
                }
            }
            var incomplete = false
            val combos = combinationStrings.map { text ->
                val limits = Regex("#\\{\\s*([^}]+)\\}\\s*<=\\s*(\\d+)").findAll(text).map {
                    InterfaceLimit(it.groupValues[1].split(',').map(String::trim).toSet(), it.groupValues[2].toInt())
                }.toList()
                val total = Regex("total\\s*<=\\s*(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull()
                val channels = Regex("#channels\\s*<=\\s*(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull()
                if (limits.isEmpty() || total == null) incomplete = true
                InterfaceCombination(limits, total, channels)
            }
            WifiPhy(match.groupValues[1], modes, combos, unsupported, incomplete)
        }
    }

    /** Candidate only: Android app/default routes and forwarded packets have different policy rules. */
    fun upstreamCandidate(routes: List<RouteState>, rules: List<RuleState>, frameworkInterface: String?): String? {
        if (frameworkInterface != null) return frameworkInterface
        val defaultRule = rules.sortedBy { it.priority }.firstOrNull {
            it.mark == "0x0/0xffff" && it.input == "lo" && it.uidRange == null && it.output == null
        }
        val candidates = routes.filter { it.destination == "default" && it.type == "unicast" && it.device != "dummy0" }
        if (defaultRule != null) return candidates.firstOrNull { it.table == defaultRule.table }?.device
        return candidates.mapNotNull { it.device }.distinct().singleOrNull()
    }
}
