package dev.blackbox.router

import androidx.test.platform.app.InstrumentationRegistry
import dev.blackbox.core.*
import dev.blackbox.router.runtime.RuntimeJournal
import dev.blackbox.router.root.LibsuRootExecutor
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import java.io.File
import java.util.UUID

class RecoveryCoordinationTest {
    @Test fun concurrentRecoveryRunsUndoExactlyOnce() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("rootTests")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val dir=File(context.filesDir,"recovery-lock-test-${UUID.randomUUID()}")
        val root=LibsuRootExecutor();assertEquals(RootStatus.GRANTED,root.requestRoot())
        try {
            val j=RuntimeJournal(dir)
            j.append(NetworkChange("record once","true","sleep 1; printf x >> ${shellQuote(File(dir,"count").path)}"))
            j.write("active","test")
            val script=shellQuote(File(dir,"recover.sh").path)
            val result=root.executeRuntime("sh $script & a=\$!; sh $script & b=\$!; wait \$a; first=\$?; wait \$b; second=\$?; test \$first = 0 && test \$second = 0")
            assertTrue(result.stderr,result.success)
            assertEquals("x",File(dir,"count").readText())
            assertFalse(File(dir,"active").exists())
        } finally {root.close();dir.deleteRecursively()}
    }
}
