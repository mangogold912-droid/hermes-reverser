# GateLink VPN for Android

GateLink VPN is a standalone Android client for the public VPN Gate volunteer relay network. It is based on the GPL-3.0 OpenGateVPN project at the pinned upstream revision listed in `SOURCE_INFO.txt`.

The app downloads the VPN Gate server directory, selects a recommended OpenVPN relay, and runs the tunnel inside the APK. The user does not need to import a `.ovpn` file or install a second VPN app. Android will show its standard VPN permission prompt on the first connection.

## Compatibility

- Android 7.0 / API 24 or later.
- ARM64 (`arm64-v8a`) and 32-bit ARM (`armeabi-v7a`) devices.
- Package ID: `com.hermes.vpngateclient`.

## Important relay warning

VPN Gate relays are volunteered and operated by third parties. The relay operator may observe traffic metadata, and the VPN Gate project documents logging of relay-related metadata. Do not use this network for sensitive traffic or treat it as a private, no-logs service. Relay availability, speed, game latency, and session stability can change at any time; this app does not guarantee better Brawl Stars performance.

The app fetches its public server directory from `https://www.vpngate.net/api/iphone/` and then connects directly to the selected volunteer relay. It does not include an account system or a developer-operated VPN gateway.

## Build

Requirements: JDK 21, Android SDK Platform 37, and Build Tools 36.0.0. The project includes the Gradle wrapper.

```bash
./gradlew testDebugUnitTest assembleDebug
```

The installable debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Debug builds are signed with the machine's Android debug key. Release builds are unsigned unless a private release keystore is configured locally; never commit a keystore or its passwords.

To create a single shareable ZIP containing the APK, external license/notice TXT files, and source archive:

```bash
python3 scripts/package_distribution.py \
  --apk app/build/outputs/apk/debug/app-debug.apk \
  --out distribution/GateLinkVPN-Bundle.zip
```

The license and source materials are provided beside the APK in that ZIP, not in the app's screens. See `INSTALL.txt`, `LICENSE`, `THIRD_PARTY_NOTICES.txt`, and `SOURCE_INFO.txt`.

## Source and licensing

This derivative is distributed under GPL-3.0. The source archive created by the packaging script contains the modified Android project, build files, bundled Java engine sources, native libraries, and license documents. The native OpenVPN sources used to produce the bundled engine binaries are available at the pinned OpenVPN for Android revision with its submodules; `scripts/fetch-openvpn-native-source.sh` fetches that source tree for reproducible source access.

This is an unofficial client and is not affiliated with VPN Gate, the University of Tsukuba, or the OpenGateVPN author. License notices are kept outside the app UI and included in the share bundle.
