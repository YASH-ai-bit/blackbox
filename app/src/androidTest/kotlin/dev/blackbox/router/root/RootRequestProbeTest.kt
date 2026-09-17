package dev.blackbox.router.root

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.blackbox.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit opt-in tests both the granted and unavailable path without assuming this phone is rooted. */
@RunWith(AndroidJUnit4::class)
class RootRequestProbeTest {
    @Test fun rootRequestReportsRealResultAndFallbackIdentity() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("rootProbe") == "true")
        val executor = LibsuRootExecutor()
        try {
            val status = executor.requestRoot()
            assertNotEquals(RootStatus.NOT_REQUESTED, status)
            val result = executor.execute(ProbeCommand.identity)
            assertTrue(result.success)
            if (status == RootStatus.GRANTED) {
                assertTrue(executor.isAvailable())
                assertEquals(Execution.ROOT, result.execution)
                assertTrue(result.stdout.startsWith("uid=0("))
            } else {
                assertFalse(executor.isAvailable())
                assertEquals(Execution.APP, result.execution)
                assertFalse(result.stdout.startsWith("uid=0("))
            }
            InstrumentationRegistry.getInstrumentation().targetContext.openFileOutput("instrumentation-root-result.txt", 0).use {
                it.write("Root request: $status\nSubsequent command: ${result.execution}\nExit code: ${result.exitCode}\n".toByteArray())
            }
        } finally { executor.close() }
    }
}
