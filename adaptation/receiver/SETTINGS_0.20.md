# Settings port — experimental 0.20

The adaptation now provides an in-page settings screen organized around the upstream DiPlay settings and connection-setup sections. It uses Android API 18 widgets to retain Android 4.3 compatibility. This is a functional adaptation, not a pixel-identical Compose implementation or a complete upstream 0.2.9 merge.

## Available controls

- Connection setup: wireless mode configuration, Bluetooth and Wi-Fi system settings, paired iPhone selection, and USB entry.
- Diagnostics: report export, relative shared-storage report folder, session logs, vehicle checks, media self-test, and audio previews.
- Automation: connect on app launch, connect after boot, reconnect, and background-session preferences. Connecting on app launch and after boot remain disabled by default.
- Display and performance: resolution scale, frame rate, music buffer, right-hand-drive layout, full screen, physical size, canvas dimensions, and safe-area controls.
- Audio: focus arbitration, optional navigation ducking, volume, and independent media/navigation stream overrides from 0 through 20. Zero retains automatic routing. The override never changes phone or Siri stream selection. Unsupported AudioTrack creation falls back to automatic routing and records the actual stream in the report. A successfully initialized but inaudible OEM stream requires a physical audio test; initialization alone does not prove sound output.
- Location and input: Android location reporting, microphone uplink, and the existing OEM steering voice listener.
- Language: the existing language selector remains available. Newly added labels have Chinese and English translations; other languages currently use English fallback for these labels.
- Permissions and help: installation-time permission guidance and the app's system settings entry.

Audio previews require the CarPlay session to be stopped. They play a half-second tone without changing system volume, audio mode, or SCO. Preview stream selection is read from the latest saved preference, independently of the current session.

## Applying changes

Choices are persisted immediately. Protocol and media changes take effect when the connection is recreated. Use **Apply settings and reconnect** for an active session. If disconnected, this button updates local full-screen and steering-listener state and leaves the application disconnected. The explicit reconnect closes the adaptation's session, without requesting disconnection of OEM Bluetooth profiles.

Report folders are specified relative to shared-storage root, rather than through a modern document-tree picker unavailable on Android 4.3. Absolute paths, traversal components, and control characters are rejected; canonical containment is checked before export. The default is `DiPlayLegacy`.

## Hardware and version limits

HEVC and Opus controls are enabled only when the required installed codecs are detected. Presence of a codec is not a guarantee of real-time performance. Wireless modes and USB remain subject to the device capabilities and existing transport implementation; presenting a setting does not establish hardware compatibility.

BYD cluster integration, the Android 11 map-embedding service, the upstream Home example, and other unsupported vendor functions have not been ported. Vehicle speed and steering integration retain their existing adapter behavior. This update does not implement the separately proposed three-layer architecture.

## Validation

All 115 automated tests passed (113 main tests and 2 IPv6 transport tests). The post-shrink EnumMap and mDNS packaging checks passed. The release is built against API 18 and audited for API 18 bytecode references, DEX format, signing, and packaging. Automated tests cover protocol/media behavior and the new stream-selection and report-folder policies. Physical verification is still needed for settings layout on the head unit, OEM stream audibility, reconnect after applying settings, and full-screen toggling.
