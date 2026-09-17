package dev.blackbox.router.root

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.blackbox.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in: -Pandroid.testInstrumentationRunnerArguments.rootTests=true. May open the root manager prompt. */
@RunWith(AndroidJUnit4::class)
class RootDeviceTest {
    @Test fun rootIdentityIsReal() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("rootTests") == "true")
        val executor = LibsuRootExecutor()
        try {
            assertEquals(RootStatus.GRANTED, executor.requestRoot())
            val result = executor.execute(ProbeCommand.identity)
            assertEquals(Execution.ROOT, result.execution)
            assertTrue(result.success)
            assertTrue(result.stdout.startsWith("uid=0("))
        } finally { executor.close() }
    }
}
