package com.jamesc.bg22monitor;

/** Standard Bluetooth Environmental Sensing values are little endian. */
public final class SensorCodec {
    private SensorCodec() {}
    public static double temperature(byte[] value) {
        requireTwoBytes(value);
        return (short)((value[0] & 255) | (value[1] & 255) << 8) / 100.0;
    }
    public static double humidity(byte[] value) {
        requireTwoBytes(value);
        int raw = (value[0] & 255) | (value[1] & 255) << 8;
        if (raw > 10000) throw new IllegalArgumentException("Humidity outside 0–100% range");
        return raw / 100.0;
    }
    private static void requireTwoBytes(byte[] value) {
        if (value == null || value.length != 2) throw new IllegalArgumentException("Expected a two-byte sensor reading");
    }
}
