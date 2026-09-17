package dev.blackbox.core

/** Synthetic fixtures, never used in the app and never represented as device measurements. */
internal val facts = DeviceFacts("Test", "Fixture", "13", 33, "arm64-v8a", true, null, null, null, null,
    emptyList(), emptyList(), null, null, null, null)

internal fun phyFixture(combination: String, name: String = "phy0", modes: String = "* managed\n        * AP") = """
Wiphy $name
    Supported interface modes:
        $modes
    Band 1:
        Frequencies:
            * 2412 MHz [1] (20.0 dBm)
    valid interface combinations:
        $combination
    HT Capability overrides:
        * MCS: ff ff ff ff
""".trimIndent()

internal class FakeRootExecutor(var rootResult: RootStatus = RootStatus.UNAVAILABLE) : RootExecutor {
    val results = mutableMapOf<String, CommandResult>()
    val calls = mutableListOf<ProbeCommand>()
    var rootRequests = 0
    private var granted = false
    override suspend fun requestRoot(): RootStatus { rootRequests++; granted = rootResult == RootStatus.GRANTED; return rootResult }
    override suspend fun isAvailable() = granted
    override suspend fun execute(command: ProbeCommand): CommandResult {
        calls += command
        return results[command.label] ?: CommandResult(stderr = "No test fixture supplied", failure = CommandFailure.SKIPPED)
    }
    override suspend fun close() { granted = false }
    fun success(label: String, output: String) { results[label] = CommandResult(stdout = output, exitCode = 0) }
    fun withTools(): FakeRootExecutor {
        ProbeCommand.binaryNames.forEach { success("Find $it", "/system/bin/$it") }
        return this
    }
}
