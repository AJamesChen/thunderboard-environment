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
    public static byte[] encodePeriodSeconds(int seconds) {
        if (seconds < 1 || seconds > 3600) throw new IllegalArgumentException("Enter a whole number from 1 to 3600 seconds");
        int ms=seconds*1000;
        return new byte[]{(byte)ms, (byte)(ms >>> 8), (byte)(ms >>> 16), (byte)(ms >>> 24)};
    }
    public static int decodePeriodSeconds(byte[] value) {
        if (value == null || value.length != 4) throw new IllegalArgumentException("Invalid reporting period from board");
        long ms=(value[0]&255L) | (value[1]&255L)<<8 | (value[2]&255L)<<16 | (value[3]&255L)<<24;
        if (ms < 1000 || ms > 3600000 || ms%1000 != 0) throw new IllegalArgumentException("Invalid reporting period from board");
        return (int)(ms/1000);
    }
    public static long staleAfterMillis(int seconds) {
        return Math.max(10000L, seconds*2000L+2000L);
    }
}
