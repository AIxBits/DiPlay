# Android 4.3 adaptation changes

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
