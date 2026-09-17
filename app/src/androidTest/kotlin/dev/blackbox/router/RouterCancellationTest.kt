package dev.blackbox.router

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.blackbox.core.*
import dev.blackbox.router.runtime.*
import dev.blackbox.router.root.LibsuRootExecutor
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.Assert.*
import org.junit.Assume.assumeTrue

class RouterCancellationTest {
    @Test fun stopDuringStartupRestoresOwnedState() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("routerTests")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val controller=RouterController.get(context);val root=LibsuRootExecutor()
        assertEquals(RootStatus.GRANTED,root.requestRoot())
        val before=root.executeRuntime("ip rule show").stdout
        val p=controller.newProfile().copy(name="Cancellation validation",ssid="BLACKBOX-CANCEL")
        controller.saveProfile(p)
        val scenario=ActivityScenario.launch(MainActivity::class.java)
        try {
            RouterService.command(context,"START",p.id)
            withTimeout(90000) {while(controller.state.value.lan==null && controller.state.value.status!=RouterStatus.ERROR)delay(20)}
            assertNotEquals(controller.state.value.log.joinToString("\n"),RouterStatus.ERROR,controller.state.value.status)
            RouterService.command(context,"STOP")
            withTimeout(90000) {while(controller.state.value.status !in setOf(RouterStatus.STOPPED,RouterStatus.ERROR))delay(50)}
            assertEquals(controller.state.value.log.joinToString("\n"),RouterStatus.STOPPED,controller.state.value.status)
            assertFalse(controller.state.value.recovery)
            assertEquals(before,root.executeRuntime("ip rule show").stdout)
            assertFalse(root.executeRuntime("iptables-save").stdout.contains("BB_"))
        } finally {controller.stop();controller.deleteProfile(p.id);scenario.close();root.close()}
    }
}
