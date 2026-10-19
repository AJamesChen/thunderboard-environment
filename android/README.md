# BG22 Monitor for Android

See the [project README](../README.md) for installation, board provisioning,
Bluetooth use, OTA packages, and build instructions.

OTA is experimental: full transfers have not passed hardware validation yet.
See [the validation record](../firmware/VALIDATION.md) before testing updates.

The app has a scan/connect screen, live temperature and humidity readings,
an update-period editor (1–3,600 seconds for both sensors),
manual reads, stale-reading indication, firmware version display, a document
picker, and acknowledged OTA transfers with progress and version confirmation.
All GATT operations are serialized on the main looper and have timeouts.
Bluetooth callbacks from old connections are ignored. OTA reconnects are bounded;
an interrupted transfer reports an error rather than success.

The period is read from the board when connecting. Applying a value performs an
acknowledged write followed by a readback, so the UI displays the accepted period.
Firmware older than version 4 remains readable, with period controls disabled.
Stale detection scales with the selected interval. The board restores 2 seconds
after a restart; settings are not written to flash.

Only Android Nearby Devices permissions are requested. The app has no network
permission and does not require system Bluetooth pairing. It is intended to
remain open while monitoring or updating; it does not run a background service.

Build with `./gradlew assembleDebug lintDebug`. The APK is generated at
`app/build/outputs/apk/debug/app-debug.apk`. SDK paths in `local.properties` and
build output are local-only and ignored by Git.

The portable `tests/ProtocolTest.java` tests sensor decoding and update-package
validation without an Android device. Pass a real generated `.bg22ota` image to
verify it against the same parser used on the phone. The CI fixture is synthetic
and must never be installed on a board.
