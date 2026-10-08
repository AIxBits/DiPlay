# Legacy receiver validation — 0.2.36

Protocol/media baseline: DiPlay 0.2.8, `f2d06951b4e8114dbb62f551c12a32a845a3042f`. The mainline application remains unchanged. VersionName 0.2.36, versionCode 37, API18/armeabi-v7a.

The preceding implementation passed 186 host tests (184 primary plus 2 IPv6), including direct audio flow isolation/checksum handling, bounded queue overflow, authentication rejection, socket fallback, asynchronous logging, native JNI/crypto, USB request ordering and video pause/resume. The public repository build independently passed the same 186 tests and APK/packaging checks. A separate scan confirmed no personal identifiers, log-upload token or publisher token in source or APK, and no bundled upload configuration.

Checks include SDK18 platform references, single DEX035, APK CRC, packaged ARM native library exports/dependencies, v1 signature, post-shrink mDNS reflection, and synthetic mutual TLS. Runtime identity inputs are byte-identical to the pinned upstream public APK's experimental identity. No personal runtime/upload/publish credentials are included in the public APK or source.

Real i.MX6 hardware feedback confirms earlier wireless and wired projection; USB first succeeded in adaptation 0.26. This does not prove universal head-unit compatibility, direct-path audible stability, full call duplex behavior, vehicle GPS/CAN adoption or resolution of OEM service crashes. Android 5.0 interface compatibility is explicitly unverified and known to need a separate adapter.

The upstream Gradle build/lint suite is outside this optional target's validation scope. No private vehicle logs, terminal history, firmware images or locally generated keys are checked into this contribution branch.

Public APK: `DiPlay-Android43-v0.2.36.apk`, 1,265,461 bytes. SHA256: `e3c76ffd6e51395827c4903b1c0513486ade7fa0c6a68fbd4dea5cac410db157`.
