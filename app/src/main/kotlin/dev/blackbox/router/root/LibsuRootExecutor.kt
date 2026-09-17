package dev.blackbox.router.root

import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.NoShellException
import dev.blackbox.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.concurrent.Executor
import kotlin.coroutines.resume

/** Serialized private shell owner for typed diagnostics and internal validated runtime commands. */
class LibsuRootExecutor : RootExecutor {
    private data class Session(val process: Process, val shell: Shell)
    private val mutex = Mutex()
    private var session: Session? = null
    private val direct = Executor { it.run() }

    override suspend fun isAvailable(): Boolean = mutex.withLock { session?.let { it.process.isAlive && it.shell.isRoot } == true }

    private fun destroySession() {
        session?.process?.destroyForcibly()
        session = null
    }

    private fun create(command: String): Session {
        val process = ProcessBuilder(command).start()
        return try {
            Session(process, Shell.Builder.create().setTimeout(20).build(process))
        } catch (e: Exception) {
            process.destroyForcibly()
            throw e
        }
    }

    override suspend fun requestRoot(): RootStatus = mutex.withLock {
        withContext(Dispatchers.IO) {
            destroySession()
            try {
                session = create("su")
                if (session?.shell?.isRoot == true) RootStatus.GRANTED else {
                    destroySession()
                    RootStatus.DENIED
                }
            } catch (_: IOException) { RootStatus.UNAVAILABLE }
            catch (_: NoShellException) { RootStatus.DENIED }
            catch (e: CancellationException) { destroySession(); throw e }
            catch (_: Exception) { destroySession(); RootStatus.ERROR }
        }
    }

    override suspend fun execute(command: ProbeCommand): CommandResult = executeText(command.shell, false)

    /** Only the networking engine uses this entry point, with validated/generated commands. */
    internal suspend fun executeRuntime(command: String): CommandResult = executeText(command, true)

    private suspend fun executeText(command: String, requireRoot: Boolean): CommandResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            val start = System.nanoTime()
            var execution = Execution.APP
            try {
                if (requireRoot && (session?.process?.isAlive != true || session?.shell?.isRoot != true))
                    return@withContext CommandResult(stderr="A verified root session is required", failure=CommandFailure.SHELL)
                if (session?.process?.isAlive != true) { destroySession(); session = create("/system/bin/sh") }
                val current = requireNotNull(session)
                execution = if (current.shell.isRoot) Execution.ROOT else Execution.APP
                val out = LimitedOutput()
                val err = LimitedOutput()
                val result = withTimeoutOrNull(if (requireRoot) 25_000 else 8_000) {
                    suspendCancellableCoroutine<Shell.Result> { continuation ->
                        continuation.invokeOnCancellation { current.process.destroyForcibly() }
                        // Subshell prevents diagnostic exit codes from ending the reusable libsu shell.
                        current.shell.newJob().add("( $command )").to(out, err).submit(direct) {
                            if (continuation.isActive) continuation.resume(it)
                        }
                    }
                }
                val elapsed = (System.nanoTime() - start) / 1_000_000
                if (result == null) {
                    destroySession()
                    CommandResult(stderr = if(requireRoot) "Privileged command timed out" else "Read-only command timed out", durationMs = elapsed, execution = execution, failure = CommandFailure.TIMEOUT)
                } else CommandResult(out.joinToString("\n"), err.joinToString("\n"), result.code, elapsed, execution,
                    if (result.code == 0) CommandFailure.NONE else CommandFailure.EXIT_CODE, out.truncated || err.truncated)
            } catch (e: CancellationException) { destroySession(); throw e }
            catch (e: Exception) {
                destroySession()
                CommandResult(stderr = e.message ?: "Shell unavailable", durationMs = (System.nanoTime() - start) / 1_000_000,
                    execution = execution, failure = CommandFailure.SHELL)
            }
        }
    }

    override suspend fun close() = mutex.withLock { destroySession() }

    private class LimitedOutput : ArrayList<String>() {
        private var characters = 0
        var truncated = false
            private set
        override fun add(element: String): Boolean {
            if (characters + element.length > 262_144 || size >= 4_000) { truncated = true; return false }
            characters += element.length
            return super.add(element)
        }
    }
}
