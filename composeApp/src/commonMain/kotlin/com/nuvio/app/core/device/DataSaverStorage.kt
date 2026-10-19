package com.nuvio.app.core.device

internal expect object DataSaverStorage {
    fun loadPayload(): String?
    fun savePayload(payload: String)
}
