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
import java.net.DatagramSocket
import java.net.DatagramPacket
import java.net.InetAddress

/** Explicitly opted-in MUTATING test. Creates an offline AP and always requests cleanup. */
class RouterLifecycleTest {
    @Test fun offlineRouterStartsResolvesAndRestores() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("routerTests")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val controller=RouterController.get(context)
        val root=LibsuRootExecutor();assertEquals(RootStatus.GRANTED,root.requestRoot())
        val beforeRules=root.executeRuntime("ip rule show").stdout
        val beforeForward=root.executeRuntime("cat /proc/sys/net/ipv4/ip_forward").stdout
        val profile=controller.newProfile().copy(name="Offline device test",ssid="BLACKBOX-TEST",mode=RouterMode.OFFLINE)
        controller.saveProfile(profile)
        val scenario=ActivityScenario.launch(MainActivity::class.java)
        try {
            RouterService.command(context,"START",profile.id)
            withTimeout(90000) {
                while(controller.state.value.status !in setOf(RouterStatus.RUNNING,RouterStatus.ERROR)) delay(250)
            }
            val state=controller.state.value
            context.openFileOutput("router-lifecycle-log.txt",0).use {it.write(state.log.joinToString("\n").toByteArray())}
            assertEquals(state.log.joinToString("\n"),RouterStatus.RUNNING,state.status)
            val query=byteArrayOf(0x12,0x34,1,0,0,1,0,0,0,0,0,0,6)+"router".toByteArray()+byteArrayOf(8)+"blackbox".toByteArray()+byteArrayOf(0,0,1,0,1)
            DatagramSocket().use { socket ->
                socket.soTimeout=5000;socket.send(DatagramPacket(query,query.size,InetAddress.getByName(profile.gateway),53))
                val response=DatagramPacket(ByteArray(1024),1024);socket.receive(response)
                assertEquals(0x12,response.data[0].toInt());assertEquals(0,response.data[3].toInt() and 15)
                assertTrue("DNS answer required",(response.data[7].toInt() and 255)>0)
            }
        } finally {
            controller.stop();scenario.close()
            context.openFileOutput("router-lifecycle-log.txt",0).use {it.write(controller.state.value.log.joinToString("\n").toByteArray())}
            if(!controller.state.value.recovery) controller.deleteProfile(profile.id)
            assertFalse("Recovery journal must be clear",controller.state.value.recovery)
            assertEquals(beforeRules,root.executeRuntime("ip rule show").stdout)
            assertEquals(beforeForward,root.executeRuntime("cat /proc/sys/net/ipv4/ip_forward").stdout)
            assertFalse(root.executeRuntime("iptables-save").stdout.contains("BB_"))
            root.close()
        }
    }
}
