# Native router validation â€” 2026-09-17

These tests use the real rooted Redmi and a real Windows Wi-Fi client. No simulated networking command is accepted as evidence.

## Observed passes

| Test | Evidence |
|---|---|
| Offline AP lifecycle | WPA2 hostapd starts; gateway and local DNS answer; stop removes owned rules and restores captured policy/forwarding values |
| Wi-Fi repeater | Phone retains existing Wi-Fi; laptop gets a 192.168.50.x lease, resolves DNS and fetches HTTPS example.com |
| Client control | LAN_ONLY blocks client HTTPS; NORMAL restores it |
| VPN no-fallback | Real temporary WireGuard interface without a peer; public IP and external DNS blocked before and after interface removal; local DNS remains available |
| Forced process death | Root watchdog cleanup in 10.9 seconds initially and 14.7 seconds with the final coordinated recovery script; no owned AP/firewall remains; exact captured policy rules and both forwarding values restored |
| Offline services | Laptop resolves dashboard.blackbox and reads the built-in HTTP status; domain blocking returns NXDOMAIN; offline Internet blocked |
| Storage/UI | Encrypted profile/client policy round-trip, six-page navigation, Developer Mode, root identity and read-only diagnostics |

The VPN test proves blocking, not successful routing through a remote VPN. An unpeered interface is intentionally unable to deliver Internet. WireGuard key/configuration management is not implemented.

A custom-DNS repeater test initially timed out: native resolver traffic needs explicit Android policy routing. The backend now binds resolver sockets and a scoped output-interface routing rule to the selected upstream. The repeated laptop test passed custom DNS (1.1.1.1 / 9.9.9.9), HTTPS, domain blocking, dashboard HTTP and LAN_ONLY/NORMAL policy changes. Phone-local HTTPS also passed while BLACKBOX ran. A real client produced nonzero forwarded counters (about 4.7 MB down / 0.4 MB up in that session).

## Final software checks

- 45 JVM tests: zero failures/errors/skips.
- Android suite: 8 passed; external-client and owner-provisioning fixtures intentionally skipped unless opted in (10 tests reported, 2 assumptions).
- Separate real-client fixtures passed for repeater/services, offline services and VPN no-fallback.
- Startup cancellation, normal stop and two concurrent recovery processes passed. The concurrency test executes undo exactly once.
- The first recovery-lock implementation exposed Android mksh close-on-exec behavior on descriptor 9. It retained its recovery journal. The implementation was corrected to an inherited standard descriptor, the interrupted interface was recovered, and the complete device suite plus forced-crash test passed again.
- Debug APK and R8 release APK build. Release artifact is unsigned; the installed development APK is debug-signed.
- Lint: zero errors, ten dependency-update warnings. Dependencies remain pinned intentionally.
- Owner setup created Travel Wi-Fi and Offline LAN profiles with generated encrypted credentials and the built-in dashboard. BLACKBOX is left stopped.

## Repeat a real client test

Install the app and instrumentation APK after building. In one terminal:

```powershell
. .\scripts\env.ps1
adb shell am instrument -w -r -e clientTests true -e servicesTest true -e class dev.blackbox.router.RouterClientTest dev.blackbox.router.test/androidx.test.runner.AndroidJUnitRunner
```

Wait for the private ready file (do not print its contents; it includes a temporary Wi-Fi password):

```powershell
adb shell run-as dev.blackbox.router ls files/client-test-ready.json
python scripts\test-windows-client.py
```

Use `-e mode OFFLINE` for offline services, or `-e vpnTest true` without servicesTest for the VPN kill-switch fixture. The test harness creates a temporary current-user Windows WLAN profile, switches Wi-Fi, runs checks, restores the original profile and deletes its own profile in finally. No existing Wi-Fi credentials are exported. The phone fixture stops and recovers in finally. Repeated live tests should not run concurrently.

## Forced-crash test

With the router stopped and both APKs installed:

```powershell
python scripts\test-watchdog.py
```

This starts its own offline fixture and intentionally force-stops BLACKBOX. The instrumentation crash is expected; the independent host harness verifies cleanup and exact captured policy/forwarding values. It does not erase app data. A failed cleanup leaves the journal for app recovery.

## Recovery

Private app files under router-sessions contain snapshots, undo intentions, process configuration and a recovery script. Each intention is atomically persisted before its command. Undo operations are scoped to the session; they do not restore whole old iptables dumps over current Android state. Interface ifindex checks avoid deleting a replacement interface. After undo, verification checks owned interfaces, firewall names, tables and priorities; failure retains the journal.

The root watchdog checks the app PID plus its process-start identity. After process death and heartbeat expiry it runs recovery independently. An alive but unresponsive process is not killed by this watchdog, avoiding false cleanup during Android sleep. Crash/reboot-at-every-step and long Doze tests remain outstanding.

## Private local artifacts

Ignored device-reports/ contains Windows repeater, offline-service, VPN and watchdog JSON results, the container-prerequisite report and screenshots. Ignored .tools/ contains build/device logs. The test ready file is private and ephemeral; do not publish it. Historical read-only milestones are in [VALIDATION.md](VALIDATION.md).

## Limits

WPA3, two-client isolation, mobile/Ethernet, remote VPN success, external service-boundary probing and extended thermal/Doze reliability need additional tests. The phone's forwarding state was already enabled in observed sessions; the newly added netd requester path for initially disabled forwarding still needs a dedicated device test. IPv6 routing and nftables-only devices are unsupported.
