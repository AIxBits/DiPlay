# Vehicle-speed integration — 0.16 experimental

This release extends v0.15 with a read-only SDK18 adapter for the supplied firmware's CarinfoService. Navigation behavior and the short-press CarPlay / long-press Siri split remain as in v0.15.

## Firmware interface

The original Music application's route is `CarinfoManager.getEcmMod()` → `EcmMod.getResultBack()` → `getVehicalSpd()`. The adapter binds explicitly to the exported `com.iflytek.autofly.carinfoservice/.CarinfoService` and uses the verified AIDL descriptor and read-only transactions 16 (ECM) and 11 (DCT). Parcelable layouts were checked against the supplied APKs. It does not send vehicle commands or change stock speed-dependent volume.

CarinfoService already converts ABS raw vehicle speed by 0.0625. The application interprets the returned vehicle speed as km/h and divides by 3.6 for the upstream metre-per-second interface; it does not apply 0.0625 twice. Compare the diagnostic speed against the vehicle display to confirm units on the installed firmware.

Only resultBack=0, finite values in 0–300 km/h and bounded-duration reads are accepted. Successful zero is valid; failure never becomes a zero-speed sample. Disconnection/read errors clear samples. Samples older than 1.5 seconds since the local read are rejected. The service does not expose the underlying CAN-frame timestamp or the ABS failure flag in EcmMod: this local age check cannot detect a vendor service returning a frozen cached CAN value with success.

## CarPlay reporting

On an iPhone location subscription, the provider samples speed at 4 Hz and raw gear at 1 Hz, away from the iAP2 loop. Bluetooth, Wi-Fi and USB have separate owned providers. Valid CAN speed takes priority in the existing original GPRMC speed field; GPS speed remains the fallback. GGA/RMC still require a valid GPS fix; speed never creates a fictitious position.

Independent vehicle-speed reporting reuses the original `VehicleSpeedLocationProvider` and `PascdEncoder`, original Identification vehicleSpeedEnabled declaration, and iPhone VEHICLE_SPEED_DATA subscription. It can send PASCD without a GPS fix once a valid P/R/N/D mapping is configured. Missing/unknown/stale gear stops PASCD. Gear changes discard samples from the previous gear. No BYD mapping is assumed.

The supplied DCT model forwards `TcuInfo.gBPositoionDisplay` without defining its P/R/N/D enumeration. Default independent PASCD advertisement is therefore disabled. Menu → Diagnostics → CAN speed and raw gear (60 seconds) logs raw codes; Menu → Diagnostics → Configure verified gear codes accepts four distinct integers in P,R,N,D order. Save and reconnect to enable the original speed declaration. Clearing the configuration disables it. Stop/subscription shutdown releases sampling and service binding.

Logs distinguish successful reads, rejection, raw gear, RMC CAN-speed use and PASCD sentences provided. Existing iAP2 logs confirm 0xfffb transmission; none of these prove that iOS uses the data for navigation.

## Additional wheel data found

Further inspection of `Saej1939Manager.AbsInfo` found `lHRPulseCounter`, `rHRPulseCounter` and corresponding failure flags, alongside vehicleSpeed and vehicleSpeedFailSts. This supplements the earlier AIDL-model-only findings: the stock DrvWheelMod still carries TPM data, but the lower-level CAN class also has rear-wheel pulse counters. Pulse-to-distance scale, rollover, timing and access have not been established; this release does not convert them into speed or claim four individual wheel speeds.

## Vehicle test

Install over v0.15. Keep the working wireless connection and GPS setting enabled. Run the 60-second CAN diagnostic and export the report; compare its speed with the vehicle display. Record the raw gear values corresponding to P/R/N/D only when the vehicle supports these positions. Configure only verified codes, reconnect, then check the iPhone subscription, CAN/RMC source counters, PASCD provider counters and iAP2 transmission logs. The independent speed feature remains unavailable when the actual gearbox cannot provide all required verified codes. No phone destination or GPS coordinates are logged by this new reader.

v0.15 feature integration details remain in FEATURE_MERGE_0.15.md. All new firmware reads and phone use of vehicle speed require hardware acceptance.

## Local verification

104 protocol/compatibility/settings/speed tests and 2 IPv6 tests passed. The suite includes the original six VehicleSpeedNmea tests and four local speed validity/age/mapping/stop tests. SDK18 reference, DEX035, CRC, post-shrink mDNS/EnumMap and APK signature checks passed. The signer matches v0.15 for upgrade installation. Hardware reads and iOS use remain unverified.

APK SHA-256: `517524247e202072990fafc8101829ff3935b6b8ec691353607ea3b95adcbcf1`.
