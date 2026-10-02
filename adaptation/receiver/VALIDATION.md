# Legacy receiver validation

Adaptation: 0.21-experimental. Upstream baseline: DiPlay 0.2.8,
`f2d06951b4e8114dbb62f551c12a32a845a3042f`.

- 115 JVM tests passed: 113 protocol/media/compatibility tests and 2 IPv6 transport tests. Tests use synthetic authentication identities and temporary local sockets.
- SDK18 platform class/member audit, single DEX035, ZIP CRC and no native libraries.
- Post-R8 mDNS service/TXT creation and EnumMap reflection checks passed.
- APK v1 signature verified for minimum SDK18.
- Publication source privacy and credential-container checks are run separately.

The device operator reported the 0.21 Android 4.3 version working after the display-recovery revision. Earlier device feedback confirmed wireless video/touch, music/navigation/Siri invocation and physical steering-key delivery. These observations do not establish support on other firmware, complete USB sessions, all audio streams, telephone duplex audio, iPhone adoption of vehicle GPS/CAN speed or fully authenticated Wi-Fi iAP2 handoff.

The report asset is updated for this public version, without changing the tested runtime source. No firmware image, personal device report, terminal output, runtime accessory identity or signing key belongs in this source tree. English public documentation replaces local development notes.

The upstream Gradle build/lint suite is not part of this optional target's local validation; its app source and build configuration remain unchanged. OEM Reverse Assist/PlatformService crashes are not considered resolved merely because the receiver works.
