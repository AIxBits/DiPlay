# Android 4.3 adaptation changes

## 0.2.36 (experimental)

- Bring the tested API18 target forward from 0.21, including USBMUX/Lockdown TLS1.2, kernel USB configuration/interface activation, NCM framing and ordered asynchronous USB reception.
- Add selectable 1/8/16 IN requests, default eight 16 KiB buffers, modest receive-thread priority, USB negotiated-speed reporting and a timed video traffic comparison.
- Add default-enabled direct wired audio delivery for matching, checksum-validated IPv6 UDP audio flows. Preserve upstream authentication/decryption/playback and socket fallback; retain a setting for the original path.
- Move audio diagnostic callbacks to a bounded asynchronous dispatcher; add per-port ingress, kernel queue/error, application queue and playback occupancy/rebuffer diagnostics.
- Add optional manual/offline log upload and built-in verified update download. The public APK contains no upload token or publisher credential; log upload is unconfigured by default.
- Correct API18 telephony stream selection and retain independent microphone, music/navigation focus, GPS, vehicle input and Surface recovery diagnostics.
- Public versionName is 0.2.36, versionCode is 37 so it can update previous versionCode 36 installations. Only an experimental Android 4.3/32-bit ARM APK is distributed.
- Android 5.0 USB compatibility is not claimed: the API18 interface-construction path remains firmware-specific. Vehicle audio continuity with direct ingress requires further testing.

## 0.21-experimental

Compared with the fork's previous 0.13 release:

- Add separate wireless/USB entry points and native home/settings pages based on the upstream layout. Preserve media, vehicle and input diagnostic tools.
- Keep automatic connection on open/boot optional and disabled by default. Add foreground session ownership, disconnect retry, background-session controls and Surface recovery on activity return.
- Enter projection before first-frame decoding; reattach/rebuild on projection return and changed Surface dimensions. The device operator reported this display-recovery version working.
- Add USBMUX/Lockdown/iAP2/NCM/IPv6/VPN paths using API18 descriptor and I/O adapters. Complete USB sessions still need device evidence.
- Add configurable wireless network modes, resolution/frame rate/physical size/safe area, audio focus/buffer/volume, optional navigation ducking and codec-dependent HEVC/Opus.
- Add independent media/navigation stream choices0–20, previews and unsupported-track fallback. Phone and Siri routes remain separate.
- Retain upstream Siri invocation and microphone negotiation, serialize Siri control requests, and restore click-based steering voice behavior.
- Add read-only OEM CAN speed/gear input. Independent PASCD requires verified gear mapping; actual iPhone use remains unverified.
- Record connection, media, rendering-target and current OEM service error states locally. OEM Reverse Assist/PlatformService crashes are not marked resolved.
- Expand automated validation to 115 tests; preserve API18 bytecode, single-DEX035, v1 signing and mDNS/EnumMap checks.

The upstream Android 9+ application is unchanged. This release does not constitute a complete upstream0.2.9 merge or broaden the original maintainer's supported vehicle list. Only an Android4.3 APK is distributed in this release.
