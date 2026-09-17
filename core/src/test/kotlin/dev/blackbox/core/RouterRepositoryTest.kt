package dev.blackbox.core

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class RouterRepositoryTest {
    @Test fun successfulScanReturnsToStoppedAndNeverRunning() = runTest {
        val repository = RouterRepository(DiagnosticEngine(FakeRootExecutor())) { facts }
        repository.scan(false)
        assertEquals(RouterStatus.STOPPED, repository.state.value.status)
        assertNotNull(repository.state.value.report)
        assertFalse(repository.state.value.report!!.canStartRouter)
    }
    @Test fun cancellationResetsStateAndUnlocksScan() = runTest {
        var calls = 0
        val backend = object : RouterBackend {
            override val name = "test"
            override suspend fun inspect(facts: DeviceFacts, requestRoot: Boolean): CompatibilityReport {
                calls++
                if (calls == 1) awaitCancellation()
                return DiagnosticEngine(FakeRootExecutor()).inspect(facts, false)
            }
        }
        val repository = RouterRepository(backend) { facts }
        val task = launch { repository.scan(false) }
        runCurrent()
        assertEquals(RouterStatus.CHECKING, repository.state.value.status)
        repository.scan(false)
        assertEquals(1, calls)
        task.cancelAndJoin()
        assertEquals(RouterStatus.STOPPED, repository.state.value.status)
        repository.scan(false)
        assertNotNull(repository.state.value.report)
        assertEquals(2, calls)
    }
    @Test fun unexpectedFailureIsVisibleAndDoesNotLeakRawOutput() = runTest {
        val backend = object : RouterBackend {
            override val name = "test"
            override suspend fun inspect(facts: DeviceFacts, requestRoot: Boolean): CompatibilityReport = error("password=secret")
        }
        val repository = RouterRepository(backend) { facts }
        repository.scan(false)
        assertEquals(RouterStatus.ERROR, repository.state.value.status)
        assertFalse(repository.state.value.message!!.contains("secret"))
        assertNull(repository.state.value.report)
    }
}
