# Validation — 19 October 2026

## Hardware and tools

- Thunderboard EFR32BG22, BRD4184A revision A02
- EFR32BG22C224F512IM40, die A2, 512 KiB flash / 32 KiB RAM
- Simplicity SDK 2026.6.2, SLC CLI 6.0.25, GNU Arm 14.2.rel1
- Connected phone identified by ADB as Pixel 7 Pro, Android 17
- Android SDK 34, build tools 34.0.0, AGP 8.6.1, Gradle 8.14.3

## Passed

- Native C conversion tests with undefined-behavior sanitizer.
- Portable Java tests against actual generated firmware packages and a synthetic
  CI fixture: positive/negative temperatures, humidity, board/version matching,
  transport checksum, GBL CRC, corruption, size limits, and ZIP entry restrictions.
- Bootloader and application generation/build from the final repository layout.
- Commander USB bootstrap programming and verification.
- Android APK build and lint: zero lint errors; remaining warnings concern
  untranslated UI strings and backup-rule metadata.
- APK signature verification (v2 signing).
- APK installation and launch on the connected Pixel 7 Pro.
- Board discovery by name in the Android app, confirmed in its UI hierarchy.
- Real Si7021 readings on USB serial at two-second intervals, for example
  29.877 C and 41.409 %RH.
- Desktop BlueZ GATT reads and repeated notifications from both characteristics.
- Independent notification unsubscribe, disconnect, reconnect, and resubscribe.
- An intentionally interrupted OTA upload at approximately 4 KiB preserved
  firmware version 2. After reconnect, temperature was 27.59 C and humidity was
  44.80 %RH. The serial monitor was closed for this test.

## Configurable reporting period (firmware 4 / Android 1.1)

- C and Java tests cover little-endian period encoding, 1/3,600-second bounds,
  invalid lengths, values outside the range, fractional seconds, and the
  interval-aware stale-reading threshold.
- Firmware 4 build, USB flash verification, Android 1.1 build/lint, APK signature
  verification, and installation on the connected Pixel passed.
- BlueZ measured temperature notification gaps of 1.030/0.997 seconds and
  humidity gaps of 0.989/0.997 seconds with a 1-second setting.
- At 3 seconds, temperature gaps were 2.974/3.058 seconds and humidity gaps were
  3.157/2.876 seconds. These are host-observed timings, including BLE and dispatch jitter.
- 1-second and 3,600-second boundary settings were accepted. Zero, below-minimum,
  fractional-second, above-maximum, overflow, and short writes were rejected
  without changing the accepted setting.
- The 5-second setting survived disconnect/reconnect. The test restored 2 seconds.
- The Pixel app connected, displayed both sensor readings, and applied a 5-second
  period with acknowledged write and BLE readback confirmation. Applying 2 seconds
  restored the default and was also confirmed by readback.
- Settings are intentionally volatile: reconnect retains them; reboot uses the
  2-second default. No flash persistence is implemented.

## Unresolved / not passed

- Full BlueZ OTA uploads fail intermittently with ATT error 0x0e. Failures have
  occurred at different offsets, including 1,952 and 15,372 bytes, and one transfer
  reached over 90% before failing. Firmware version 3 has not been confirmed
  installed through OTA. Do not interpret transfer progress as update success.
- UART RTS/CTS was disabled to remove a possible serial-monitor dependency.
  Blocking sensor conversions are now paused during OTA. The full-transfer
  failure still requires diagnosis; these changes are not claimed as a complete fix.
- Complete phone OTA installation remains unverified.
- Sensor failure recovery has not been fault-injected. Battery life, firmware
  power-loss recovery during installation, and Android background operation have
  not been validated. There is no background collection service.

OTA images are unsigned development images, not authenticated production updates.
The app reports failure rather than success when a transfer or version check fails.

## Backups

The factory 512 KiB image and pre-OTA sensor firmware are retained locally in the
ignored `firmware/backups/` directory. They are not published in Git.
The factory backup SHA-256 is:

```text
b4e8e49be42bc2f1cf6bb55d8a9e52e9fa468ab0dd3b6d5a7227092609ca88ad
```
