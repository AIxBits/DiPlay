# Android 4.3 0.2.36: direct wired audio ingress

## Problem and resulting behavior

The 0.35 vehicle report confirms a 480 Mbps USB link and eight queued IN requests, yet the main audio socket RX memory stays almost full. NCM-to-socket counts diverge by about 41% in a steady interval, while the application packet queue rejects zero packets and asynchronous diagnostic enqueue cost is low. Increasing USB queue depth alone does not address that downstream bottleneck.

Version 0.2.36 adds a default-enabled, selectable wired audio ingress which delivers matching NCM UDP payloads directly to the existing bounded audio queue, before TUN injection. Authentication, native/BC decryption, RTP handling, audio focus, channel mapping and AudioTrack playback remain unchanged. TCP video/control, NDP and other UDP flows retain the existing kernel network path. The upstream checkout is not modified; this optimization belongs to the API18 transport adaptation.

## Scope, validation and fallback

A router context belongs to one wired connection and its local IPv6 address. A flow is registered after socket binding and before the phone receives the audio port. It matches the control-session peer IPv6 address, local IPv6 address and negotiated destination port. The phone source UDP port is not guessed or pinned to an unnegotiated value. The existing cryptographic authentication is still applied to every delivered audio packet.

Only ordinary complete IPv6 UDP datagrams use the direct path. UDP length and IPv6 UDP pseudoheader checksum are checked, including odd payload lengths. Matched invalid lengths/zero or invalid checksums are counted and discarded, not treated as successful audio. Extended headers and fragment processing remain with TUN/kernel and the existing socket reader. The socket stays bound and the reader remains available for that fallback. Unsupported or unmatched traffic is not intercepted.

Consumed direct packets are skipped by the TUN writer, preventing duplicate delivery. The producer copies only the UDP payload into the existing 128-entry queue; it does not decrypt or write audio on the NCM thread. A full application queue rejects the new packet and increments the existing explicit reject counter instead of blocking USB reception. Flow close disables delivery and removes its registration; connection close removes the router context. No sysctl/root changes are made, and no OEM Bluetooth/audio service is modified.

## Controls and diagnostics

Settings → Diagnostics → **Direct wired audio delivery (reconnect required)** controls the path (`usbDirectAudio`, default true). Disable it and reconnect to compare the prior socket path. Wireless audio uses the prior socket path because it has no wired router context.

`USB audio ingress mode=direct-with-socket-fallback` confirms the wired preference. `Audio queue ... mode=... directPackets=... directInvalidLength=... directBadChecksum=...` confirms stream registration and actual routing. Application queue received counts combine direct and socket arrivals; rejects and occupancy retain their prior meaning.

`Audio ingress` retains the original NCM/socket metrics and adds `direct` and `directGaps`. `udp` now represents only actual socket receives, including fallback; it must not be compared alone against NCM when direct mode is active. Compare NCM to **direct + udp**, then compare those to authenticated packets, allowing queue/in-flight offsets and queue rejects. `tunWritten` remains actual TUN writes and will stay low/zero for normally intercepted audio. These are distinct transport stages, not lost packets by definition. System UDP6 counters likewise no longer count intercepted audio.

Matched malformed/checksum counters identify transport errors without recording addresses or payloads. The queue summary, authentication rejects, device occupancy and rebuffer counts help determine whether the bottleneck has moved downstream. Header/fragment fallback can reorder relative to direct delivery; normal unfragmented audio takes a single direct producer path. No retransmission recovery or new packet-reordering algorithm is claimed.

## Vehicle validation

Install 0.2.36, reconnect USB, and play the same music for at least one minute. Save the report after any audible interruption. Verify the stream mode, increasing directPackets/direct count, queue rejects, authentication rejects and playback rebuffers. Test navigation announcements separately. If problems worsen or direct checksum rejects grow, disable direct mode, reconnect and retain both reports for comparison.

The change is a targeted optimization, not a certified audio fix. Hardware CPU/scheduling, native output pacing, Android vendor routing, fragmented traffic behavior and user-perceived audio continuity still require vehicle validation. Host tests exercise address/port isolation, odd-length checksum validation, Ethernet offsets, malformed/checksum rejection, close cleanup, bounded queue overflow, fallback reception and unchanged authentication of valid/tampered packets.
