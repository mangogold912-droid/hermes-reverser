package com.hermes.vpngateclient.data.model

/** Selection helpers for directory-reported latency (not a live probe from this device). */
internal object ServerSelection {
    /**
     * Pick the lowest plausible reported ping. VPN Gate sometimes reports tiny or
     * missing values; ignore sub-15 ms reports when any plausible sample exists,
     * then fall back to other positive reports, and finally to the quality score.
     */
    fun <T> bestReportedPing(
        servers: List<T>,
        ping: (T) -> Int,
        quality: (T) -> Double
    ): T? {
        if (servers.isEmpty()) return null

        val plausible = servers.filter { ping(it) in MIN_PLAUSIBLE_PING_MS..MAX_REPORTED_PING_MS }
        val known = servers.filter { ping(it) in 1..MAX_REPORTED_PING_MS }
        val candidates = plausible.ifEmpty { known.ifEmpty { servers } }

        return candidates.minWithOrNull(
            compareBy<T> { ping(it).takeIf { value -> value in 1..MAX_REPORTED_PING_MS } ?: Int.MAX_VALUE }
                .thenByDescending(quality)
        )
    }

    fun bestPingServer(servers: List<VpnServer>): VpnServer? =
        bestReportedPing(servers, VpnServer::ping) { it.score.toDouble() }

    fun bestPingDisplay(servers: List<VpnServerDisplay>): VpnServerDisplay? =
        bestReportedPing(servers, VpnServerDisplay::ping, VpnServerDisplay::overallRankScore)

    private const val MIN_PLAUSIBLE_PING_MS = 15
    private const val MAX_REPORTED_PING_MS = 600
}
