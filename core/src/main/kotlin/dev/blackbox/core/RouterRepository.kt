package dev.blackbox.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex

data class RouterUiState(
    val status: RouterStatus = RouterStatus.STOPPED,
    val report: CompatibilityReport? = null,
    val message: String? = null,
)

/** Read-only compatibility state, separate from the foreground routing lifecycle. */
class RouterRepository(private val backend: RouterBackend, private val facts: () -> DeviceFacts) {
    private val scanLock = Mutex()
    private val mutableState = MutableStateFlow(RouterUiState())
    val state: StateFlow<RouterUiState> = mutableState.asStateFlow()
    suspend fun scan(requestRoot: Boolean) {
        if (!scanLock.tryLock()) return
        try {
            mutableState.update { it.copy(status = RouterStatus.CHECKING, message = null) }
            val report = backend.inspect(facts(), requestRoot)
            mutableState.value = RouterUiState(RouterStatus.STOPPED, report)
        } catch (e: CancellationException) {
            mutableState.update { it.copy(status = RouterStatus.STOPPED, message = "Scan cancelled. You can run it again.") }
            throw e
        } catch (_: Exception) {
            mutableState.update { it.copy(status = RouterStatus.ERROR, message = "The compatibility scan could not finish. Run diagnostics again.") }
        } finally { scanLock.unlock() }
    }
}
