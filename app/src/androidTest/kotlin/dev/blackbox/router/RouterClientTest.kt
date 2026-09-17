package dev.blackbox.router

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.blackbox.core.*
import dev.blackbox.router.runtime.*
import dev.blackbox.router.root.LibsuRootExecutor
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import java.io.File

/** Opt-in lab fixture that holds a real router while an external client runs its tests. */
class RouterClientTest {
    @Test fun serveExternalClient() = runBlocking {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("clientTests")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val controller=RouterController.get(context)
        val mode=RouterMode.valueOf(args.getString("mode")?:"REPEATER")
        val vpnTest=args.getString("vpnTest")=="true"
        val servicesTest=args.getString("servicesTest")=="true"
        val root=LibsuRootExecutor()
        if(vpnTest) {
            assertEquals(RootStatus.GRANTED,root.requestRoot())
            assertTrue(root.executeRuntime("test ! -e /sys/class/net/bbtestwg").success)
            assertTrue(root.executeRuntime("ip link add bbtestwg type wireguard && ip addr add 10.253.254.1/32 dev bbtestwg && ip link set bbtestwg up").success)
        }
        val p=controller.newProfile().copy(name="Client validation",ssid="BLACKBOX-LAB",mode=mode,
            vpn=if(vpnTest) VpnPolicy.ALL_CLIENTS else VpnPolicy.OFF,vpnInterface=if(vpnTest) "bbtestwg" else "",
            services=if(servicesTest) listOf(LocalService("dashboard",8080)) else emptyList(),
            dnsMode=if(servicesTest) DnsMode.AD_BLOCK else DnsMode.SYSTEM,dns=if(servicesTest) listOf("1.1.1.1","9.9.9.9") else emptyList(),
            blockedDomains=if(servicesTest) listOf("blocked.blackbox-test.example") else emptyList())
        controller.saveProfile(p)
        val stop=File(context.filesDir,"client-test-stop");stop.delete()
        val result=File(context.filesDir,"client-test-ready.json");result.delete()
        val scenario=ActivityScenario.launch(MainActivity::class.java)
        try {
            RouterService.command(context,"START",p.id)
            withTimeout(90000) {while(controller.state.value.status !in setOf(RouterStatus.RUNNING,RouterStatus.ERROR))delay(250)}
            assertEquals(controller.state.value.log.joinToString("\n"),RouterStatus.RUNNING,controller.state.value.status)
            if(mode==RouterMode.REPEATER && !vpnTest) {
                val connection=java.net.URL("https://example.com/").openConnection() as java.net.HttpURLConnection
                try {connection.connectTimeout=10000;connection.readTimeout=10000;assertEquals("Phone-local Internet must remain usable",200,connection.responseCode)} finally {connection.disconnect()}
            }
            // Private app storage only. The local ADB harness uses this ephemeral password, never the report.
            result.writeText(JSONObject().put("ssid",p.ssid).put("password",p.password).put("gateway",p.gateway).put("mode",p.mode).put("vpnTest",vpnTest).put("servicesTest",servicesTest).toString())
            val control=File(context.filesDir,"client-test-control.json");control.delete()
            withTimeout(600000) {while(!stop.exists()) {
                if(control.exists()) {
                    val command=JSONObject(control.readText());control.delete()
                    if(command.optBoolean("removeVpn")) {
                        assertTrue(root.executeRuntime("ip link del bbtestwg").success)
                        File(context.filesDir,"client-test-control-result").writeText("vpn removed")
                    } else {
                        val client=controller.state.value.clients.firstOrNull()
                        if(client!=null) controller.setPolicy(client.mac,ClientPolicy.valueOf(command.getString("policy")))
                        File(context.filesDir,"client-test-control-result").writeText(if(client==null) "no client" else "applied")
                    }
                }
                delay(1000)
            }}
            File(context.filesDir,"client-test-stats.json").writeText(org.json.JSONArray(controller.state.value.clients.map { c ->
                JSONObject().put("down",c.receivedBytes).put("up",c.sentBytes).put("policy",c.policy.name)
            }).toString())
        } finally {
            controller.stop();scenario.close();result.delete();stop.delete()
            File(context.filesDir,"client-test-log.txt").writeText(controller.state.value.log.joinToString("\n"))
            if(!controller.state.value.recovery)controller.deleteProfile(p.id)
            if(vpnTest)root.executeRuntime("if [ -e /sys/class/net/bbtestwg ]; then ip link del bbtestwg; fi")
            root.close()
        }
    }
}
