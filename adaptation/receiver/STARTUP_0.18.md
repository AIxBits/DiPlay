# Manual startup and Bluetooth diagnostics (0.18)

The 0.17 activity unconditionally called automatic wireless connection after binding its service, and copied detailed diagnostic messages into the home status label. This release removes those default behaviors.

## Connection selection

Opening DiPlay or pressing the steering voice key with no live session displays Home. Wireless starts after selecting the wireless action and a paired phone; USB retains its own action. Existing sessions stay running and can be reopened with the wireless/return action. Opening the app no longer forces projection over the connection-choice page.

Automatic wireless connection on application open is an optional setting, disabled by default. Boot connection remains a separate explicit setting, also disabled by default. Existing boot preferences are retained; disable boot connection if previously enabled. An idle service is not sticky. A service restart with an old sessionWanted flag cannot initiate wireless connection unless automatic open or boot connection was explicitly enabled. Reconnection during a manually started session is retained.

Home displays only ready, connecting or connected status. Detailed diagnostics continue in Menu > Log / Export and periodic reports.

## Bluetooth scope

The receiver opens its own iAP2 RFCOMM socket. On successful two-condition wireless handoff it closes that socket and iAP2 bootstrap; it does not call BluetoothAdapter.disable, removeBond, or HFP/A2DP disconnect. This does not establish that firmware Bluetooth handling cannot be affected by a new socket or an iPhone's own CarPlay transitions.

The activity now records adapter, ACL and HFP/A2DP connection broadcasts while open. The wireless controller records enabled, HFP and A2DP states immediately before RFCOMM setup and around handoff. These summaries omit Bluetooth device addresses. Profile snapshots are aggregate system states, not per-phone proof.

## PlatformService investigation

The firmware package com.iflytek.autofly.platformservice contains several services, including Bluetooth, audio and key services. A stop dialog alone does not identify the failing service, process or exception. The supplied Home modification changes launcher entries and adds an installed-app page; it does not replace PlatformService. The current intermittent report lacks a system crash trace, so no cause is established.

If the dialog returns, record whether DiPlay has been opened and export its log. With authorized ADB available, capture `adb logcat -b crash -d` and `adb logcat -d -v threadtime`; if this Android build has no crash buffer, use the latter. Logs can contain personal information and should be reviewed before public sharing. Application diagnostics cannot substitute for the PlatformService exception stack.

## Validation

Connection policy regression tests cover default manual startup, retained stale requests after process restart, explicit opt-in, duplicate-session prevention and stopped-session recovery. Existing protocol/policy and packaged mDNS tests plus the SDK18 APK audit are rerun. Hardware Bluetooth behavior and PlatformService crash resolution remain unverified.
