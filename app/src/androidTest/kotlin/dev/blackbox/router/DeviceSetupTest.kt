package dev.blackbox.router

import androidx.test.platform.app.InstrumentationRegistry
import dev.blackbox.core.*
import dev.blackbox.router.runtime.RouterController
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import java.util.UUID

/** Explicit development provisioning, never part of normal app startup or the default test suite. */
class DeviceSetupTest {
    @Test fun prepareOwnerProfiles() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("setupProfiles")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val controller=RouterController.get(context)
        controller.recover();assertFalse(controller.state.value.recovery);controller.refreshProfiles()
        // Remove only profiles reserved by these instrumented lab fixtures, including an intentionally crashed fixture.
        val labPairs=setOf("Client validation" to "BLACKBOX-LAB","Offline device test" to "BLACKBOX-TEST","Cancellation validation" to "BLACKBOX-CANCEL")
        controller.state.value.profiles.filter {(it.name to it.ssid) in labPairs}.forEach {controller.deleteProfile(it.id)}
        val names=controller.state.value.profiles.map {it.name}
        val base=controller.newProfile()
        if("Offline LAN" !in names)controller.saveProfile(base.copy(name="Offline LAN",mode=RouterMode.OFFLINE))
        if("Travel Wi-Fi" !in names)controller.saveProfile(base.copy(id=UUID.randomUUID().toString(),name="Travel Wi-Fi",mode=RouterMode.REPEATER))
        for(name in listOf("client-test-ready.json","client-test-stop","client-test-control.json","client-test-control-result")) java.io.File(context.filesDir,name).delete()
        context.getSharedPreferences("blackbox_preferences",0).edit().putBoolean("developer_mode",false).commit()
        assertTrue(controller.state.value.profiles.any {it.name=="Travel Wi-Fi"})
    }
}
