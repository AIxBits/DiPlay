# DiPlay Android 4.3 receiver

**Experimental CarPlay receiver for Android 4.3 / API 18 head units.**
Adaptation version: **0.21-experimental**. Install on the **car**, not the iPhone.
This separate package, `local.airuize.diplaylegacy`, preserves the upstream Android 9+ app and its build.

Based on [DiPlay](https://github.com/shihabal3amri/DiPlay) public preview 0.2.8,
commit `f2d06951b4e8114dbb62f551c12a32a845a3042f`. This is not a full merge of upstream 0.2.9.
The original maintainer's BYD support commitment is unchanged. Use one projection receiver at a time.

## Current behavior

- API18 Activity/View interface, single DEX035 and v1 APK signing, without AndroidX/Compose or native libraries.
- Upstream iAP2/AirPlay sources selected by `prepare_sources.py`, with explicit API18 substitutions and source hashes. The shared source tree is unchanged.
- Bluetooth wireless startup, car-hotspot projection, encrypted control/events, H.264 decoding, PCM/AAC audio, touch and upstream Siri invocation/microphone negotiation.
- Connection starts from the wireless or USB entry. Connect-on-open and connect-after-boot are disabled by default. Background sessions and bounded reconnect backoff are configurable.
- Upstream-style settings groups for connection, diagnostics, automation, display/performance, audio routing, location and permissions. Resolution, frame rate, physical size, safe area, buffer, full-screen, audio focus and optional navigation ducking are configurable.
- Independent media/navigation stream overrides 0–20 and short-tone previews. Zero retains automatic routing; unsupported track creation falls back with a diagnostic entry. Phone and Siri streams remain separate.
- GPS location subscriptions and GGA/RMC reporting based on upstream. Optional firmware-specific read-only ABS/CAN speed and gear getters; independent PASCD reporting requires a verified gear mapping and phone subscription.
- Optional Skypine MCU voice listener: the recognized button opens the app when disconnected and invokes Siri when connected. Click behavior is retained; separate physical long-press behavior is not claimed.
- A wireless first-frame wait no longer keeps the home/settings overlay covering projection. Entering/returning to projection reattaches the Surface; changed Surface dimensions recover even if Android reuses its wrapper.
- Local report export, configurable relative report folder, media/audio self-tests, vehicle diagnostics and current OEM process-error snapshots. Reports are not sent automatically.

The UI reuses upstream language resources and provides a native-widget interpretation of the original style. Pixel-identical visuals and complete translation of every diagnostic message are not claimed.

## Device feedback and remaining limits

Feedback from one Android 4.3, 32-bit ARM, i.MX6 firmware confirms wireless picture, full-screen H.264, touch, music, navigation synchronization/announcements, Siri invocation, steering-button delivery and independently readable vehicle GPS. The 0.21 display recovery version was reported working on that device. This is not a compatibility list or a guarantee for other head units.

Implemented paths still needing hardware evidence include complete USB/NCM/VPN sessions, app-managed hotspot/Wi-Fi Direct/shared-router modes, iPhone adoption of vehicle GPS/CAN speed, all vendor audio stream choices, sustained microphone recognition, telephone duplex audio, full Bluetooth-to-Wi-Fi iAP2 handoff and long-session/reboot recovery. Codec-dependent HEVC/Opus require installed platform codecs; there is no bundled software codec.

Wireless handoff releases the application's RFCOMM only after `disableBluetooth` and authenticated/subscribed tunnel readiness. The 45-second monitor retains working video when the tunnel is unavailable. Cleanup does not request disconnection of the car's OEM Bluetooth profiles; complete equivalence with upstream connection lifecycle is not claimed.

BYD HUD/cluster integration, Android 11 map embedding, the upstream Home sample, wheel-pulse conversion, battery reporting and complete Live Activity behavior are not provided. The proposed platform/core/vehicle-layer refactor has not been completed. GPS speed is not described as measured wheel speed.

Reverse Assist/PlatformService crash reports remain unresolved without a stack trace. A public ActivityManager query attempts to record their current errors and manifest-defined service processes. A cleared or inaccessible record is inconclusive; this is not full historical logcat capture. The adaptation does not modify the OEM reverse-camera service.

## Install and diagnose

Install the Android 4.3 APK from this fork's release. The existing signing identity and package are retained for in-place updates. Pair the iPhone, keep the car's hotspot available, and select wireless connection. USB selects a different transport; use **Show projection** to return to an existing picture.

After changing connection parameters, use **Apply settings and reconnect**. Audio preview requires stopping projection. Reports default to shared storage under `DiPlayLegacy`; folder names are relative and checked against path traversal.

If a black picture or OEM service crash recurs, export before stopping or switching transport. Counters distinguish received video, configuration availability, decoder starts, output-buffer release and visible rendering targets. Counts alone do not prove physical display, sound output or iPhone use of GPS. Keep reports local unless deliberately shared.

## Build, privacy and validation

See [BUILD.md](BUILD.md), [VALIDATION.md](VALIDATION.md), [settings details](SETTINGS_0.20.md) and [display recovery](DISPLAY_RECOVERY_0.21.md).
The repository excludes terminal paths, personal device reports, firmware images, Wi-Fi/Bluetooth credentials, accessory identities and Android signing keys. Tests generate synthetic identities.

As documented by upstream, the release APK deliberately includes the experimental runtime identity and makes it extractable. This is not an Apple-certified product; future iOS acceptance and general-distribution suitability remain unresolved. Source-only builds omit runtime authentication unless explicitly provisioned from an external directory.

## Credits and licenses

Preserve upstream [credits and licenses](../../docs/THIRD_PARTY_NOTICES.md), [LICENSE](../../LICENSE), and the bundled notices in `assets/licenses`.
DiPlay is based on [xcertplay](https://github.com/shilapi/xcertplay), GPL-3.0; the DiAuto interface and website carry AGPL-3.0 notices. CarPlay belongs to Apple Inc.; no Apple, BYD or original-maintainer endorsement of this legacy target is implied.
