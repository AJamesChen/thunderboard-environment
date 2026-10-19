#!/usr/bin/env python3
"""Verify reporting settings and real notification timing using Linux BlueZ.

Requires python3-dbus, python3-gi, enabled Bluetooth, and an unoccupied board.
Restores the reporting period to 2 seconds and the adapter's previous power state.
"""
import argparse
import struct
import time

import dbus
from dbus.mainloop.glib import DBusGMainLoop
from gi.repository import GLib


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--address", default="84:2E:14:31:C1:CB")
    args = parser.parse_args()
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

    def wait_for(condition, seconds=20):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            result = condition()
            if result:
                return result
            pump(0.1)
        raise TimeoutError("Bluetooth state timed out")

    adapter_path = next(p for p, interfaces in manager.GetManagedObjects().items() if "org.bluez.Adapter1" in interfaces)
    obj = bus.get_object("org.bluez", adapter_path)
    adapter = dbus.Interface(obj, "org.bluez.Adapter1")
    adapter_props = dbus.Interface(obj, "org.freedesktop.DBus.Properties")
    powered = bool(adapter_props.Get("org.bluez.Adapter1", "Powered"))
    device = None
    period = None
    discovering = False
    notifications = {}
    subscriptions = []

    def on_change(interface, changed, invalidated, path=None):
        if interface == "org.bluez.GattCharacteristic1" and "Value" in changed and path in notifications:
            assert len(changed["Value"]) == 2
            notifications[path].append(time.monotonic())

    try:
        if not powered:
            adapter_props.Set("org.bluez.Adapter1", "Powered", dbus.Boolean(True))
        adapter.SetDiscoveryFilter({"Transport": "le"})
        adapter.StartDiscovery()
        discovering = True

        def find_device():
            for path, interfaces in manager.GetManagedObjects().items():
                if str(interfaces.get("org.bluez.Device1", {}).get("Address", "")) == args.address:
                    return path

        device_path = wait_for(find_device)
        adapter.StopDiscovery()
        discovering = False
        obj = bus.get_object("org.bluez", device_path)
        device = dbus.Interface(obj, "org.bluez.Device1")
        props = dbus.Interface(obj, "org.freedesktop.DBus.Properties")

        def connect():
            device.Connect(timeout=20)
            wait_for(lambda: props.Get("org.bluez.Device1", "ServicesResolved"))

        def characteristic(uuid):
            for path, interfaces in manager.GetManagedObjects().items():
                values = interfaces.get("org.bluez.GattCharacteristic1", {})
                if str(path).startswith(str(device_path) + "/") and str(values.get("UUID", "")) == uuid:
                    return path, dbus.Interface(bus.get_object("org.bluez", path), "org.bluez.GattCharacteristic1")
            raise AssertionError("Characteristic missing: " + uuid)

        connect()
        _, period = characteristic("f2b90002-4e7d-4eaf-a5bf-1d23c7aa0001")

        def write(value):
            period.WriteValue(dbus.Array(value, signature="y"), {"type": "request"})

        def read():
            return struct.unpack("<I", bytes(period.ReadValue({})))[0]

        bus.add_signal_receiver(on_change, dbus_interface="org.freedesktop.DBus.Properties",
                                signal_name="PropertiesChanged", path_keyword="path")
        for short in ["2a6e", "2a6f"]:
            path, char = characteristic(f"0000{short}-0000-1000-8000-00805f9b34fb")
            notifications[path] = []
            char.StartNotify()
            subscriptions.append(char)
        pump(0.3)
        for seconds in [1, 3]:
            for values in notifications.values():
                values.clear()
            write(struct.pack("<I", seconds * 1000))
            assert read() == seconds * 1000
            # Allow connection scheduling/host dispatch latency around the first
            # sample rather than assuming three packets arrive by exactly 3T.
            wait_for(lambda: all(len(samples) >= 3 for samples in notifications.values()),
                     seconds * 5 + 5)
            for path, samples in notifications.items():
                gaps = [b - a for a, b in zip(samples, samples[1:])]
                assert len(samples) >= 3, (seconds, samples)
                assert all(abs(gap - seconds) < 0.6 for gap in gaps), (seconds, gaps)
                print(f"PASS {path.rsplit('/', 1)[-1]}: requested={seconds}s gaps={[round(g, 3) for g in gaps]}", flush=True)
        for value in [1000, 3600000]:
            write(struct.pack("<I", value))
            assert read() == value
        for invalid in [struct.pack("<I", n) for n in [0, 999, 1001, 3600001, 0xffffffff]] + [b"\x01\x02\x03"]:
            try:
                write(invalid)
                raise AssertionError("Invalid period accepted")
            except dbus.DBusException:
                assert read() == 3600000
        write(struct.pack("<I", 5000))
        for char in subscriptions:
            char.StopNotify()
        subscriptions.clear()
        device.Disconnect()
        wait_for(lambda: not props.Get("org.bluez.Device1", "Connected"))
        pump(0.5)
        connect()
        assert read() == 5000
        print("PASS: boundary values, invalid writes preserve the period, and 5s setting survives reconnect", flush=True)
    finally:
        for char in subscriptions:
            try:
                char.StopNotify()
            except dbus.DBusException:
                pass
        if period is not None:
            try:
                period.WriteValue(dbus.Array(struct.pack("<I", 2000), signature="y"), {"type": "request"})
            except dbus.DBusException:
                pass
        if device is not None:
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
