package com.nuvio.app.core.device

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager

actual object DeviceEnvironment {
    private var appContext: Context? = null

    /** Called once from [com.nuvio.app.MainActivity] during startup. */
    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * Test-only override for [isWifi]. When non-null, [isWifi] returns this value instead of
     * querying the ConnectivityManager. Robolectric tests have no real network and never call
     * [initialize], so they set this to simulate WiFi. Always null in production.
     */
    internal var testWifiOverride: Boolean? = null

    /**
     * Test-only override for [isUnmetered], mirroring [testWifiOverride].
     * When null but [testWifiOverride] is set, the simulated WiFi counts as
     * unmetered (an ordinary home network). Always null in production.
     */
    internal var testUnmeteredOverride: Boolean? = null

    actual fun isWifi(): Boolean = testWifiOverride ?: (activeTransport() == NetworkCapabilities.TRANSPORT_WIFI)

    actual fun isCellular(): Boolean = activeTransport() == NetworkCapabilities.TRANSPORT_CELLULAR

    actual fun isUnmetered(): Boolean =
        testUnmeteredOverride
            ?: testWifiOverride
            ?: activeCapabilities()?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            ?: false

    private fun activeCapabilities(): NetworkCapabilities? {
        val connectivityManager =
            appContext?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return null
        return connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
    }

    private fun activeTransport(): Int? {
        val capabilities = activeCapabilities() ?: return null
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ->
                NetworkCapabilities.TRANSPORT_WIFI
            // Ethernet is a wifi-like (unmetered, local-network) transport:
            // report it as Wi-Fi so consumers such as the offline-home
            // classifier don't fall through to probe-dependent Unknown/Offline.
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ->
                NetworkCapabilities.TRANSPORT_WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ->
                NetworkCapabilities.TRANSPORT_CELLULAR
            else -> null
        }
    }

    actual fun batteryPercent(): Int {
        val batteryManager =
            appContext?.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                ?: return -1
        val level = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return if (level in 0..100) level else -1
    }
}
