# Remaining production work

The native development router is operational. These are explicit remaining requirements, not completed or simulated features.

1. **Security and distribution:** replace/rebuild the root-retaining dnsmasq fork with an audited privilege-drop design; independently reproduce native tools; finish linked-library/license compliance; select app licensing and release signing.
2. **Device coverage:** Android/OEM versions, WPA3, same-channel restrictions, DFS/CAC, Android tethering interactions, cellular/Ethernet and alternate root managers. An nftables-only backend is not implemented.
3. **VPN completeness:** working remote TUN/WireGuard tunnel, reconnect/interface recreation, Android always-on VPN, independent per-client VPN and per-client DNS overrides. Current VPN-required client control requires an ALL_CLIENTS profile; VPN DNS isolation needs root-manager effective-GID support.
4. **Isolation/security tests:** two simultaneous clients for layer-2 isolation; external service-port access attempts; malformed DHCP/DNS/HTTP inputs; address/MAC changes. MAC-based client policies are not strong identity/authentication.
5. **Reliability:** multi-day traffic, screen-off/Doze, thermal/load tests; reboot/power loss at each startup step; daemon/resource exhaustion; concurrent Android changes. Uncertain ownership or failed cleanup keeps the recovery journal.
6. **UX:** persistent sanitized runtime reports, richer interrupted-session inspection, profile export/import with secret handling, tool provisioning UX and more end-to-end diagnostics. Current copyable command report covers hardware; runtime logs are session-local.
7. **OpenWrt:** prerequisites only. This kernel lacks PID namespaces and IPC unshare fails. Never run OpenWrt init in Android's host namespace or call chroot isolation. A future isolated userspace experiment needs a reviewed lifecycle adapter; kernel changes are outside the app.

Each milestone must preserve functional native routing, run meaningful tests and report failures honestly. OpenWrt remains optional.
