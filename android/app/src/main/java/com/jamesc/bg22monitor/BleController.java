package com.jamesc.bg22monitor;

import android.annotation.SuppressLint;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;

/** All state and queued GATT operations run on the main looper. */
@SuppressLint("MissingPermission")
public final class BleController {
    static UUID uuid(String value) { return UUID.fromString(value); }
    static UUID standard(String value) { return uuid("0000" + value + "-0000-1000-8000-00805f9b34fb"); }
    static final UUID ENV = standard("181a"), TEMP = standard("2a6e"), HUMID = standard("2a6f");
    static final UUID INFO = standard("180a"), VERSION = standard("2a26"), CCC = standard("2902");
    static final UUID BATTERY = standard("180f"), BATTERY_LEVEL = standard("2a19");
    static final UUID OTA = uuid("1d14d6ee-fd63-4fa1-bfa4-8f47b42119f0");
    static final UUID CONTROL = uuid("f7bf3564-fb6d-4e53-88a4-5e37e0326063");
    static final UUID DATA = uuid("984227f3-34fc-4045-a5d0-2c581f81a153");
    static final UUID SETTINGS = uuid("f2b90001-4e7d-4eaf-a5bf-1d23c7aa0001");
    static final UUID PERIOD = uuid("f2b90002-4e7d-4eaf-a5bf-1d23c7aa0001");

    public interface Listener {
        void message(String text);
        void device(BluetoothDevice device, String name, int rssi);
        void scanning(boolean active);
        void connection(boolean ready, boolean otaAvailable, String version);
        void reading(boolean temperature, double value);
        void otaProgress(boolean active, int percent, String text);
        void reportingPeriod(boolean available, int seconds);
        void battery(int percent);
    }
    private enum Kind { MTU, DISCOVER, READ, DESCRIPTOR, WRITE }
    private static final class Op {
        final Kind kind; final BluetoothGattCharacteristic characteristic;
        final BluetoothGattDescriptor descriptor; final byte[] value; final Consumer<byte[]> done;
        Op(Kind k, BluetoothGattCharacteristic c, BluetoothGattDescriptor d, byte[] v, Consumer<byte[]> next) {
            kind=k; characteristic=c; descriptor=d; value=v; done=next;
        }
    }
    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final BluetoothAdapter adapter;
    private final ArrayDeque<Op> queue = new ArrayDeque<>();
    private BluetoothGatt gatt;
    private BluetoothDevice target;
    private Op current;
    private Runnable operationTimeout;
    private boolean scanning, ready, updating, reconnecting, closed;
    private int mtu=23, offset, reconnectAttempts;
    private String version="Unknown";
    private OtaImage image;
    private BluetoothGattCharacteristic temperature, humidity, revision, control, data, period, battery;
    private final Runnable scanTimeout = this::stopScan;

    public BleController(Context context, Listener listener) {
        this.context=context.getApplicationContext(); this.listener=listener;
        adapter=context.getSystemService(BluetoothManager.class).getAdapter();
    }
    public boolean bluetoothEnabled() { return adapter != null && adapter.isEnabled(); }
    public boolean isReady() { return ready; }
    public boolean isUpdating() { return updating || reconnecting; }

    public void scan() {
        if (closed || isUpdating()) return;
        disconnect();
        BluetoothLeScanner scanner=adapter.getBluetoothLeScanner();
        if (scanner == null) { listener.message("Turn on Bluetooth first."); return; }
        scanning=true; listener.scanning(true); listener.message("Scanning for environmental sensors…");
        // One service filter finds compatible boards even if their names change.
        scanner.startScan(Collections.singletonList(new ScanFilter.Builder().setServiceUuid(new ParcelUuid(ENV)).build()),
            new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback);
        main.postDelayed(scanTimeout, 15000);
    }
    public void stopScan() {
        if (!scanning) return;
        scanning=false; main.removeCallbacks(scanTimeout);
        BluetoothLeScanner scanner=adapter.getBluetoothLeScanner();
        if (scanner != null) scanner.stopScan(scanCallback);
        listener.scanning(false);
    }
    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int type, ScanResult result) {
            main.post(() -> {
                if (!scanning) return;
                String name=result.getScanRecord() == null ? null : result.getScanRecord().getDeviceName();
                listener.device(result.getDevice(), name == null ? "Environmental sensor" : name, result.getRssi());
            });
        }
        @Override public void onScanFailed(int error) {
            main.post(() -> { stopScan(); listener.message("Scan failed (" + error + "). Wait a moment and try again."); });
        }
    };

    public void connect(BluetoothDevice device) {
        if (closed || isUpdating()) return;
        stopScan(); releaseGatt(); target=device; version="Unknown";
        openConnection();
    }
    private void openConnection() {
        listener.message(reconnecting ? "Reconnecting to verify the update…" : "Connecting…");
        ready=false;
        gatt=target.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE);
        BluetoothGatt attempt=gatt;
        main.postDelayed(() -> {
            if (gatt == attempt && !ready) {
                if (reconnecting) retryReconnect(); else fail("Connection timed out. Disconnect other BLE apps and try again.");
            }
        }, 15000);
    }
    public void disconnect() {
        stopScan();
        boolean wasUpdating=isUpdating();
        updating=false; reconnecting=false; image=null;
        releaseGatt();
        listener.connection(false, false, "Unknown");
        if (wasUpdating) listener.otaProgress(false, 0, "Update cancelled. The previous firmware remains active unless installation had begun.");
    }
    private void releaseGatt() {
        cancelOperations(); ready=false;
        if (gatt != null) { BluetoothGatt old=gatt; gatt=null; old.disconnect(); old.close(); }
        temperature=null; humidity=null; revision=null; control=null; data=null; period=null; battery=null;
        listener.battery(-1);
        listener.reportingPeriod(false, 2);
    }
    public void close() { closed=true; disconnect(); main.removeCallbacksAndMessages(null); }

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int status, int state) {
            main.post(() -> {
                if (g != gatt) return;
                if (status == BluetoothGatt.GATT_SUCCESS && state == BluetoothProfile.STATE_CONNECTED) {
                    mtu=23;
                    enqueue(new Op(Kind.MTU, null, null, null, ignored ->
                        enqueue(new Op(Kind.DISCOVER, null, null, null, value -> setup()))));
                } else if (state == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                    releaseGatt(); listener.connection(false, false, version);
                    if (reconnecting) retryReconnect();
                    else if (updating) fail("Transfer interrupted. Reconnect and restart the update.");
                    else listener.message("Disconnected" + (status == 0 ? "." : " (Bluetooth status " + status + ")."));
                }
            });
        }
        @Override public void onMtuChanged(BluetoothGatt g, int size, int status) {
            main.post(() -> { if (g == gatt) { mtu=status == 0 ? size : 23; complete(Kind.MTU, null, 0, null); } });
        }
        @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
            main.post(() -> { if (g == gatt) complete(Kind.DISCOVER, null, status, null); });
        }
        @Override public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value, int status) {
            main.post(() -> { if (g == gatt) complete(Kind.READ, c.getUuid(), status, value); });
        }
        @Override @SuppressWarnings("deprecation") public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            if (Build.VERSION.SDK_INT < 33) {
                byte[] value=c.getValue() == null ? new byte[0] : c.getValue().clone();
                main.post(() -> { if (g == gatt) complete(Kind.READ, c.getUuid(), status, value); });
            }
        }
        @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            main.post(() -> { if (g == gatt) complete(Kind.DESCRIPTOR, d.getCharacteristic().getUuid(), status, null); });
        }
        @Override public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            main.post(() -> { if (g == gatt) complete(Kind.WRITE, c.getUuid(), status, null); });
        }
        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
            main.post(() -> { if (g == gatt) receive(c, value); });
        }
        @Override @SuppressWarnings("deprecation") public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            if (Build.VERSION.SDK_INT < 33) {
                byte[] value=c.getValue() == null ? new byte[0] : c.getValue().clone();
                main.post(() -> { if (g == gatt) receive(c, value); });
            }
        }
    };

    private BluetoothGattCharacteristic find(UUID service, UUID characteristic) {
        BluetoothGattService s=gatt.getService(service);
        return s == null ? null : s.getCharacteristic(characteristic);
    }
    private void setup() {
        temperature=find(ENV, TEMP); humidity=find(ENV, HUMID); revision=find(INFO, VERSION);
        control=find(OTA, CONTROL); data=find(OTA, DATA);
        period=find(SETTINGS, PERIOD);
        battery=find(BATTERY, BATTERY_LEVEL);
        if (battery != null && (battery.getProperties() & BluetoothGattCharacteristic.PROPERTY_READ) == 0) battery=null;
        if (temperature == null || humidity == null) { fail("This device does not provide temperature and humidity."); return; }
        read(temperature); read(humidity);
        subscribe(temperature); subscribe(humidity);
        if (battery != null) read(battery);
        else listener.battery(-1);
        if (revision != null) enqueue(new Op(Kind.READ, revision, null, null, bytes -> {
            version=new String(bytes, StandardCharsets.UTF_8).trim();
        }));
        if (period != null) readPeriod();
        else listener.reportingPeriod(false, 2);
        // A queued read provides a final barrier after CCC writes/version read.
        enqueue(new Op(Kind.READ, temperature, null, null, bytes -> {
            receive(temperature, bytes); ready=true;
            listener.connection(true, control != null && data != null, version);
            if (reconnecting) {
                if (image == null || !Long.toString(image.version).equals(version)) {
                    fail("The board reconnected with firmware " + version + "; the requested update was not confirmed."); return;
                }
                reconnecting=false; updating=false; image=null;
                listener.otaProgress(false, 100, "Update verified. Firmware " + version + " is running.");
            }
            listener.message("Connected. Live readings follow the board's reporting period.");
        }));
    }
    private void read(BluetoothGattCharacteristic c) {
        enqueue(new Op(Kind.READ, c, null, null, bytes -> receive(c, bytes)));
    }
    private void receive(BluetoothGattCharacteristic c, byte[] bytes) {
        try {
            if (c.getUuid().equals(TEMP)) listener.reading(true, SensorCodec.temperature(bytes));
            else if (c.getUuid().equals(HUMID)) listener.reading(false, SensorCodec.humidity(bytes));
            else if (c.getUuid().equals(BATTERY_LEVEL)) listener.battery(SensorCodec.batteryLevel(bytes));
        } catch (IllegalArgumentException e) { listener.message(e.getMessage()); }
    }
    public void refresh() { if (ready && !isUpdating()) { read(temperature); read(humidity); if (battery != null) read(battery); } }
    private void readPeriod() {
        enqueue(new Op(Kind.READ, period, null, null, bytes -> {
            try { listener.reportingPeriod(true, SensorCodec.decodePeriodSeconds(bytes)); }
            catch (IllegalArgumentException e) { fail(e.getMessage()); }
        }));
    }
    public void setReportingPeriod(int seconds) {
        if (!ready || isUpdating() || period == null) return;
        byte[] value;
        try { value=SensorCodec.encodePeriodSeconds(seconds); }
        catch (IllegalArgumentException e) { listener.message(e.getMessage()); return; }
        if (current != null || !queue.isEmpty()) {
            listener.message("Wait for the current read or setting change to finish, then apply again."); return;
        }
        enqueue(new Op(Kind.WRITE, period, null, value, ignored -> readPeriod()));
    }
    private void subscribe(BluetoothGattCharacteristic c) {
        if ((c.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0) return;
        BluetoothGattDescriptor ccc=c.getDescriptor(CCC);
        if (ccc == null || !gatt.setCharacteristicNotification(c, true)) { fail("Cannot subscribe to sensor updates."); return; }
        enqueue(new Op(Kind.DESCRIPTOR, c, ccc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE, ignored -> {}));
    }

    public void update(OtaImage selected) {
        if (!ready || isUpdating() || control == null || data == null) return;
        if (!queue.isEmpty() || current != null) { listener.message("Wait for the current sensor read to finish, then start the update."); return; }
        updating=true; image=selected; offset=0;
        listener.otaProgress(true, 0, "Starting firmware transfer…");
        gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
        enqueue(new Op(Kind.WRITE, control, null, new byte[]{0}, ignored -> sendChunk()));
    }
    private void sendChunk() {
        if (!updating || image == null) return;
        if (offset == image.bytes.length) {
            enqueue(new Op(Kind.WRITE, control, null, new byte[]{3}, ignored -> {
                updating=false; reconnecting=true; reconnectAttempts=0;
                listener.otaProgress(true, 100, "Transferred. The board is verifying and installing the image…");
                releaseGatt(); listener.connection(false, false, version);
                main.postDelayed(this::retryReconnect, 4000);
            }));
            return;
        }
        int chunk=Math.min(image.bytes.length-offset, Math.min(244, Math.max(20, mtu-3)) & ~3);
        byte[] bytes=Arrays.copyOfRange(image.bytes, offset, offset+chunk);
        enqueue(new Op(Kind.WRITE, data, null, bytes, ignored -> {
            offset+=bytes.length;
            int percent=(int)(100L*offset/image.bytes.length);
            listener.otaProgress(true, percent, "Transferring firmware · " + percent + "%");
            sendChunk();
        }));
    }
    private void retryReconnect() {
        if (closed || !reconnecting) return;
        releaseGatt();
        if (++reconnectAttempts > 4) { fail("The board did not return with the new firmware. Reconnect to check its version."); return; }
        main.postDelayed(() -> { if (reconnecting && !closed) openConnection(); }, 1500);
    }

    private void enqueue(Op op) { queue.add(op); startNext(); }
    @SuppressWarnings("deprecation") private void startNext() {
        if (current != null || queue.isEmpty() || gatt == null) return;
        current=queue.remove(); Op op=current; boolean accepted;
        switch (op.kind) {
            case MTU: accepted=gatt.requestMtu(247); break;
            case DISCOVER: accepted=gatt.discoverServices(); break;
            case READ: accepted=gatt.readCharacteristic(op.characteristic); break;
            case DESCRIPTOR:
                if (Build.VERSION.SDK_INT >= 33) accepted=gatt.writeDescriptor(op.descriptor, op.value) == BluetoothStatusCodes.SUCCESS;
                else { op.descriptor.setValue(op.value); accepted=gatt.writeDescriptor(op.descriptor); }
                break;
            case WRITE:
                if (Build.VERSION.SDK_INT >= 33) accepted=gatt.writeCharacteristic(op.characteristic, op.value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS;
                else {
                    op.characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                    op.characteristic.setValue(op.value); accepted=gatt.writeCharacteristic(op.characteristic);
                }
                break;
            default: accepted=false;
        }
        if (!accepted) { fail("Bluetooth could not start " + op.kind.name().toLowerCase(Locale.ROOT) + ". Reconnect and try again."); return; }
        operationTimeout=() -> {
            if (current != op) return;
            if (op.kind == Kind.MTU) complete(Kind.MTU, null, 0, null);
            else fail("Bluetooth operation timed out. Reconnect and try again.");
        };
        main.postDelayed(operationTimeout, op.kind == Kind.MTU ? 5000 : 15000);
    }
    private void complete(Kind kind, UUID characteristic, int status, byte[] value) {
        if (current == null || current.kind != kind) return;
        if (characteristic != null && !current.characteristic.getUuid().equals(characteristic)) return;
        main.removeCallbacks(operationTimeout);
        Op finished=current; current=null;
        if (status != BluetoothGatt.GATT_SUCCESS) {
            // Battery is optional: an unavailable level must not disable monitoring or OTA.
            if (kind == Kind.READ && BATTERY_LEVEL.equals(characteristic)) {
                listener.battery(-1); startNext(); return;
            }
            fail("Bluetooth " + kind.name().toLowerCase(Locale.ROOT) + " failed (" + status + "). Reconnect and retry."); return;
        }
        finished.done.accept(value);
        startNext();
    }
    private void cancelOperations() {
        if (operationTimeout != null) main.removeCallbacks(operationTimeout);
        current=null; queue.clear();
    }
    private void fail(String message) {
        boolean ota=isUpdating();
        updating=false; reconnecting=false; image=null;
        releaseGatt(); listener.connection(false, false, version);
        listener.message(message);
        if (ota) listener.otaProgress(false, 0, message);
    }
}
