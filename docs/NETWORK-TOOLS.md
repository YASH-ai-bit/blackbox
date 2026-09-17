# Native networking tools — 2026-09-17

The owner-authorized runtime now uses checksum-pinned iw, hostapd and dnsmasq at `/data/adb/blackbox/tools`. All three are root-owned and mode 0700; runtime verifies hashes before starting. They are not bundled in the APK. Root access works with SELinux Enforcing.

The earlier isolated iw probe failed in ordinary ADB shell context because SELinux denied generic-netlink creation. The same queries succeed through Magisk root. Actual standalone hostapd and dnsmasq have now passed AP, DHCP and DNS client tests. dnsmasq reports its version as UNKNOWN; no version is inferred.

## Provenance and limits

The payloads came from the official [VirtualAP v1.8.0 release](https://github.com/ravindu644/VirtualAP/releases/tag/v1.8.0), published 2026-09-16. Release source commit: `720f2cb51f3db58cef37708f8712780e53cad5b8`.

The APK was downloaded as an archive, **not installed**. Its SHA-256 matches the GitHub release-asset digest:

```text
5283178db14cb115e6ddffa2521e1a9e16b29e63eb06b11c79b35d12faf8cfbe
```

Android SDK apksigner verified its v2/v3 signature. Signer certificate SHA-256:

```text
ed9e2fec17cb87b57fb9d7645e95a1d510ab84b8a84fb9009c4cc39382d9cadd
```

This establishes archive integrity and signature consistency, not independent trust in the publisher. The reviewed recipe builds static arm64 payloads using Alpine/musl, libnl and OpenSSL. No upstream build or router scripts were executed. No independent reproducible build or comprehensive security audit was performed.

| Tool | SHA-256 of extracted executable | Pinned component source |
| --- | --- | --- |
| iw | `3314eceb85558bd5c81556b225bd4bb9a77d494d83bbc3c27a3f5eb512a937c3` | [f9081ee](https://github.com/Droidspaces/iw-vap/tree/f9081ee3c6c092e88da00d3959af7add56538295) |
| hostapd | `f5b6fd733b71ce7662189cd4c86dc26933225673376bdc1ccfc1ec4999befbe9` | [fadc549](https://github.com/Droidspaces/hostapd-vap/tree/fadc5497548ba253ee3a652799a551d059df212b) |
| dnsmasq | `916c1455f639b175e34f9eb775e0d6dc1570d141c83cd50913ffca68bc92edee` | [02edbd5](https://github.com/Droidspaces/dnsmasq-vap/tree/02edbd5d1341112902543671a231d47f374a2778) |

Component archives, primary COPYING files (plus hostapd README terms), build-recipe copies, APK-signature output, and a checksum manifest are retained under `.tools/router-toolchain/virtualap-v1.8.0/`. These are ignored local artifacts, not application dependencies or redistributed BLACKBOX binaries. Source archive hashes are recorded locally; the archives are fetched by pinned commit rather than independently rebuilt.

The dnsmasq fork includes Android adaptations that retain root rather than dropping privileges through the normal user lookup. That needs a deliberate privilege and SELinux design before production use. The hostapd fork also contains Android-specific changes; runtime compatibility still requires device-specific validation. Full component and linked-library license review remains necessary before bundling/distribution.

## Provision and inspect

```powershell
. .\scripts\env.ps1
python scripts\prepare-network-tools.py
python scripts\install-runtime-tools.py
python scripts\probe-device.py --request-root
```

The preparation script validates the pinned archive digest and ELF architecture/linkage. Provisioning checks root and arm64 ABI, rejects symbolic-link destinations, verifies root directory ownership, copies only the three verified tools and checks hashes again. Root is installed separately; the script never flashes or reboots.

Earlier isolated diagnostics remain at `/data/local/tmp/blackbox-diagnostics-3314eceb8555/iw`; this is no longer the runtime tool path. Native services run only during a BLACKBOX session. They are not boot services or a Magisk networking module.

The root-retaining dnsmasq fork is a production-hardening limitation. APK/source distributions must not imply native payloads are included, independently rebuilt, or fully license-cleared. See [runtime validation](RUNTIME-VALIDATION.md).
