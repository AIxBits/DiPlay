# Bluetooth/Wi-Fi and release review of Android 4.3 adaptation 0.18

Reference: locally checked upstream DiPlay commit f2d06951b4e8114dbb62f551c12a32a845a3042f, particularly CarPlayController and DiPlayActivity. This is a source review with the supplied 0.17 reports, not a hardware verification of 0.18.

## OEM Bluetooth and CarPlay bootstrap are separate

A head unit's automatic Bluetooth connection typically establishes profiles for calling and music. Those connections do not establish DiPlay's authenticated iAP2 control channel. Both upstream and this adaptation create a dedicated RFCOMM socket using the iAP2 UUID after preparing their Wi-Fi endpoint and AirPlay discovery/listener.

Neither reviewed controller explicitly disables the Bluetooth adapter, removes pairing or invokes HFP/A2DP disconnect as part of wireless handoff. Upstream's closeBluetoothBootstrapTransport closes its iAP2 session, RFCOMM duplex stream and socket. The adaptation closes the same owned layers. OEM Bluetooth services remain responsible for their own connections. This source behavior cannot guarantee that the phone or vendor firmware will never change profile state in response to CarPlay.

## Matching behavior

- Prepare a usable Wi-Fi endpoint and publish/listen before starting RFCOMM.
- Authenticate iAP2 and send the Wi-Fi/CarPlay endpoint over Bluetooth.
- Accept the Wi-Fi iAP2 tunnel and use the upstream control client's onReady after authentication/subscription setup.
- Require both the phone's disableBluetooth request and tunnel readiness before releasing the Bluetooth bootstrap.
- Monitor the handoff for 45 seconds; retain an established video session if the tunnel does not arrive, rather than force a reconnect loop.
- Dispose owned media/control sockets, location subscriptions, network/discovery resources and locks when stopping.
- Application-open automatic connection is optional in 0.18, as it is in upstream, rather than an unconditional consequence of OEM Bluetooth pairing.

## Remaining differences

- Upstream selects an explicitly configured paired phone, or otherwise prefers one connected iPhone using device/profile state. Adaptation's automatic selectors prefer a saved address or an unambiguous bonded candidate; its service can also construct a remote device from a stale saved address. The manual chooser lists paired devices. Automatic selection is not fully equivalent.
- Upstream's RFCOMM connection timeout is 15 seconds; adaptation closes the connecting socket after 12 seconds.
- Upstream checks CHANNEL_CLOSED against authenticated handoff, handoff request, tunnel and session state before deciding to preserve Wi-Fi or fail. Adaptation currently logs normal control-loop termination without the full upstream terminal-state decision; a startup exception uses a simpler active-session/tunnel-presence guard. These are not fully equivalent recovery paths.
- Wi-Fi tunnel failures in adaptation are logged but do not fully propagate upstream's control-status lifecycle. A full reconnect/recovery equivalence claim would be incorrect.
- Upstream hotspot modes and cleanup use newer platform mechanisms. Adaptation uses API18-compatible existing/vendor hotspot and P2P paths, with restoration of network state it changed. Equivalent intent does not prove equivalent firmware effects.
- Upstream automatic startup uses the last connection type. Adaptation's optional application-open automatic setting starts wireless specifically.

## Release confidence and limits

The adaptation has idempotent close gating, closes the listener before other cleanup, disposes its resource list in reverse order and waits for its bootstrap worker. Existing tests cover the handoff gate and repeated close/port-release synchronization. They do not exercise every hardware resource in the production controller.

The supplied reports show deliberate session termination, wireless-stack cleanup, and a subsequently successful new wireless session. This supports that the previous listener/session could be released and reused in that sequence. Successful authenticated Wi-Fi handoff and retained OEM HFP/A2DP profile state were not demonstrated in those reports.

Some cleanup exceptions are caught without recording them, yet the controller emits an unconditional summary saying the wireless stack was released. That summary is a cleanup-path completion message, not proof that every underlying resource closed successfully. Bootstrap joining also lacks an independent timeout. Stronger resource-level diagnostics and terminal-state handling would be needed to certify every release/recovery scenario.

## Required OEM constraint

Keep OEM pairing and Bluetooth calling/music under OEM service control. Do not add adapter disable/enable cycling, removeBond, HFP/A2DP disconnect or forced OEM profile reconnect to implement DiPlay handoff. Releasing the application's RFCOMM bootstrap is the intended operation. Use 0.18 adapter/ACL/profile events and before/after profile snapshots to verify real behavior; correlate a PlatformService crash with a system exception trace before attributing it to this path.
