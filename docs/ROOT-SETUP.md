# Completed owner-authorized root setup — 2026-09-17

This is a separate device-maintenance task requested after Phase 1–2. BLACKBOX itself still does not root, unlock, or flash the device. The owner explicitly authorized rooting and any necessary unlock wipe; no wipe has been needed.

## Confirmed

- Device: M2010J19SG, fastboot product `lime`.
- Direct `fastboot oem device-info`: `Device unlocked: true`, `Device critical unlocked: true`.
- Direct `fastboot getvar unlocked`: `unlocked: yes`.
- Android's reported locked/green properties are inconsistent with the bootloader's direct response. No unlock command was issued.
- Android build: API 33 / Android 13, `aosp_lime-user`, incremental `1707473259`, custom 4.19.225-lilium+ kernel. Its MIUI Android 12 fingerprint is insufficient to select a matching boot image.
- Android exposes a `boot` partition and a separate `recovery`; no `init_boot` node was found. The original boot image has header v2 and a ramdisk; Magisk's inspection found no existing Magisk patch.

## Preparation

Official [Magisk v30.7](https://github.com/topjohnwu/Magisk/releases/tag/v30.7) downloaded to ignored `.tools/root-preparation/`. Release APK SHA-256, verified against GitHub's release asset digest:

```text
e0d32d2123532860f97123d927b1bb86c4e08e6fd8a48bfc6b5bee0afae9ebd5
```

Android SDK apksigner verifies the APK's v1/v2 signatures. Certificate SHA-256:

```text
b4cb83b4dad99f997dbe872f013aa16c14eec41d167021f371f7e1330f273ee6
```

The official APK is installed as `com.topjohnwu.magisk`. Its upstream boot patcher was reviewed and executed on this phone against a copy of its own boot image. No model-name-based or third-party patched image was used. See [official installation guidance](https://topjohnwu.github.io/Magisk/install.html).

## Procedure and verified result

The existing AOSP-style recovery initially reported ADB unauthorized. The owner selected Advanced → Enable ADB, which provided a root recovery shell. The exact 134,217,728-byte boot partition was read to the computer and its SHA-256 independently checked on the phone. Android was then rebooted; the owner assisted with USB access.

Magisk's official `boot_patch.sh` ran in an isolated ADB directory on booted Android. Settings preserved verity and encryption (`KEEPVERITY=true`, `KEEPFORCEENCRYPT=true`, `PATCHVBMETAFLAG=false`); recovery/legacy-SAR flags were false. The patcher selected `cache` for pre-init storage. The kernel and DTB byte sequences were confirmed unchanged. The patcher's unsuccessful Samsung-specific pattern searches were nonfatal and left the kernel unchanged.

Temporary `fastboot boot` was attempted first, but this bootloader returned `unknown command`. After rechecking the product, unlocked state, backup hash and patched-image hash, only the `boot` partition was flashed. Fastboot reported successful writing. Android subsequently booted, Magisk completed its environment setup, and the live boot-partition hash matches the patched file. No wipe, unlock, relock, recovery/ROM/kernel replacement, standalone vbmeta flash, or SELinux disabling was performed. The preserved kernel is inside the patched boot image.

| Artifact | SHA-256 |
| --- | --- |
| Original `.tools/root-preparation/original-boot.img` | `8f3394b5a95494953f25e91df2788e8ef91f2c87a8e0487b6b0a3a613e358306` |
| Patched `.tools/root-preparation/magisk-patched-boot.img` | `77f80845c37e61e9d73bb8ae7a1b1a15326b41d988c2f157419081b06c44ce5a` |

Verification:

- Magisk app and daemon report **30.7 (30700)**.
- Authorized ADB `su -c id`: **uid=0, context=u:r:magisk:s0**.
- SELinux: **Enforcing**.
- BLACKBOX's Magisk per-app permission is enabled. The first root-required test hit a denied policy; enabling BLACKBOX in Magisk's Superuser page resolved it.
- Both real-device root tests then pass: **OK (2 tests)**. BLACKBOX records **GRANTED**, subsequent command **ROOT**, exit 0.
- Rooted isolated `iw dev` and `iw phy` both exit 0. The PHY advertises managed+AP combinations with up to two channels and matching STA/AP beacon intervals. This does not establish standalone AP lifecycle or client routing.
- Wi-Fi upstream is connected after reboot. The prior Android hotspot is no longer active; it was not recreated or reconfigured by BLACKBOX.

Evidence: `.tools/root-preparation/{patch-log.txt,temporary-boot-log.txt,flash-boot-log.txt,blackbox-root-tests.txt,original-boot.json,patched-boot.json}` and `device-reports/rooted-probes.json`. These private device artifacts are gitignored. A second verified original backup and checksum record are saved outside the tool cache at `device-backups/lime-2026-09-17/` (also gitignored).

To reproduce root verification:

```powershell
. .\scripts\env.ps1
adb shell su -c id
adb shell su -c 'magisk -v'
adb shell getenforce
adb shell am instrument -w -r -e class dev.blackbox.router.root.RootDeviceTest,dev.blackbox.router.root.RootRequestProbeTest -e rootTests true -e rootProbe true dev.blackbox.router.test/androidx.test.runner.AndroidJUnitRunner
```

Recovery, only if needed for this same installation: enter fastboot, verify the saved original image hash, then flash `original-boot.img` to **boot** and reboot. Do not relock this device with the custom system installed. Restoring boot removes the Magisk boot patch; app/data cleanup is separate.
