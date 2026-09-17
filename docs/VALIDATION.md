> Historical Phase 1–2 record. Its missing-root and unimplemented-router statements describe that earlier milestone only. Current results: [RUNTIME-VALIDATION.md](RUNTIME-VALIDATION.md).

# Phase 1–2 validation — 2026-09-17

**Later root milestone:** [Magisk setup](ROOT-SETUP.md) completed and both `RootDeviceTest` and `RootRequestProbeTest` passed on the real phone, with a verified root identity. The initial denied policy was corrected through Magisk's Superuser UI. Rooted iw now produces real driver output. The sections below record the earlier pre-root baseline; AP/routing behavior remains unimplemented and untested.

The final app scan displays **ROOT GRANTED** and **Repeater candidate · runtime test pending**, with validated Wi-Fi upstream. The unchanged app builds successfully and the 34-test JVM task is up-to-date. Root-milestone build log: `.tools/root-preparation/build-validation.log`; dashboard capture: `device-reports/rooted-home.png`.

## Build and static tests

- Debug APK compiled successfully; installed as `dev.blackbox.router` on the attached Redmi.
- Optimized R8 release APK compiled successfully. It is unsigned and not a production release.
- Android instrumentation test APK compiled successfully.
- **34 JVM tests pass**: 16 network/parser tests, 12 diagnostic-engine tests, 3 scan-state tests, 3 sanitization tests. Zero failures or errors.
- Debug Android lint passes with no errors. Dependency-update suggestions remain because the compatible toolchain is deliberately pinned.
- The Python ADB preflight script compiled and ran against the phone.

Verification commands:

```powershell
. .\scripts\env.ps1
.\gradlew.bat :core:test :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease --max-workers=2
.\gradlew.bat :app:lintDebug --max-workers=1
python -m py_compile scripts\probe-device.py
python scripts\probe-device.py --request-root
```

One intermediate lint run crashed inside Kotlin analysis while source files were being added. Restarting the Gradle daemon and rerunning lint on the completed source set resolved the tool failure; no lint checks were disabled.

## On-device results

The Redmi was connected through authorized USB ADB throughout. No network toggle, firewall edit, route mutation, interface creation, reboot, bootloader operation, or root installation was performed.

A final read-only preflight still observed the same connected STA and Android SoftAP at 5660 MHz. The captured policy-rule output and IPv4 forwarding state matched the initial capture.

Three device instrumentation tests passed together on the final installed build:

1. `ReadOnlyDiagnosticsTest`: actual app shell identity, successful read-only diagnostic engine, Android hardware facts, no false router-start capability, sanitized report generation.
2. `RootRequestProbeTest`: real root request yields **UNAVAILABLE**; a subsequent command succeeds as **APP**, never falsely claiming root.
3. `BlackboxSmokeTest`: all six navigation destinations and Developer Mode render and respond on the unlocked phone.

The final run used:

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb install -r app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
adb shell am instrument -w -r -e class dev.blackbox.router.BlackboxSmokeTest,dev.blackbox.router.ReadOnlyDiagnosticsTest,dev.blackbox.router.root.RootRequestProbeTest -e rootProbe true dev.blackbox.router.test/androidx.test.runner.AndroidJUnitRunner
adb shell am start -n dev.blackbox.router/.MainActivity
```

The root-required `RootDeviceTest` is deliberately skipped unless `rootTests=true`; a successful root grant has not been tested because this phone currently does not expose root to BLACKBOX. The owner clarified that no root manager was knowingly installed. Root availability needs to be resolved separately before Phase 3.

**The UI navigation test passed after the owner unlocked the phone.** It verified all six navigation destinations and Developer Mode. The initial attempt was blocked by the secure keyguard; the test now explicitly checks this prerequisite. The rendered dashboard was also inspected on-device. Reproduce the UI test after unlocking:

```powershell
adb shell am instrument -w -r -e class dev.blackbox.router.BlackboxSmokeTest dev.blackbox.router.test/androidx.test.runner.AndroidJUnitRunner
```

## Local evidence

- `core/build/reports/tests/test/index.html`: JVM results.
- `app/build/reports/lint-results-debug.html`: lint results.
- `.tools/final-compile.log` and `.tools/final-lint.log`: final build logs.
- `.tools/device-final-tests.log`: final three-test on-device run, `OK (3 tests)`.
- `device-reports/adb-preflight.json`: sanitized ADB observations.
- `device-reports/app-diagnostics.txt`: sanitized results from the real app UID.
- `device-reports/app-root-result.txt`: real root request/fallback status.
- `device-reports/adb-after-validation.json`: final network observations for comparison.
- `device-reports/home.png`: inspected on-device dashboard screenshot.

These generated/private files are gitignored. The checked-in [device findings](DEVICE-COMPATIBILITY.md) omit serials, SSIDs, IP addresses and MAC addresses.

## Not tested or implemented

Successful root authorization, real `iw phy` output on this Redmi, standalone hostapd/dnsmasq operation, AP/DHCP/DNS/NAT, per-client traffic, VPN routing/kill switch, startup rollback and crash recovery. The UI and docs must not describe these as working. Synthetic Wi-Fi fixtures exist only in unit tests.

## Follow-up: isolated tool installation

See [tool provenance and results](NETWORK-TOOLS.md). iw 6.17 was checksum-verified before and after copying to an isolated ADB directory. Version execution succeeds; all three read-only Wi-Fi queries fail because SELinux denies generic-netlink socket creation. The existing-file repeat probe produces the same results. hostapd/dnsmasq remain staged on the PC; the VirtualAP APK was never installed.

Policy-rule output and forwarding state match before/after. A fresh ADB preflight still reports connected STA and SoftAP at 5660 MHz. The app remains unchanged; no new root or network capability is inferred.

The Python scripts pass syntax compilation. `:core:test :app:assembleDebug` completed successfully; the unchanged 34-test suite was up-to-date with zero failures/errors. Existing device UI tests were not repeated because no app code changed. Log: `.tools/network-tools-validation.log`.
