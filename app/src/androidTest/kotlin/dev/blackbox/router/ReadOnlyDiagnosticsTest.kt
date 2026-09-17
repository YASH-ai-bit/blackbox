package dev.blackbox.router

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.blackbox.core.*
import dev.blackbox.router.diagnostics.AndroidFacts
import dev.blackbox.router.root.LibsuRootExecutor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReadOnlyDiagnosticsTest {
    @Test fun appShellReportsActualIdentityAndFrameworkCapabilities() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val executor = LibsuRootExecutor()
        try {
            val result = executor.execute(ProbeCommand.identity)
            assertTrue(result.success)
            assertEquals(Execution.APP, result.execution)
            assertTrue(result.stdout.contains("uid="))
            assertFalse(result.stdout.startsWith("uid=0("))
            val report = DiagnosticEngine(executor).inspect(AndroidFacts(context).read(), false)
            assertEquals(RootStatus.NOT_REQUESTED, report.root)
            assertFalse(report.canStartRouter)
            assertTrue(report.records.isNotEmpty())
            assertTrue(report.facts.sdk >= 26)
            // This is a sanitized, private report for the attached-device validation record.
            context.openFileOutput("instrumentation-diagnostics.txt", 0).use {
                it.write(Sanitizer.report(report, true).toByteArray())
            }
        } finally { executor.close() }
    }
}
