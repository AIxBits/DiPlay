# Display recovery — experimental 0.21

A reported regression after installing 0.20 leaves the user on the home/settings page after wireless connection. Starting USB exposes a black projection area. No display preferences were changed. Without a log from the failing session, the underlying media failure is not yet established.

## Findings and changes

The 0.19-to-0.20 comparison shows no change to video transport or decoding. The activity previously waited for a decoded first frame before hiding the home/settings overlay. That dependency obscures the projection area when video startup fails. Returning to projection also did not explicitly reattach its rendering target.

- Wireless connection now shows the projection page before starting negotiation. It does not depend on successful first-frame decoding to leave home/settings.
- Entering or returning to projection posts a forced Surface attachment after the visibility/window update. Existing recovery requests and decoder rebuilding are reused.
- A changed nonzero Surface size forces recovery even if Android reuses the same Java Surface wrapper. Identical size callbacks do not force another rebuild.
- The extra immediate system-bar change introduced in 0.20 on activity creation has been removed. The full-screen preference remains applied through the existing focus/projection paths.
- Diagnostics now include current page/overlay visibility, video View and Surface dimensions, Surface validity, codec, configuration availability, and decoder exception details. Existing counters distinguish received video from released display buffers. Releasing a buffer is not proof that the physical panel shows its contents.

The new settings page, audio routing controls, and diagnostic tools remain available. This patch does not change authentication, OEM Bluetooth profiles, or wireless/USB transport selection.

## Automated validation

All 115 regression tests passed (113 main tests and 2 IPv6 transport tests). The API 18 bytecode/DEX audit and post-shrink mDNS/EnumMap packaging checks passed. These checks do not replace an on-device display test.

## Physical verification

Install over the previous adaptation using the existing package and signing identity. Use the wireless connection action and selected iPhone. To return to an active session, use the projection action; USB is a transport selection rather than a screen-recovery action.

If the image remains black, keep the failing session running briefly, open the menu, and export a report before switching transport or stopping. The report should establish whether video is absent, configuration is unavailable, decoder startup fails, or a valid output target remains obscured. Subsequent device feedback reported the 0.21 receiver working. This supports recovery on that device but does not establish a unique root cause or resolve the separate OEM service crash.

## OEM service crash investigation

The supplied firmware identifies Reverse Assist as `com.iflytek.autofly.reverseassist`, an OEM system-UID service started by boot broadcasts. Its startup initializes device-status callbacks, a vehicle-information listener, a status worker, and a CPU-monitor worker. A desktop crash popup therefore does not establish that a reverse-camera activity was launched or that DiPlay caused the failure. The adaptation's vehicle bridge performs getter transactions for speed and gear; it does not invoke `CallCameraApp` or the firmware's `BackCarManager`. Indirect interactions remain unverified without a stack trace.

Diagnostic snapshots and report export now attempt the public ActivityManager current-error-state query, retaining only Reverse Assist and PlatformService entries. Service-process names are read from the installed package manifests, including the firmware PlatformService processes such as `Remote.AudioService` and `Remote.DisplayService`. This is read-only and does not require the adaptation to stop or reconfigure either service. Visibility depends on the firmware; a cleared error or inaccessible record is explicitly reported as inconclusive. This is not full historical logcat capture, and absence of a returned error is not proof that no crash occurred. Export while the error is still present, if possible. An ADB/system crash log may still be needed.
