package dev.blackbox.router.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.blackbox.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class ProfileStore(context: Context) {
    private val db=database(context.applicationContext)
    private fun key(): SecretKey = synchronized(keyLock) {
        val store=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("blackbox.profiles",null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("blackbox.profiles",KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun decrypt(bytes:ByteArray):JSONObject {
        require(bytes.size>=28)
        val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,bytes.copyOfRange(0,12)))
        return JSONObject(String(c.doFinal(bytes.copyOfRange(12,bytes.size)),Charsets.UTF_8))
    }
    private fun encrypt(json:JSONObject):ByteArray {
        val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key())
        return c.iv+c.doFinal(json.toString().toByteArray(Charsets.UTF_8))
    }
    fun all() = db.profiles().all().map { decode(decrypt(it.payload)) }
    fun save(p:RouterProfile) {
        p.validate()
        db.profiles().save(ProfileRow().apply { id=p.id;payload=encrypt(encode(p));updated=System.currentTimeMillis() })
    }
    fun delete(id:String) {db.runInTransaction {db.profiles().delete(id);db.clients().delete(id)}}
    fun clients(profile:String):List<RouterClient> = db.clients().all(profile).map { row ->
        val j=decrypt(row.payload)
        RouterClient(row.mac,j.getString("ip"),j.optString("hostname").takeIf(String::isNotEmpty),j.getLong("lease"),false,
            j.getLong("first"),j.getLong("last"),ClientPolicy.valueOf(j.getString("policy")),leaseCurrent=false)
    }
    fun saveClients(profile:String,clients:List<RouterClient>) { db.runInTransaction {
        clients.forEach { c -> db.clients().save(ClientRow().apply {
            profileId=profile;mac=c.mac;updated=c.lastSeen
            payload=encrypt(JSONObject().put("ip",c.ip).put("hostname",c.hostname.orEmpty()).put("lease",c.leaseUntil)
                .put("first",c.firstSeen).put("last",c.lastSeen).put("policy",c.policy.name))
        }) }
    } }
    companion object {
        private val keyLock=Any()
        @Volatile private var dbInstance:BlackboxDatabase?=null
        private fun database(context:Context):BlackboxDatabase = dbInstance?:synchronized(this) {
            dbInstance?:Room.databaseBuilder(context,BlackboxDatabase::class.java,"blackbox.db")
                .addMigrations(object:Migration(1,2) {override fun migrate(db:SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE IF NOT EXISTS clients (profileId TEXT NOT NULL, mac TEXT NOT NULL, payload BLOB NOT NULL, updated INTEGER NOT NULL, PRIMARY KEY(profileId, mac))")
                }}).build().also {dbInstance=it}
        }
        fun encode(p:RouterProfile)=JSONObject().apply {
            put("id",p.id);put("name",p.name);put("ssid",p.ssid);put("password",p.password);put("mode",p.mode.name)
            put("gateway",p.gateway);put("prefix",p.prefix);put("first",p.first);put("last",p.last)
            put("dnsMode",p.dnsMode.name);put("dns",JSONArray(p.dns));put("blocked",JSONArray(p.blockedDomains))
            put("vpn",p.vpn.name);put("vpnInterface",p.vpnInterface);put("wpa3",p.wpa3);put("isolation",p.isolation);put("balanced",p.balanced)
            put("services",JSONArray(p.services.map { JSONObject().put("name",it.name).put("port",it.port).put("udp",it.udp) }))
        }
        private fun JSONObject.strings(key:String):List<String> = optJSONArray(key)?.let { a-> (0 until a.length()).map(a::getString) }.orEmpty()
        fun decode(j:JSONObject):RouterProfile {
            val services=j.optJSONArray("services")?.let { a ->
                (0 until a.length()).map { index ->
                    val s=a.getJSONObject(index)
                    LocalService(s.getString("name"),s.getInt("port"),s.optBoolean("udp"))
                }
            }.orEmpty()
            return RouterProfile(id=j.getString("id"),name=j.getString("name"),ssid=j.getString("ssid"),password=j.getString("password"),
                mode=RouterMode.valueOf(j.getString("mode")),gateway=j.getString("gateway"),prefix=j.getInt("prefix"),first=j.getString("first"),last=j.getString("last"),
                dnsMode=DnsMode.valueOf(j.getString("dnsMode")),dns=j.strings("dns"),blockedDomains=j.strings("blocked"),vpn=VpnPolicy.valueOf(j.getString("vpn")),vpnInterface=j.optString("vpnInterface"),
                wpa3=j.optBoolean("wpa3"),isolation=j.optBoolean("isolation"),services=services,balanced=j.optBoolean("balanced",true))
        }
    }
}
