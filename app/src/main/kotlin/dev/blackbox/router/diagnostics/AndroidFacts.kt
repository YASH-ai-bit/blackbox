package dev.blackbox.router.diagnostics

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import dev.blackbox.core.DeviceFacts

class AndroidFacts(private val context: Context) {
    fun read(): DeviceFacts {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val active = runCatching { connectivity.activeNetwork }.getOrNull()
        val caps = active?.let { runCatching { connectivity.getNetworkCapabilities(it) }.getOrNull() }
        val link = active?.let { runCatching { connectivity.getLinkProperties(it) }.getOrNull() }
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val temperature = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        val thermal = if (Build.VERSION.SDK_INT >= 29) runCatching {
            when (context.getSystemService(PowerManager::class.java).currentThermalStatus) {
                PowerManager.THERMAL_STATUS_NONE -> "Normal"
                PowerManager.THERMAL_STATUS_LIGHT -> "Light"
                PowerManager.THERMAL_STATUS_MODERATE -> "Moderate"
                PowerManager.THERMAL_STATUS_SEVERE -> "Severe"
                PowerManager.THERMAL_STATUS_CRITICAL -> "Critical"
                PowerManager.THERMAL_STATUS_EMERGENCY -> "Emergency"
                PowerManager.THERMAL_STATUS_SHUTDOWN -> "Shutdown"
                else -> "Unknown"
            }
        }.getOrNull() else null
        return DeviceFacts(Build.MANUFACTURER, Build.MODEL, Build.VERSION.RELEASE, Build.VERSION.SDK_INT,
            Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown", context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI),
            if (Build.VERSION.SDK_INT >= 30) runCatching { wifi.isStaApConcurrencySupported }.getOrNull() else null,
            link?.interfaceName,
            when {
                caps == null -> null
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile data"
                else -> "Other"
            }, caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            link?.dnsServers?.mapNotNull { it.hostAddress } ?: emptyList(), link?.linkAddresses?.map { it.toString() } ?: emptyList(),
            if (scale > 0 && level >= 0) 100 * level / scale else null,
            battery?.let { it.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0 },
            temperature?.takeIf { it != Int.MIN_VALUE }?.div(10f), thermal)
    }
}
