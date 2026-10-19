# Thunderboard firmware

The complete setup, phone workflow, flash layout, and OTA protocol are documented
in the [project README](../README.md). This firmware targets BRD4184A revision A02.

Run `python3 firmware/build.py --version 3` from the repository root. The script
builds the internal-storage bootloader, the sensor application, an application
GBL, a phone update package, and a combined USB bootstrap image. Generated files
under `config/`, `autogen/`, and `cmake_*/` are ignored. Persistent application
configuration belongs in `thunderboard_environment.slcp` and the root
`gatt_configuration.btconf`; edit `bootloader/bootloader.slcp` for bootloader
configuration. Increment the application version for each new OTA image.

The app reads the Si7021 every two seconds, exposes standard temperature and
humidity characteristics, and supports read and notify operations. Sensor
failures invalidate cached data and trigger reinitialization. The SDK's
application OTA component stages and verifies an image in the bootloader's
192 KiB slot. The application reboots only after verification succeeds.

Serial logging uses 115200 baud, 8N1, **no RTS/CTS flow control**. Hardware flow
control can stall the main loop when the USB serial monitor is closed and must
remain disabled for autonomous BLE operation.

See [VALIDATION.md](VALIDATION.md) for measured results and tested limitations.
Original local flash backups are retained in `backups/` and excluded from Git.
