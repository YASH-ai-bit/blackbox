# Attached Redmi compatibility â€” 2026-09-17

**Verdict: native IPv4 offline LAN and Wi-Fi repeater work on this phone.** A Windows laptop connected through BLACKBOX and completed DHCP, DNS and HTTPS tests while Android retained its existing Wi-Fi connection.

| Capability | Observed evidence |
|---|---|
| Device | Redmi M2010J19SG, lime, Qualcomm bengal, arm64-v8a |
| Android / kernel | Android 13 / API 33; 4.19.225-lilium+ |
| Root | Magisk 30.7; ADB and BLACKBOX execute with UID 0 |
| SELinux | Enforcing throughout tests |
| STA + AP | Driver advertises managed â‰¤2, AP â‰¤2, total â‰¤4, channels â‰¤2; matching beacon interval requirement |
| Actual concurrency | Existing 5 GHz STA retained with a new owned 2.4 GHz channel-6 AP |
| AP backend | Checksum-pinned hostapd on a dynamically named bbâ€¦ interface |
| WPA2 / DHCP / local DNS | Verified from a real laptop |
| DNS / Internet NAT | External DNS and HTTPS verified from laptop |
| Client LAN-only policy | Blocks new Internet requests; normal policy restores them |
| Offline services | dashboard.blackbox:8080/status.json verified from laptop; Internet blocked in offline mode |
| WireGuard kernel support | A real temporary WireGuard interface can be created and removed |
| VPN kill switch | Direct IP and external DNS blocked with unpeered WireGuard and after interface removal |
| Working VPN endpoint | Not tested; no external peer configuration provided |
| Forced app-process death | Watchdog removed AP/firewall and restored captured routing/forwarding in 10.9–14.7 seconds in two tests |
| OpenWrt full init container | Unavailable: CONFIG_PID_NS disabled; PID/IPC unshare probes fail |

WPA3 and isolation are configurable but unverified with suitable clients. Cellular/Ethernet modes use the same routing engine but need separate tests. IPv6 is blocked on the private LAN. Findings apply to this phone/build, not every Redmi or Qualcomm device.

Android's hotspot manager was evaluated and allocated its own LAN configuration. BLACKBOX therefore uses an independently owned AP and refuses an already-active hotspot. An idle Android-created wlan1 is left untouched.

Rooting/flashing is not included in BLACKBOX. The separate authorized setup is recorded in [ROOT-SETUP.md](ROOT-SETUP.md); original boot backups remain outside version control. [Runtime validation](RUNTIME-VALIDATION.md) explains scope and repeat commands.
