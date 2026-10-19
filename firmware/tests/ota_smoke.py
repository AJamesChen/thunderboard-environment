#!/usr/bin/env python3
"""Linux hardware test: upload a generated .bg22ota package via BlueZ D-Bus.

Requires python3-dbus, python3-gi, an enabled Bluetooth adapter, and no other
client connected to the board. This intentionally installs the supplied image.
"""
import argparse
import hashlib
import struct
import time
import zipfile

import dbus
from dbus.mainloop.glib import DBusGMainLoop
from gi.repository import GLib


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("package")
    parser.add_argument("--address", default="84:2E:14:31:C1:CB")
    parser.add_argument("--interrupt-after", type=int, help="disconnect after this many bytes; assert old version survives")
    args = parser.parse_args()
    with zipfile.ZipFile(args.package) as archive:
        image = archive.read("application.gbl")
        metadata = dict(line.split("=", 1) for line in archive.read("manifest.properties").decode().splitlines())
    assert metadata["board"] == "BRD4184A-A02"
    assert len(image) <= 196608 and str(len(image)) == metadata["size"]
    assert hashlib.sha256(image).hexdigest() == metadata["sha256"]
    DBusGMainLoop(set_as_default=True)
    bus = dbus.SystemBus()
    manager = dbus.Interface(bus.get_object("org.bluez", "/"), "org.freedesktop.DBus.ObjectManager")
    context = GLib.MainContext.default()

    def pump(seconds):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            while context.pending():
                context.iteration(False)
            time.sleep(0.01)

    def wait_for(condition, timeout=20):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            value = condition()
            if value:
                return value
            pump(0.1)
        raise TimeoutError("Bluetooth state timed out")

    adapter_path = next(p for p, interfaces in manager.GetManagedObjects().items() if "org.bluez.Adapter1" in interfaces)
    adapter = dbus.Interface(bus.get_object("org.bluez", adapter_path), "org.bluez.Adapter1")
    adapter_props = dbus.Interface(bus.get_object("org.bluez", adapter_path), "org.freedesktop.DBus.Properties")
    powered = bool(adapter_props.Get("org.bluez.Adapter1", "Powered"))
    device = None
    discovering = False

    def find_device():
        for path, interfaces in manager.GetManagedObjects().items():
            if str(interfaces.get("org.bluez.Device1", {}).get("Address", "")) == args.address:
                return path

    try:
        if not powered:
            adapter_props.Set("org.bluez.Adapter1", "Powered", dbus.Boolean(True))
        adapter.SetDiscoveryFilter({"Transport": "le"})
        adapter.StartDiscovery()
        discovering = True
        path = wait_for(find_device)
        adapter.StopDiscovery()
        discovering = False
        device = dbus.Interface(bus.get_object("org.bluez", path), "org.bluez.Device1")
        props = dbus.Interface(bus.get_object("org.bluez", path), "org.freedesktop.DBus.Properties")

        def connect():
            device.Connect(timeout=20)
            wait_for(lambda: props.Get("org.bluez.Device1", "ServicesResolved"))

        def characteristic(uuid):
            for char_path, interfaces in manager.GetManagedObjects().items():
                values = interfaces.get("org.bluez.GattCharacteristic1", {})
                if str(char_path).startswith(str(path) + "/") and str(values.get("UUID", "")) == uuid:
                    return dbus.Interface(bus.get_object("org.bluez", char_path), "org.bluez.GattCharacteristic1"), values
            raise AssertionError("Characteristic missing: " + uuid)

        def version():
            c, _ = characteristic("00002a26-0000-1000-8000-00805f9b34fb")
            return bytes(c.ReadValue({})).decode()

        connect()
        before = version()
        print("Connected, current firmware=" + before, flush=True)
        control, _ = characteristic("f7bf3564-fb6d-4e53-88a4-5e37e0326063")
        data, data_props = characteristic("984227f3-34fc-4045-a5d0-2c581f81a153")
        chunk = min(244, int(data_props.get("MTU", 23)) - 3) & ~3
        print(f"Sending {len(image)} bytes in {chunk}-byte acknowledged writes", flush=True)
        control.WriteValue(dbus.Array([0], signature="y"), {"type": "request"})
        pump(0.2)
        last_percent = -1
        for offset in range(0, len(image), chunk):
            packet = image[offset:offset + chunk]
            try:
                data.WriteValue(dbus.Array(packet, signature="y"), {"type": "request"}, timeout=15)
            except dbus.DBusException:
                print(f"Write failed at byte offset {offset}", flush=True)
                raise
            pump(0.01)
            percent = (offset + len(packet)) * 100 // len(image)
            if percent // 10 != last_percent // 10:
                print(f"Transferred {percent}%", flush=True)
                last_percent = percent
            if args.interrupt_after and offset + len(packet) >= args.interrupt_after:
                print("Intentionally interrupting transfer", flush=True)
                break
        else:
            control.WriteValue(dbus.Array([3], signature="y"), {"type": "request"})
        device.Disconnect()
        pump(5)
        for attempt in range(8):
            try:
                connect()
                break
            except dbus.DBusException:
                pump(2)
        else:
            raise AssertionError("Board did not reconnect")
        actual = version()
        expected = before if args.interrupt_after else metadata["version"]
        assert actual == expected, (actual, expected)
        for short, fmt, name in [("2a6e", "<h", "temperature"), ("2a6f", "<H", "humidity")]:
            c, _ = characteristic(f"0000{short}-0000-1000-8000-00805f9b34fb")
            value = struct.unpack(fmt, bytes(c.ReadValue({})))[0] / 100
            print(f"{name}={value}", flush=True)
        print(f"PASS: {'interruption preserved' if args.interrupt_after else 'OTA installed'} firmware {actual}, sensor reads work", flush=True)
    finally:
        if device:
            try:
                device.Disconnect()
            except dbus.DBusException:
                pass
        if discovering:
            adapter.StopDiscovery()
        if not powered:
            adapter_props.Set("org.bluez.Adapter1", "Powered", dbus.Boolean(False))


if __name__ == "__main__":
    main()
