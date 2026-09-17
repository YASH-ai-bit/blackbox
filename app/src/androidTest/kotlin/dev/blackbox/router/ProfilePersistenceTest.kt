package dev.blackbox.router

import androidx.test.platform.app.InstrumentationRegistry
import dev.blackbox.core.*
import dev.blackbox.router.data.ProfileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ProfilePersistenceTest {
    @Test fun profilesAndClientPolicySurviveNewStoreInstance() = runBlocking(Dispatchers.IO) {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val store=ProfileStore(context)
        val p=RouterProfile(password="test-only secret",dnsMode=DnsMode.CUSTOM,dns=listOf("9.9.9.9"),services=listOf(LocalService("dashboard",8080)))
        val c=RouterClient("00:11:22:33:44:55","192.168.50.10","Laptop",2000000000,true,100,200,ClientPolicy.LAN_ONLY)
        try {
            store.save(p);store.saveClients(p.id,listOf(c))
            val reopened=ProfileStore(context)
            assertEquals(p,reopened.all().single {it.id==p.id})
            val remembered=reopened.clients(p.id).single()
            assertEquals(c.policy,remembered.policy);assertEquals(c.firstSeen,remembered.firstSeen);assertEquals(c.lastSeen,remembered.lastSeen)
            assertFalse(remembered.online);assertFalse(remembered.leaseCurrent)
        } finally {store.delete(p.id)}
        assertTrue(store.clients(p.id).isEmpty())
    }
}
