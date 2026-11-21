package com.hermes.vpngateclient.data.vpn

import android.content.Context
import android.content.Intent
import android.util.Base64
import android.util.Log
import de.blinkt.openvpn.core.ConfigParser
import de.blinkt.openvpn.core.OpenVPNService
import de.blinkt.openvpn.core.ProfileManager
import de.blinkt.openvpn.core.ProfileManager.setTemporaryProfile
import de.blinkt.openvpn.core.VPNLaunchHelper.startOpenVpn
import de.blinkt.openvpn.core.VpnStatus
import java.io.StringReader

class VpnManager(private val context: Context) {

    private val settings = context.getSharedPreferences(SETTINGS_NAME, Context.MODE_PRIVATE)
    private var activeConfig: String? = null
    private var sessionBrawlStarsDirectRoutingEnabled: Boolean? = null

    /** When enabled, Brawl Stars bypasses this VPN and uses Android's active network. */
    fun isBrawlStarsDirectRoutingEnabled(): Boolean =
        settings.getBoolean(KEY_BRAWL_STARS_DIRECT, false)

    fun setBrawlStarsDirectRoutingEnabled(enabled: Boolean) {
        settings.edit().putBoolean(KEY_BRAWL_STARS_DIRECT, enabled).apply()
    }

    /** Recover the route mode from a tunnel started by an older app process/version. */
    private fun activeProfileDirectRoute(): Boolean? {
        val profile = ProfileManager.getLastConnectedProfile(context) ?: return null
        val packageListed = profile.mAllowedAppsVpn?.contains(BRAWL_STARS_PACKAGE) == true
        return if (profile.mAllowedAppsVpnAreDisallowed) packageListed else !packageListed
    }

    /**
     * Launches the OpenVPN process for the given config.
     * @return true if the launch was initiated, false if config parsing/launch failed
     * (callers must surface that immediately instead of waiting on the watchdog).
     */
    fun startVpn(base64ConfigString: String): Boolean {
        return try {
            val existingTunnel = activeConfig != null || VpnStatus.isVPNActive()
            val directRouteForSession = sessionBrawlStarsDirectRoutingEnabled
                ?: if (existingTunnel) {
                    settings.getBoolean(
                        KEY_SESSION_BRAWL_STARS_DIRECT,
                        activeProfileDirectRoute() ?: isBrawlStarsDirectRoutingEnabled()
                    )
                } else {
                    isBrawlStarsDirectRoutingEnabled()
                }
            sessionBrawlStarsDirectRoutingEnabled = directRouteForSession
            settings.edit().putBoolean(KEY_SESSION_BRAWL_STARS_DIRECT, directRouteForSession).apply()
            activeConfig = base64ConfigString

            val decodedBytes = Base64.decode(base64ConfigString, Base64.DEFAULT)
            var ovpnConfigText = String(decodedBytes, Charsets.UTF_8)

            // Inject fail-fast, clean dynamic socket port, and graceful exit directives
            if (!ovpnConfigText.contains("nobind")) {
                ovpnConfigText += "\nnobind\n"
            }
            if (!ovpnConfigText.contains("connect-retry-max")) {
                ovpnConfigText += "\nconnect-retry-max 2\nconnect-timeout 8\nresolv-retry 2\n"
            }
            if (!ovpnConfigText.contains("explicit-exit-notify")) {
                ovpnConfigText += "\nexplicit-exit-notify 2\n"
            }

            val parser = ConfigParser()
            parser.parseConfig(StringReader(ovpnConfigText))
            val profile = parser.convertProfile()

            profile.mUsername = "vpn"
            profile.mPassword = "vpn"
            profile.mProfileCreator = context.packageName

            if (directRouteForSession) {
                // Keep game packets on Android's active physical network so replacing
                // a VPN Gate relay does not move the game's route between VPN exits.
                profile.mAllowedAppsVpnAreDisallowed = true
                profile.mAllowedAppsVpn = hashSetOf(BRAWL_STARS_PACKAGE)
            }

            setTemporaryProfile(context, profile)
            startOpenVpn(profile, context, "VPN Gate Connection", true)
            true

        } catch (e: Exception) {
            Log.e(TAG, "startVpn failed to parse/launch config", e)
            false
        }
    }

    /** @return true if a stop request was dispatched to the service. */
    fun stopVpn(): Boolean {
        return try {
            ProfileManager.setConntectedVpnProfileDisconnected(context)

            val disconnectIntent = Intent(context, OpenVPNService::class.java).apply {
                action = OpenVPNService.DISCONNECT_VPN
            }
            context.startService(disconnectIntent)

            activeConfig = null
            sessionBrawlStarsDirectRoutingEnabled = null
            settings.edit().remove(KEY_SESSION_BRAWL_STARS_DIRECT).apply()
            true
        } catch (e: Exception) {
            Log.e(TAG, "stopVpn failed", e)
            false
        }
    }

    fun clearActiveConfig() {
        activeConfig = null
        sessionBrawlStarsDirectRoutingEnabled = null
        settings.edit().remove(KEY_SESSION_BRAWL_STARS_DIRECT).apply()
    }

    /** Tears down the tunnel but remembers its config so resume can restart it. */
    fun pauseVpn(): Boolean {
        val lastRunning = activeConfig
        val lastRouteMode = sessionBrawlStarsDirectRoutingEnabled
        val stopped = stopVpn()
        // Keep the profile and per-session routing mode so resume doesn't apply a
        // pending preference change in the middle of a game session.
        activeConfig = lastRunning
        sessionBrawlStarsDirectRoutingEnabled = lastRouteMode
        if (lastRouteMode != null) {
            settings.edit().putBoolean(KEY_SESSION_BRAWL_STARS_DIRECT, lastRouteMode).apply()
        }
        return stopped
    }

    /**
     * Restarts the paused tunnel from its stored config.
     * @return false when there is no stored session to resume — the caller must fail
     * fast ("session expired") instead of showing RECONNECTING until the watchdog.
     */
    fun resumeVpn(fallbackConfig: String? = null): Boolean {
        val configToResume = activeConfig ?: fallbackConfig
        return if (configToResume != null) {
            startVpn(configToResume)
        } else {
            Log.w(TAG, "resumeVpn called with no stored or fallback config")
            false
        }
    }

    companion object {
        private const val TAG = "VpnManager"
        private const val SETTINGS_NAME = "gatelink_settings"
        private const val KEY_BRAWL_STARS_DIRECT = "brawl_stars_direct_routing"
        private const val KEY_SESSION_BRAWL_STARS_DIRECT = "session_brawl_stars_direct_routing"
        private const val BRAWL_STARS_PACKAGE = "com.supercell.brawlstars"
    }
}
