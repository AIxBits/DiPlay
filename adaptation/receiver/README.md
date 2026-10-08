# Experimental Android 4.3 receiver — 0.2.36

This optional API18 receiver adapts the pinned DiPlay 0.2.8 protocol/media implementation (`f2d06951b4e8114dbb62f551c12a32a845a3042f`) to tested 32-bit ARM i.MX6 Android 4.3 firmware. It is separate from the upstream Android 9+ receiver and does not extend the original maintainer's supported vehicle list.

The operator reported wireless projection and wired USB projection working; wired connection first succeeded with the earlier 0.26 interface-activation fix. Music/navigation/Siri and steering input have prior feedback, but wired audio continuity and complete call duplex behavior are not certified. The newest direct audio path is host-tested and still needs vehicle validation.

## Wired audio revision

The current report showed a high-speed 480 Mbps USB connection but a persistent deficit between NCM reception and audio socket reception. The new selectable direct ingress sends matching IPv6 UDP audio payloads to the existing bounded queue before TUN injection, preserving authentication, decryption and playback. Ordinary control/video traffic and unsupported extended-header/fragment handling retain the kernel path. Read [the transport design and validation limits](AUDIO_DIRECT_0.2.36.md).

Settings → Diagnostics retains the original socket-mode comparison, USB queue-depth controls, timed video reception pause, exports and vehicle/media self-tests. Update checks and verified APK downloads run inside the application.

## Distribution and privacy

The public APK includes only the experimental accessory identity already present in the pinned upstream public APK; it contains no user's phone pairings, log upload token, update publish token, firmware image or signing keystore. Source builds omit accessory identity unless explicitly provisioned. The bundled experimental identity is extractable and is not a personal identity or evidence of Apple certification, consistent with upstream notices.

Log upload is unconfigured in the public APK; the interface reports that configuration is unavailable. Local integrators may explicitly provision their own external upload configuration. No automatic report upload occurs. Public update checks/downloads require no publish credential; publisher authentication is never bundled.

Android 5.0 USB support is not claimed by this build. The API18 hidden interface constructor is firmware-specific; newer Android systems need a separate platform adapter. OEM button/CAN integration is optional and is not assumed to apply to other vehicles. Stock Bluetooth/audio services are not forcibly reconfigured.

See [build instructions](BUILD.md), [validation](VALIDATION.md), [change history](CHANGELOG.md), and the upstream licensing and contribution notices. Release versionName is **0.2.36**, versionCode **37**, with package `local.airuize.diplaylegacy`.
