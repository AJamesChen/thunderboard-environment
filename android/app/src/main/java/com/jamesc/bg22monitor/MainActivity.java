package com.jamesc.bg22monitor;

import android.Manifest;
import android.app.*;
import android.bluetooth.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import android.view.*;
import android.widget.*;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity implements BleController.Listener {
    private static final int PERMISSIONS=10, ENABLE_BT=11, PICK_FILE=12;
    private static final int BACKGROUND=0xff101b29, CARD=0xff1b2a3b, TEXT=0xfff0f5fa, MUTED=0xffa3b5c8, ACCENT=0xff6ce5c2;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExecutorService files=Executors.newSingleThreadExecutor();
    private final Map<String, Button> deviceButtons=new LinkedHashMap<>();
    private BleController ble;
    private LinearLayout devices;
    private TextView status, temperature, humidity, updated, firmware, packageInfo, otaStatus;
    private Button scan, disconnect, refresh, choose, install;
    private ProgressBar progress;
    private OtaImage selected;
    private boolean ready, otaAvailable, loadingFile, destroyed, scanning;
    private long temperatureTime, humidityTime;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi(); ble=new BleController(this, this);
        handler.post(tick);
        message("Ready to scan. Power your Thunderboard and disconnect other BLE clients.");
    }
    private int dp(float value) { return (int)(getResources().getDisplayMetrics().density*value+0.5f); }
    private GradientDrawable background(int color, float radius) {
        GradientDrawable d=new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d;
    }
    private TextView label(String text, int size, int color) {
        TextView view=new TextView(this); view.setText(text); view.setTextSize(size); view.setTextColor(color);
        view.setPadding(0, dp(3), 0, dp(3)); return view;
    }
    private LinearLayout column() {
        LinearLayout layout=new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); return layout;
    }
    private LinearLayout card(LinearLayout parent) {
        LinearLayout box=column(); box.setPadding(dp(18),dp(16),dp(18),dp(16)); box.setBackground(background(CARD,20));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2); p.bottomMargin=dp(16); parent.addView(box,p); return box;
    }
    private Button button(String text, boolean primary) {
        Button b=new Button(this); b.setText(text); b.setAllCaps(false); b.setTextSize(15); b.setMinHeight(dp(48));
        b.setTextColor(primary ? BACKGROUND : TEXT);
        b.setBackgroundTintList(ColorStateList.valueOf(primary ? ACCENT : 0xff30445a));
        b.setPadding(dp(12),dp(4),dp(12),dp(4)); return b;
    }
    private void buildUi() {
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(BACKGROUND);
        LinearLayout root=column(); root.setPadding(dp(20),dp(24),dp(20),dp(24)); scroll.addView(root);
        TextView eyebrow=label("THUNDERBOARD  /  BLUETOOTH LE",11,ACCENT); eyebrow.setLetterSpacing(.12f); root.addView(eyebrow);
        TextView title=label("BG22 Monitor",32,TEXT); title.setTypeface(null,Typeface.BOLD); root.addView(title);
        root.addView(label("Your room, measured live.",16,MUTED));
        status=label("Ready to scan",14,MUTED); status.setPadding(0,dp(12),0,dp(18)); status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); root.addView(status);

        LinearLayout nearby=card(root); nearby.addView(label("Nearby sensors",19,TEXT));
        scan=button("Scan for sensors",true); nearby.addView(scan); scan.setOnClickListener(v -> scanPressed());
        devices=column(); nearby.addView(devices);
        disconnect=button("Disconnect",false); nearby.addView(disconnect); disconnect.setEnabled(false);
        disconnect.setOnClickListener(v -> { ble.disconnect(); message("Disconnected. Scan to connect again."); });

        LinearLayout readings=card(root); readings.addView(label("Environment",19,TEXT));
        LinearLayout row=new LinearLayout(this);
        LinearLayout left=column(), right=column();
        row.addView(left,new LinearLayout.LayoutParams(0,-2,1)); row.addView(right,new LinearLayout.LayoutParams(0,-2,1));
        left.addView(label("TEMPERATURE",11,MUTED)); right.addView(label("HUMIDITY",11,MUTED));
        temperature=label("—",34,TEXT); humidity=label("—",34,TEXT);
        temperature.setTypeface(null,Typeface.BOLD); humidity.setTypeface(null,Typeface.BOLD);
        left.addView(temperature); right.addView(humidity);
        left.addView(label("degrees Celsius",12,ACCENT)); right.addView(label("% relative humidity",12,ACCENT)); readings.addView(row);
        updated=label("Connect to see live readings",12,MUTED); readings.addView(updated);
        refresh=button("Read now",false); refresh.setEnabled(false); refresh.setOnClickListener(v -> ble.refresh()); readings.addView(refresh);

        LinearLayout ota=card(root); ota.addView(label("Firmware update",19,TEXT));
        firmware=label("Board firmware · not connected",13,MUTED); ota.addView(firmware);
        ota.addView(label("Experimental OTA. Select a BG22 update package and keep the app open and the board powered. USB remains available for firmware installation.",14,MUTED));
        choose=button("Choose update file",false); ota.addView(choose);
        choose.setOnClickListener(v -> {
            Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(intent,PICK_FILE);
        });
        packageInfo=label("No .bg22ota file selected",13,MUTED); ota.addView(packageInfo);
        install=button("Update firmware",true); install.setEnabled(false); ota.addView(install);
        install.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("Install firmware " + selected.version + "?")
            .setMessage("The board will restart after it verifies the transfer. Leave the app open and keep the board powered.")
            .setNegativeButton("Cancel",null).setPositiveButton("Install",(d,w) -> ble.update(selected)).show());
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal); progress.setMax(100);
        progress.setProgressTintList(ColorStateList.valueOf(ACCENT)); ota.addView(progress,new LinearLayout.LayoutParams(-1,dp(12)));
        otaStatus=label("Connect to check OTA support.",13,MUTED); otaStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); ota.addView(otaStatus);
        root.addView(label("Onboard Si7021  ·  Updates every 2 seconds\nReadings stay on your phone. No account required.",12,MUTED));
        setContentView(scroll);
    }

    private boolean hasPermissions() {
        return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED
            && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED;
    }
    private void scanPressed() {
        if (scanning) { ble.stopScan(); return; }
        if (!hasPermissions()) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT},PERMISSIONS); return;
        }
        if (!ble.bluetoothEnabled()) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED) return;
            startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE),ENABLE_BT); return;
        }
        devices.removeAllViews(); deviceButtons.clear();
        try { ble.scan(); } catch (RuntimeException e) { message("Could not start Bluetooth: " + e.getMessage()); }
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request,permissions,grants);
        if (request==PERMISSIONS) {
            if (hasPermissions()) scanPressed();
            else message("Nearby Devices permission is needed to find and connect to your board. Enable it in Android app settings.");
        }
    }
    @Override public void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if (request==ENABLE_BT && result==RESULT_OK) scanPressed();
        if (request!=PICK_FILE || result!=RESULT_OK || data==null || data.getData()==null) return;
        Uri uri=data.getData(); loadingFile=true; selected=null; updateButtons(); packageInfo.setText("Checking update package…");
        files.execute(() -> {
            try (InputStream in=getContentResolver().openInputStream(uri)) {
                OtaImage parsed=OtaImage.read(in);
                handler.post(() -> {
                    if (destroyed) return;
                    loadingFile=false; selected=parsed;
                    packageInfo.setText("Firmware " + parsed.version + " · " + String.format(Locale.getDefault(),"%.1f KiB",parsed.bytes.length/1024.0)
                        + "\nBRD4184A A02 · checksum verified");
                    updateButtons();
                });
            } catch (Exception e) {
                handler.post(() -> { if (!destroyed) { loadingFile=false; selected=null; packageInfo.setText("Cannot use this file: " + e.getMessage()); updateButtons(); } });
            }
        });
    }
    private void updateButtons() {
        boolean busy=ble!=null && ble.isUpdating();
        scan.setEnabled(!busy); disconnect.setEnabled(ready && !busy); refresh.setEnabled(ready && !busy);
        choose.setEnabled(!busy && !loadingFile); install.setEnabled(ready && otaAvailable && selected!=null && !busy && !loadingFile);
        for (Button b:deviceButtons.values()) b.setEnabled(!busy);
        if (busy) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
    @Override public void message(String text) { status.setText(text); }
    @Override public void scanning(boolean active) {
        scanning=active; scan.setText(active ? "Stop scanning" : "Scan for sensors");
        if (!active && deviceButtons.isEmpty()) message("No sensors found. Check board power and disconnect your desktop or other BLE apps.");
    }
    @Override public void device(BluetoothDevice device,String name,int rssi) {
        @SuppressWarnings("MissingPermission") String address=device.getAddress();
        Button b=deviceButtons.get(address);
        if (b==null) {
            b=button("",false); deviceButtons.put(address,b); devices.addView(b);
            b.setOnClickListener(v -> {
                if (!hasPermissions()) { message("Grant Nearby Devices permission, then scan again."); return; }
                temperature.setText("—"); humidity.setText("—"); temperatureTime=0; humidityTime=0;
                ble.connect(device);
            });
        }
        b.setText(name + "\n" + address + "  ·  " + rssi + " dBm"); b.setContentDescription("Connect to " + name + ", signal " + rssi + " dBm");
    }
    @Override public void connection(boolean connected,boolean supportsOta,String version) {
        ready=connected; otaAvailable=supportsOta;
        firmware.setText(connected ? "Board firmware · " + version : "Board firmware · disconnected");
        if (!ble.isUpdating()) otaStatus.setText(connected ? supportsOta ? "Experimental OTA available. Choose a .bg22ota file to test an update." : "Monitoring ready. Install the OTA bootloader and firmware once over USB to enable updates." : "Connect to check OTA support.");
        updateButtons();
    }
    @Override public void reading(boolean isTemperature,double value) {
        if (isTemperature) { temperature.setText(String.format(Locale.getDefault(),"%.2f°",value)); temperatureTime=SystemClock.elapsedRealtime(); }
        else { humidity.setText(String.format(Locale.getDefault(),"%.2f%%",value)); humidityTime=SystemClock.elapsedRealtime(); }
    }
    @Override public void otaProgress(boolean active,int percent,String text) {
        progress.setProgress(percent); otaStatus.setText(text); updateButtons();
    }
    private final Runnable tick=new Runnable() {
        @Override public void run() {
            if (destroyed) return;
            if (!ready) updated.setText("Disconnected · displayed readings may be old");
            else if (temperatureTime==0 || humidityTime==0) updated.setText("Waiting for both sensors…");
            else {
                long seconds=(SystemClock.elapsedRealtime()-Math.min(temperatureTime,humidityTime))/1000;
                updated.setText(seconds>10 ? "No fresh reading for " + seconds + " seconds" : "Live · updated " + seconds + "s ago");
            }
            handler.postDelayed(this,1000);
        }
    };
    @Override public void onBackPressed() {
        if (ble.isUpdating()) new AlertDialog.Builder(this).setTitle("Leave firmware update?")
            .setMessage("Leaving stops the connection. A completed transfer may already be installing.")
            .setNegativeButton("Stay",null).setPositiveButton("Leave",(d,w) -> finish()).show();
        else super.onBackPressed();
    }
    @Override public void onStop() { super.onStop(); if (ble!=null && hasPermissions()) ble.stopScan(); }
    @Override public void onDestroy() {
        destroyed=true; handler.removeCallbacksAndMessages(null); files.shutdownNow();
        if (ble!=null && hasPermissions()) ble.close();
        super.onDestroy();
    }
}
