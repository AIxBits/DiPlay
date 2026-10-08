# Build the legacy receiver

The upstream Android 9+ Gradle app remains unchanged. This optional target uses
JDK 11, Kotlin compiler 1.9.24, Android API18 `android.jar`, Android build tools
34.0.0 (including R8 and apksigner), Python 3, the `zip` command, and OpenSSL for synthetic test identities.

Provide the SDK18 platform and build tools from the Android SDK, and a Kotlin
compiler distribution from JetBrains. Dependency versions and published archive
hashes are recorded in `dependency-sha256.json`. The dependency downloader checks
JAR hashes before accepting files; tools and generated files are ignored by Git.

```sh
export JAVA_HOME=/path/to/jdk-11
export DIPLAY_PLATFORM_JAR=/path/to/android-sdk/platforms/android-18/android.jar
export DIPLAY_BUILD_TOOLS_DIR=/path/to/android-sdk/build-tools/34.0.0
export DIPLAY_KOTLIN_DIR=/path/to/kotlinc
export DIPLAY_DEPS_DIR="$PWD/downloads"
python3 adaptation/receiver/fetch_dependencies.py
sh adaptation/receiver/build.sh
python3 adaptation/receiver/verify_apk.py
sh adaptation/receiver/test-packaging.sh
```

The result is `adaptation/output/DiPlay-Android43-Wireless-Experimental.apk`.
Without runtime identity assets this APK is a source/compatibility build and cannot
complete accessory authentication. No external authentication server is added.

For an intentional local runtime build, set `DIPLAY_AUTH_ASSETS_DIR` to an external
directory containing `offline-mfi/identity.pk8` and `offline-mfi/certificate.p7b`,
consistent with upstream [build policy](../../docs/BUILD.md). The inputs are never
committed or downloaded by these scripts. Do not publish personal signing keys.
A release APK including the experimental identity makes that identity extractable.

The script creates a local test signing key under the ignored build directory.
Keep the same local key to update an existing installation. Signing a public
release is a separate local decision; the test key is not a production identity.

Run the JVM tests after building:

```sh
sh adaptation/receiver/test.sh
```

Tests generate a synthetic short-lived P-256 identity in the ignored build directory; no real accessory identity is needed. Network tests bind loopback sockets. `test-packaging.sh` checks the post-shrink
JmDNS startup and reflective enum methods without starting a network service.
`verify_apk.py` checks platform types/members, DEX035, single DEX, CRC and identity
presence/consistency. `build.sh` verifies the v1 signature using apksigner.

The included `adaptation/probe` is an optional API18 capability diagnostic app.
Its build uses the same JDK/platform/build-tools environment variables; it does
not participate in the receiver's normal connection flow.

## Native transport and current dependencies

0.2.36 additionally uses Bouncy Castle TLS/util 1.79 (hash-verified by the downloader) and a freestanding ARM EABI5 JNI library for USB ioctls and authenticated audio crypto. Monocypher 4.0.2 source, license and provenance are included under `native/vendor/monocypher`.

Set `DIPLAY_ZIG` to Zig 0.13.0. The compiler archive hash is recorded in the upstream-independent native build documentation below. JNI headers come from JAVA_HOME; the verified host workflow is macOS with JDK 11 and clang. The produced application ABI is armeabi-v7a only.

Zig 0.13.0 macOS aarch64 archive SHA256: `46fae219656545dfaf4dce12fb4e8685cec5b51d721beee9389ab4194d43394c`.
Official archive: https://ziglang.org/download/0.13.0/zig-macos-aarch64-0.13.0.tar.xz

Public release builds deliberately omit `DIPLAY_LOG_UPLOAD_CONFIG`. Never point that variable to personal credentials when creating a public APK. For intentional integrator builds it may select an external, ignored properties file; no credential values belong in source control. The public APK's experimental accessory identity is byte-identical to the pinned upstream public APK's identity assets. Do not substitute personal provisioning material.

The build verifies v1 signing for API18 and preserves a locally generated ignored key across builds. Public release certificates match the preceding fork APK certificate; signing keystores remain local. Version code 37 is deliberately higher than the prior 36 while the displayed release version is 0.2.36.
