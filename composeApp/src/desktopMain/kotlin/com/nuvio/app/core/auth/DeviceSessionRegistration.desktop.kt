package com.nuvio.app.core.auth

internal actual fun currentDeviceClientMetadata(): DeviceClientMetadata {
    val osName = System.getProperty("os.name").orEmpty()
    val osVersion = System.getProperty("os.version").orEmpty()
    val deviceName = formatDeviceName(
        manufacturer = "",
        model = osName,
        fallback = "Desktop device",
    )
    val platform = if (osVersion.isBlank()) "Desktop" else "Desktop $osVersion"

    return DeviceClientMetadata(
        deviceName = deviceName,
        platform = platform,
    )
}
