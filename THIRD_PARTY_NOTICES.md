# Third-party components

BLACKBOX uses unmodified third-party libraries resolved by Gradle:

- libsu 6.0.0, John Wu — Apache License 2.0: https://github.com/topjohnwu/libsu
- AndroidX / Jetpack Compose, Android Open Source Project — Apache License 2.0: https://android.googlesource.com/platform/frameworks/support/
- Kotlin standard library and kotlinx.coroutines, JetBrains and contributors — Apache License 2.0: https://github.com/JetBrains/kotlin and https://github.com/Kotlin/kotlinx.coroutines
- Material icons, Google — Apache License 2.0: https://github.com/google/material-design-icons
- Gradle wrapper, Gradle contributors — Apache License 2.0: https://github.com/gradle/gradle

License text for libsu and coroutines is bundled in `app/src/main/assets/licenses/`. Gradle and dependency artifacts retain their own notices. No VirtualAP source, scripts, or binaries are part of BLACKBOX's APK or tracked source. No hostapd, dnsmasq, iw or OpenWrt binary is bundled in the application.

Separate local development downloads include VirtualAP v1.8.0 and its pinned iw/hostapd/dnsmasq component sources. The exact sources, extracted primary license files and checksum manifest are retained under ignored `.tools/router-toolchain/virtualap-v1.8.0/`. The verified iw, hostapd and dnsmasq executables are now provisioned separately on the owner's phone under /data/adb/blackbox/tools. The VirtualAP APK was not installed. AndroidX dependencies include Room 2.7.2. See [tool provenance and limitations](docs/NETWORK-TOOLS.md). This evaluation does not establish complete redistribution compliance for a future bundled backend.

The locally downloaded Android SDK and Temurin JDK are development tools, ignored by the repository and not included in the APK. Their terms remain with their distributions.

An application-wide public distribution license has not been selected by the owner. This file records dependencies; it does not license the owner's original application source.
