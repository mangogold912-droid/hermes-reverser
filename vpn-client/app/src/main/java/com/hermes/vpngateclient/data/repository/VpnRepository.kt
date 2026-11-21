package com.hermes.vpngateclient.data.repository

import com.hermes.vpngateclient.data.remote.VpnGateApi
import com.hermes.vpngateclient.data.remote.VpnGateResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class VpnRepository(private val api: VpnGateApi = VpnGateApi()) {

    /** Fetches and parses the directory off the main thread (network + CSV parsing are heavy). */
    suspend fun getServers(): VpnGateResult = withContext(Dispatchers.Default) {
        api.parseVpnGate()
    }
}
