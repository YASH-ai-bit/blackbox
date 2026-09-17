# Backend decision

**B â€” reuse compatible components; implement BLACKBOX's own lifecycle.** The working runtime uses independently generated hostapd/dnsmasq configuration and a native root controller with owned interfaces, chains, routing tables and recovery journal.

[VirtualAP](https://github.com/ravindu644/VirtualAP) was checked on 2026-09-17: not archived; [v1.8.0](https://github.com/ravindu644/VirtualAP/releases/tag/v1.8.0) published 2026-09-16; source commit 720f2cb51f3db58cef37708f8712780e53cad5b8. Recent maintenance is evidence of activity, not a reliability certification.

Its [start-ap backend](https://github.com/ravindu644/VirtualAP/blob/720f2cb51f3db58cef37708f8712780e53cad5b8/backend/start-ap) uses defaults and cleanup assumptions that do not directly satisfy BLACKBOX's per-session ownership requirements. Its scripts were not executed, copied into the app, or used as a drop-in backend. The official release was inspected as an archive to obtain pinned native components for this owner's device. The VirtualAP APK was never installed.

VirtualAP declares [GPL-3.0](https://github.com/ravindu644/VirtualAP/blob/720f2cb51f3db58cef37708f8712780e53cad5b8/LICENSE). Native component sources, license notices and build recipes are cached separately. No native tool is bundled in BLACKBOX's APK or tracked source. Public redistribution requires a complete component/linkage/license review. See [NETWORK-TOOLS.md](NETWORK-TOOLS.md).

## Android-specific behavior

[AOSP STA/AP guidance](https://source.android.com/docs/core/connect/wifi-sta-ap-concurrency) and [HAL combinations](https://source.android.com/docs/core/connect/wifi-hal) describe separate framework/driver evidence. BLACKBOX keeps missing evidence UNKNOWN and validates actual startup. The attached Qualcomm advertises two channels; actual 5 GHz STA plus 2.4 GHz AP worked.

Android shell soft-AP management was tested and allocated Android-owned LAN state on this ROM. BLACKBOX instead creates a uniquely named AP interface with iw, refuses an existing active AP, and leaves idle Android interfaces untouched. It does not toggle Wi-Fi or change country/regulatory settings. Required DFS/CAC is currently rejected.

The native controller requires usable xtables command tools. It uses private IPv4 chains and routing tables, blocks IPv6 on its LAN, binds DNS to the selected upstream, and uses an isolated resolver effective group for the VPN DNS output guard. Pure nftables support is not implemented.

[libsu](https://github.com/topjohnwu/libsu) owns verified private shells. All generated privileged commands use the centralized executor. The UI does not accept arbitrary root-shell text. Foreground-service ownership and a separate root watchdog survive Activity loss and app-process death respectively.

## OpenWrt experiment

The [official OpenWrt container project](https://github.com/openwrt/docker) notes that a runtime uses multiple services; a root filesystem alone is not a router integration. The attached kernel allows network/mount/UTS namespaces but has CONFIG_PID_NS disabled, and IPC namespace creation fails. The probe does not mount anything or start container init.

No OpenWrt rootfs or init was installed. The full init-container backend remains unavailable; the native router has no OpenWrt dependency. A restricted namespace/chroot experiment would not justify claiming an isolated full OpenWrt router. See [remaining work](NEXT-PHASE.md).
