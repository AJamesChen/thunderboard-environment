# James's Thunderboard

A Silicon Labs Thunderboard BG22 environment monitor with a native Android app.
The onboard Si7021 measures temperature and relative humidity; Bluetooth LE
carries live readings and application firmware updates. No account, server, or
Internet permission is required by the app.

Target board: **BRD4184A revision A02 / EFR32BG22C224F512IM40**.
The app supports Android 12 and later, including Pixel 7 and Pixel 7 Pro.

**Current status:** the Kotlin app (version 1.2) builds and passes Android lint
and portable battery/sensor/protocol tests. Earlier Android hardware validation
confirmed sensor reads and reporting-period changes on the Pixel; the new Kotlin
screens still need on-device testing. Sensor firmware and desktop BLE
reads/notifications are verified. OTA is experimental: interrupted-upload recovery
passes, but full desktop uploads still fail intermittently with ATT error `0x0e`.
Complete phone OTA installation remains unverified. Use USB provisioning for
dependable firmware installation until this is resolved.

## Main screen reference

<img src="docs/images/monitor-design-reference.png" alt="Proposed Climate Monitor design with a connected board, temperature and humidity cards, history chart, and bottom navigation" width="360">

*User-supplied proposed design with sample readings, not a screenshot captured
from the implemented app or evidence of board behavior.* The implemented UI now
uses **James's Thunderboard** branding and follows this green-and-white layout.
The board label in the mockup is illustrative; this project's target is BRD4184A
A02. The status bar battery belongs to the phone, not the BG22.

## Android app features

| Screen | Implemented behavior |
| --- | --- |
| Monitor | BLE discovery/connect/disconnect, live temperature and humidity, manual reads, stale status, and up to one hour of real session history |
| Update | Package selection and validation, confirmation, experimental OTA progress, and reconnect/version confirmation |
| Settings / Configure | Reporting-period readback, per-board label stored on the phone, and keep-screen-on preference |
| About | App version, board and sensor information, and firmware capability details |

The current board firmware does not expose battery, sleep, or reboot commands.
The app shows battery as unavailable and disables sleep/reboot with an explanation.
Standard Battery Service support is implemented for compatible future firmware.
History starts empty and uses received readings; it clears when switching boards
or restarting the app process. See the [Android guide](android/README.md) for
build instructions and the Pixel 7 test checklist.

## Use the Android app

1. Build or install `BG22 Monitor` using the instructions below.
2. Allow **Nearby Devices** access and turn on Bluetooth.
3. Disconnect the desktop and other BLE apps from the board.
4. Tap **Scan for sensors**, then **BG22 Environment**.
5. Temperature and humidity update automatically every two seconds by default.
   **Read now** requests fresh cached values once.

### Choose an update period

With firmware 4 or newer, open **Settings** and use **Sensor reporting**:

1. Enter a whole number from **1 to 3,600 seconds**.
2. Tap **Apply period**.
3. Check the confirmed **Board reports every … seconds** value.

The setting controls both sensor measurements and notifications. The next sample
is scheduled one selected period after a changed value is accepted. Applying the
same period leaves its current schedule running. The board retains the setting
when Bluetooth disconnects; a board restart restores the **2-second default**.
The setting is kept in RAM and does not write flash. Older firmware continues
to support monitoring, with period controls disabled until it is upgraded.
Stale-reading detection scales with the selected period.

Pairing through Android's system Bluetooth screen is not required. The board
supports one client at a time. Keep the app open during monitoring and updates;
background collection and notifications outside the app are not implemented.

## Update the board over Bluetooth

This workflow is implemented for testing; see the current-status limitation above.

The board first needs the supplied bootloader and OTA-enabled application
installed once over USB. An original sensor image linked at address zero cannot
receive these updates.

1. Build a new firmware version, for example `python3 firmware/build.py --version 4`.
2. Copy `firmware/artifacts/firmware-4.bg22ota` to the phone's Downloads folder.
3. Connect to the board in BG22 Monitor, open **Update**, and tap **Choose update file**.
4. Select the package, tap **Update firmware**, and confirm.
5. Keep the app open and the board powered while it transfers, verifies,
   installs, and reconnects. Success is shown only after the reported firmware
   version matches the selected package.

The app validates the board ID, version metadata, SHA-256, GBL CRC, image size,
and application flash range. It uses acknowledged writes, one at a time, with
chunks sized for the negotiated MTU. The bootloader stages a complete image
before installation. An interrupted upload can be restarted from the beginning;
it is not resumed from an offset.

This is development OTA: images are unsigned and BLE access is unencrypted.
Checksums detect corruption and do not authenticate the publisher. Bootloader,
secure-engine, compressed, and encrypted GBL updates are excluded from this app.

## Build and install Android

Requirements: JDK 17 or 21, Android SDK platform 34, and build tools 34.0.0.
Set `ANDROID_HOME` to your SDK, or configure `android/local.properties` with
`sdk.dir=/path/to/Android/Sdk`.

```bash
cd android
./gradlew --no-daemon assembleDebug lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The APK is a debug-signed personal development build. The Kotlin interface uses
platform Android views and reuses the Java BLE and protocol code. Gradle 8.14.3,
Android Gradle Plugin 8.6.1, and Kotlin 2.1.20 are pinned in the build configuration.
The launcher name remains **BG22 Monitor**; the app's UI branding is
**James's Thunderboard**.

## Build and provision firmware

Install Simplicity Studio 6, Simplicity SDK **2026.6.2**, SLC CLI **6.0.25**,
GNU Arm **14.2.rel1**, CMake, Ninja, and Simplicity Commander using Simplicity
Installer. The build script resolves their locations through
`~/.silabs/tools.json`.

```bash
python3 firmware/build.py --version 4
commander flash firmware/artifacts/bootstrap-4.hex --serialno <debugger-serial>
```

A new SLC installation may need
`slc signature trust --sdk <installed-sdk-path>` for the SDK installed by Silicon
Labs. The build never flashes hardware automatically.

Build products:

| File | Purpose |
| --- | --- |
| `bootstrap-N.hex` | USB installation of matching bootloader and application |
| `firmware-N.gbl` | Uncompressed application GBL for Silicon Labs OTA tooling |
| `firmware-N.bg22ota` | Validated package selected in BG22 Monitor |

These files are generated under `firmware/artifacts/` and excluded from Git.
Keep a backup of the board before first provisioning.

## Layout and protocol

- `android/`: native Android UI, BLE operation queue, sensor decoder, OTA parser.
- `firmware/`: C sensor application and SLC project.
- `firmware/bootloader/`: internal-storage Gecko bootloader project.
- `firmware/tests/`: native value conversion and Linux hardware OTA tests.
- `android/tests/`: portable Java protocol tests.

| Flash region | Address range, end exclusive |
| --- | --- |
| Bootloader reservation | `0x00000–0x06000` |
| Application area | `0x06000–0x44000` |
| 192 KiB update slot | `0x44000–0x74000` |

The build rejects application images that overlap the bootloader or update slot.

| GATT value | UUID | Encoding |
| --- | --- | --- |
| Environmental Sensing service | `181A` | Standard Bluetooth service |
| Temperature | `2A6E` | Signed little-endian 16-bit / 100, degrees C |
| Humidity | `2A6F` | Unsigned little-endian 16-bit / 100, %RH |
| Firmware revision | `2A26` | Decimal application version as UTF-8 |

The custom Reporting Settings service is
`f2b90001-4e7d-4eaf-a5bf-1d23c7aa0001`. Its readable/writable Reporting Period
characteristic is `f2b90002-4e7d-4eaf-a5bf-1d23c7aa0001`: a four-byte unsigned
little-endian value in **milliseconds**, from 1,000 to 3,600,000 in 1,000 ms steps.
For example, `88 13 00 00` selects 5,000 ms (5 seconds). Writes require a response.
Invalid lengths, out-of-range values, fractional seconds, and setting changes
during OTA are rejected without changing the accepted period. The app confirms
changes by reading the value back after the acknowledged write.

OTA uses Silicon Labs' application-based OTA service. A control write of `00`
starts transfer, acknowledged data writes stage the GBL, and `03` finishes it.
Disconnect triggers verification; the application then asks the bootloader to
install the verified image and reboot.

Serial diagnostics use **115200 baud, 8N1, no flow control**. Firmware operation
does not depend on a serial monitor being open.
Sensor conversions are paused during OTA so blocking I2C measurements do not
compete with Bluetooth/flash handling. Sampling resumes after recovery or reboot.

## Verification

See [firmware/VALIDATION.md](firmware/VALIDATION.md) for hardware results and
remaining limitations. Android lint and APK signature verification are part of
the local validation.

To test a generated update package with the portable Java tests:

```bash
mkdir -p /tmp/bg22-tests
javac --release 17 -d /tmp/bg22-tests android/app/src/main/java/com/jamesc/bg22monitor/SensorCodec.java android/app/src/main/java/com/jamesc/bg22monitor/OtaImage.java android/tests/ProtocolTest.java
java -ea -cp /tmp/bg22-tests ProtocolTest firmware/artifacts/firmware-4.bg22ota
gcc -std=c11 -Wall -Wextra -Werror -fsanitize=undefined -I firmware firmware/tests/test_values.c -o /tmp/bg22-test-values
/tmp/bg22-test-values
```

The Linux OTA hardware test requires `python3-dbus`, `python3-gi`, an enabled
Bluetooth adapter, and an unoccupied board. It installs the supplied image:

```bash
python3 firmware/tests/ota_smoke.py firmware/artifacts/firmware-4.bg22ota
```

Add `--interrupt-after 4096` to check that an interrupted transfer preserves
the currently installed firmware.

To verify the reporting setting on real hardware:

```bash
python3 firmware/tests/period_smoke.py --address <board-Bluetooth-address>
```

This measures both notification streams at 1 and 3 seconds, checks accepted
boundaries and rejected values, and verifies reconnect retention. It restores
the period to 2 seconds and the desktop adapter's previous power state afterward.

## References

- [Thunderboard BRD4184A hardware guide](https://www.silabs.com/documents/public/user-guides/ug415-brd4184a-user-guide.pdf)
- [Silicon Labs OTA architecture](https://docs.silabs.com/bluetooth/10.1.2/using-gecko-bootloader-with-bluetooth-apps/03-bluetooth-ota-upgrade)
- [Android BLE data transfer](https://developer.android.com/develop/connectivity/bluetooth/ble/transfer-ble-data)
