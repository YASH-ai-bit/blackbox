package dev.blackbox.core

import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object HostapdConfig {
    fun generate(p:RouterProfile,lan:String,channel:Int,directory:String):String {
        p.validate();require(validInterface(lan));require(channel in 1..14 || channel in 36..165)
        val hex=p.ssid.toByteArray().joinToString("") {"%02x".format(it)}
        val psk=SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(PBEKeySpec(p.password.toCharArray(),p.ssid.toByteArray(),4096,256)).encoded.joinToString("") {"%02x".format(it)}
        return buildString {
            appendLine("interface=$lan\ndriver=nl80211\nssid2=$hex\nhw_mode=${if(channel<=14) "g" else "a"}\nchannel=$channel")
            appendLine("ctrl_interface=$directory/hostapd\nwmm_enabled=1\nauth_algs=1\nwpa=2\nwpa_key_mgmt=${if(p.wpa3) "SAE" else "WPA-PSK"}\nrsn_pairwise=CCMP")
            if(p.wpa3) appendLine("sae_password=${p.password}\nieee80211w=2") else appendLine("wpa_psk=$psk")
            appendLine("ap_isolate=${if(p.isolation) 1 else 0}\nbeacon_int=100\nmax_num_sta=32")
        }
    }
}
