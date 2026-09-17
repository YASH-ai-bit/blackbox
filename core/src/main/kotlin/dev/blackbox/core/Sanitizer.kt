package dev.blackbox.core

object Sanitizer {
    private val sensitiveLine = Regex("(?i)\\b(ssid|password|(?:wpa_)?passphrase|(?:wpa_)?psk|preSharedKey|identity|private[_ -]?key|token|secret)\\s*[:= ].*")
    private val mac = Regex("(?i)(?<![0-9a-f:])(?:[0-9a-f]{2}:){5}[0-9a-f]{2}(?![0-9a-f:])")
    private val ipv4 = Regex("(?<![\\d.])(?:\\d{1,3}\\.){3}\\d{1,3}(?![\\d.])")
    private val ipv6 = Regex("(?i)(?<![\\w:])(?:[a-f0-9]{0,4}:){2,}[a-f0-9:.]*(?:%[\\w.-]+)?")
    fun clean(text: String): String = text.lineSequence().map { line ->
        val secretFree = sensitiveLine.replace(line) { "${it.groupValues[1]}=[redacted]" }
        ipv6.replace(ipv4.replace(mac.replace(secretFree, "[MAC]"), "[IP]"), "[IPv6]")
            .filter { it == '\t' || !it.isISOControl() }
    }.joinToString("\n").take(64_000)

    fun report(report: CompatibilityReport, includeCommands: Boolean): String = clean(buildString {
        appendLine("BLACKBOX / compatibility report / v0.3.0")
        appendLine("Captured: ${java.time.Instant.ofEpochMilli(report.snapshot.capturedAtMs)}")
        appendLine("Device: ${report.facts.manufacturer} ${report.facts.model}")
        appendLine("Android ${report.facts.android} / API ${report.facts.sdk} / ${report.facts.abi}")
        appendLine("Root: ${report.root}")
        appendLine("Verdict: ${report.verdict}")
        appendLine("Read-only scan. Router operation is verified separately by the runtime and client tests.")
        report.capabilities.forEach { appendLine("${it.title}: ${it.support} — ${it.detail}") }
        report.binaries.forEach { appendLine("${it.name}: ${it.path ?: "not found in accessible search paths"}") }
        report.nextSteps.forEach { appendLine("Next: $it") }
        if (includeCommands) report.records.forEach {
            appendLine("\n$ ${it.command}")
            appendLine("${it.result.execution} / exit ${it.result.exitCode} / ${it.result.durationMs} ms / ${it.result.failure}")
            appendLine(it.result.stdout)
            if (it.result.stderr.isNotEmpty()) appendLine("stderr: ${it.result.stderr}")
            if (it.result.truncated) appendLine("[output truncated; not used as capability evidence]")
        }
    })
}
