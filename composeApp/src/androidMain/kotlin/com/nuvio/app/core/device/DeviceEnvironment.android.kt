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

    actual fun isWifi(): Boolean = activeTransport() == NetworkCapabilities.TRANSPORT_WIFI

    actual fun isCellular(): Boolean = activeTransport() == NetworkCapabilities.TRANSPORT_CELLULAR

    private fun activeTransport(): Int? {
        val connectivityManager =
            appContext?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return null
        val capabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
            ?: return null
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ->
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
