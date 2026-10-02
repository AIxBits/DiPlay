# Projection resume and steering voice click (0.19)

## Projection recovery

Code review identified two recovery gaps that can explain a black projection after returning to the app: an exception while using an invalidated Surface ended the video worker permanently, and object-identity checks could miss a detach/reattach when Android reused a Surface wrapper. These are potential causes, not a confirmed diagnosis of the user's observed device event.

This release publishes an immutable target/generation pair for the video worker. Detach, reattach and explicit foreground recovery advance the generation, even when the Surface object is reused. The worker recreates the API18 decoder on a new generation, discards obsolete queued/pending video and requests a random-access frame after a usable decoder is configured. It retains the video configuration and CarPlay session. Foreground resume, launcher reentry and service rebind reattach the display; a live session returns to projection. With no active session, the connection-choice Home and default manual connection policy remain available.

Decoder exceptions now release the codec and retry on the video worker with a bounded 250–2000ms delay rather than terminate the worker. Actual shutdown interrupts the loop. Keyframe requests are deferred until a decoder exists. This API18 path cannot use the upstream MediaCodec.setOutputSurface optimization, which requires a newer Android platform.

Diagnostics retain Surface creation/destruction and attachment reason, generation, codec restart errors and first-frame recovery confirmation. A render callback reflects buffer release to a configured Surface; physical panel visibility remains a hardware observation.

## Steering voice key

Following the user's request, any recognized voice-button action opens Home if no CarPlay session exists, and invokes upstream Siri control when a session exists. Siri activation no longer depends on a vendor long-press signal. The previous raw key classifier and duplicate callback gate remain, but an OPEN action during an active session now invokes Siri too. Normal launcher reentry does not invoke Siri. The foreground service notification describes the click behavior.

## Verification

Regression coverage adds Surface-wrapper reuse, forced resume and immutable worker snapshots. Existing transport, media-policy, startup-policy and packaged mDNS tests and the API18 APK audit are rerun. Returning from the OEM launcher and click-to-Siri still require head-unit validation. This release does not change the Bluetooth handoff conditions or certify the previously unverified full handoff.
