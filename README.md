# BLACKBOX

Native Android travel-router application, **0.3.0-dev**. The attached rooted Redmi runs a separate WPA2 access point while keeping its existing Wi-Fi connection, with DHCP, DNS, IPv4 routing, client controls and recovery. This is a working development build, not a production certification across Android devices.

## Implemented

- Kotlin, Compose, Material 3 and six working pages: Home, Clients, Network, Services, Logs, Settings.
- Real libsu root authorization; hardware discovery; parsers for interfaces, routes, policy rules and Wi-Fi PHY combinations; sanitized diagnostic exports.
- Offline LAN, normal Internet and Wi-Fi repeater profiles. Dynamic PHY/interface/channel selection, subnet overlap detection, WPA2 and optional WPA3 configuration, hostapd client isolation.
- Dedicated hostapd and dnsmasq instances, private DHCP leases, local .blackbox DNS, system/custom DNS and user-supplied domain blocking.
- Scoped IPv4 firewall/NAT and dedicated routing tables. Unsolicited upstream-to-LAN traffic is denied. IPv6 is blocked on BLACKBOX interfaces until routed IPv6 is implemented.
- Existing TUN/WireGuard gateway selection, phone-only VPN policy, downstream VPN policy with route and DNS kill switches. BLACKBOX does not establish VPN tunnels or import VPN credentials.
- Client discovery, first/last seen, persistent per-profile access policies, approximate forwarded byte/rate counters and aggregate interface rates.
- Normal / LAN-only / paused client controls. VPN-required clients currently require an ALL_CLIENTS VPN profile. Independent per-client VPN selection and per-client DNS overrides remain unimplemented.
- Room profiles encrypted with Android Keystore AES-GCM, encrypted client-history payloads, disabled Android backup.
- LAN-only read-only web dashboard at http://dashboard.blackbox:8080, plus local names and port boundaries for existing Termux/Android services.
- Foreground service, Open/Stop notification, startup cancellation, intent-before-change disk journal, reverse cleanup, interface ownership checks, cleanup verification and independent root watchdog.
- Battery/charging/temperature/thermal information; 30-second health sampling during routing; 5-second balanced or 2-second performance monitoring.

See [device compatibility](docs/DEVICE-COMPATIBILITY.md), [runtime validation](docs/RUNTIME-VALIDATION.md), [backend decision](docs/BACKEND-DECISION.md), and [remaining work](docs/NEXT-PHASE.md). Capabilities are never simulated.

## Build and install

Requires JDK 17, SDK platform 35/build-tools 35.0.0. Pinned build tools: AGP 8.9.2, Gradle 8.11.1, Kotlin 2.1.20. These are reproducible choices, not a claim that they are the latest releases.

```powershell
cd C:\Users\gujja\redmi-exp\router
# Initial setup only; accepts the requested Android SDK package licenses.
.\scripts\setup-tools.ps1 -AcceptLicenses
. .\scripts\env.ps1
.\gradlew.bat :core:test :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --max-workers=2
adb install -r app\build\outputs\apk\debug\app-debug.apk

# Already rooted arm64 phone: prepare and provision checksum-pinned tools.
python scripts\prepare-network-tools.py
python scripts\install-runtime-tools.py
adb shell am start -n dev.blackbox.router/.MainActivity
```

Native tools are installed separately under /data/adb/blackbox/tools and **not bundled in the APK**. Provisioning and runtime both verify exact SHA-256 hashes. See [sources and licensing](docs/NETWORK-TOOLS.md). APK installation alone does not provision another phone.

The attached Redmi is already rooted and provisioned. On another phone, root setup remains a separate owner task; BLACKBOX never unlocks, roots, flashes a kernel or changes SELinux mode. USB authorization and a root-manager authorization tap may require the owner. The app and ADB shell have separate root grants.

## Use

1. Open **Network**, save a profile and inspect its generated Wi-Fi password. The service entry `dashboard:8080:tcp` enables the built-in offline page.
2. Choose **OFFLINE** for local-only operation, **REPEATER** for Wi-Fi upstream, or **INTERNET** for the selected Android Internet network.
3. Stop any existing Android hotspot. BLACKBOX refuses to take over an active AP.
4. From **Home**, select the profile and tap **START ROUTER**. Connect a second device to its SSID.
5. Stop from the app or notification. If an interrupted session remains, use **RECOVER NETWORK** before restarting.

Default subnet: 192.168.50.0/24, gateway 192.168.50.1, DHCP .10â€“.200. Change it if it overlaps the upstream. Profile edits apply at the next start. RUNNING means local infrastructure is ready, not that a remote Internet/VPN endpoint was reached. Upstream changes can require a restart.

External Termux/Android servers must bind to the private gateway address. BLACKBOX installs temporary service-port boundaries while running; it does not manage external server lifetimes.

## Test and debug

```powershell
. .\scripts\env.ps1
.\gradlew.bat :core:test
adb install -r app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
# Read-only, storage and UI tests; unlock the screen for Compose tests.
adb shell am instrument -w -r dev.blackbox.router.test/androidx.test.runner.AndroidJUnitRunner
# Real root identity test.
adb shell am instrument -w -r -e rootTests true -e class dev.blackbox.router.root.RootDeviceTest dev.blackbox.router.test/androidx.test.runner.AndroidJUnitRunner
# MUTATING offline AP/DNS/cleanup test; existing Android hotspot must be off.
adb shell am instrument -w -r -e routerTests true -e class dev.blackbox.router.RouterLifecycleTest dev.blackbox.router.test/androidx.test.runner.AndroidJUnitRunner
python scripts\probe-device.py --request-root
python scripts\probe-container-support.py
adb logcat -d -b crash
```

External-client tests temporarily switch the computer's Wi-Fi and restore its original profile in finally. Commands and scope: [runtime validation](docs/RUNTIME-VALIDATION.md). Root/mutating tests are opt-in.

**Network â†’ Hardware compatibility** shows the read-only scan. **Settings â†’ Developer Mode**, then **Logs â†’ Hardware command diagnostics**, shows sanitized evidence. Diagnostic copies redact addresses and sensitive fields. Private recovery snapshots contain real network state; do not share them unredacted. No upstream Wi-Fi credential files are read.

## Code layout

core/ contains pure Kotlin models, diagnostics, parsers, validators, configuration generators, firewall plans, traffic parsing and transaction tests. app/ owns Android APIs, Compose/ViewModels, libsu, Room/Keystore, the foreground controller, disk journal/watchdog and LAN HTTP server. Runtime privileged commands use the central root executor; ViewModels do not execute shell commands.

The diagnostic RouterBackend is an inspection contract. The native lifecycle is implemented in RouterController/RouterService; VirtualAP and OpenWrt lifecycle adapters are not implemented. OpenWrt is not a dependency. This Redmi lacks PID/IPC namespace support needed by the planned full init container.

## Practical limits

Actual tests cover WPA2 offline LAN/repeater, DHCP/DNS, HTTPS, client blocking, VPN no-fallback and crash cleanup. A working external VPN tunnel, WPA3, two-client isolation, cellular/Ethernet upstream, IPv6 routing and long unattended operation need separate tests. Pure nftables-only devices are not supported yet: runtime requires compatible iptables, ip6tables, save and restore tools.

The pinned dnsmasq fork runs as root. Privilege reduction, complete redistribution review, broader device coverage, power-loss/Doze/thermal soak tests and signed release delivery remain production gates. See [remaining work](docs/NEXT-PHASE.md).
