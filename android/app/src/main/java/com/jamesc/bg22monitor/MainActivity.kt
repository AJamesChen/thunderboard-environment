package com.jamesc.bg22monitor

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*
import java.util.Locale
import java.util.concurrent.Executors

/** Kotlin presentation layer; the serialized BLE transport and package validator remain shared Java. */
@Suppress("DEPRECATION")
class MainActivity : Activity(), BleController.Listener {
    private val main = Handler(Looper.getMainLooper())
    private val files = Executors.newSingleThreadExecutor()
    private lateinit var ble: BleController
    private lateinit var pages: FrameLayout
    private lateinit var status: TextView
    private lateinit var deviceName: TextView
    private lateinit var deviceState: TextView
    private lateinit var battery: TextView
    private lateinit var temperature: TextView
    private lateinit var humidity: TextView
    private lateinit var updated: TextView
    private lateinit var firmware: TextView
    private lateinit var packageInfo: TextView
    private lateinit var otaStatus: TextView
    private lateinit var periodStatus: TextView
    private lateinit var devices: LinearLayout
    private lateinit var scan: Button
    private lateinit var disconnect: Button
    private lateinit var refresh: Button
    private lateinit var choose: Button
    private lateinit var install: Button
    private lateinit var applyPeriod: Button
    private lateinit var periodInput: EditText
    private lateinit var progress: ProgressBar
    private lateinit var chart: HistoryView
    private lateinit var title: TextView
    private val screens = mutableListOf<View>()
    private val tabs = mutableListOf<TextView>()
    private val deviceButtons = linkedMapOf<String, Button>()
    private var selected: OtaImage? = null
    private var ready = false
    private var otaAvailable = false
    private var periodAvailable = false
    private var scanning = false
    private var loadingFile = false
    private var destroyed = false
    private var periodSeconds = 2
    private var temperatureTime = 0L
    private var humidityTime = 0L
    private var address = ""
    private var advertisedName = "James's Thunderboard BG22"
    private var signal: Int? = null
    private val preferences by lazy { getSharedPreferences("climate", MODE_PRIVATE) }
    private var activeTab = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        ble = BleController(this, this)
        selectTab(savedInstanceState?.getInt("tab", 0) ?: 0)
        message("Power your board, then scan to connect.")
        main.post(tick)
    }

    private fun dp(value: Int) = (resources.displayMetrics.density * value + .5f).toInt()
    private fun shape(color: Int, radius: Int = 24) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
    }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun label(value: String, size: Int = 14, color: Int = MUTED) = TextView(this).apply {
        text = value; textSize = size.toFloat(); setTextColor(color)
        setPadding(0, dp(4), 0, dp(4))
    }
    private fun heading(value: String) = label(value, 20, TEXT).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private fun button(value: String, action: () -> Unit) = Button(this).apply {
        text = value; isAllCaps = false; textSize = 14f
        minHeight = dp(48); minimumHeight = dp(48)
        setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()), intArrayOf(MUTED, GREEN)))
        backgroundTintList = ColorStateList.valueOf(TINT)
        setOnClickListener { action() }
    }
    private fun card(parent: LinearLayout) = column().apply {
        setPadding(dp(18), dp(16), dp(18), dp(16)); background = shape(Color.WHITE)
        parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
    }
    private fun page(): LinearLayout {
        val scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false }
        val content = column().apply { setPadding(dp(18), dp(12), dp(18), dp(24)) }
        scroll.addView(content); pages.addView(scroll); screens.add(scroll)
        return content
    }
    private fun buildUi() {
        val root = column().apply { setBackgroundColor(BACKGROUND) }
        val header = column().apply { setPadding(dp(20), dp(16), dp(20), dp(8)) }
        header.addView(label("James's Thunderboard", 12))
        title = label("Climate", 32, TEXT).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
        header.addView(title); root.addView(header)
        pages = FrameLayout(this); root.addView(pages, LinearLayout.LayoutParams(-1, 0, 1f))
        buildMonitor(page()); buildUpdate(page()); buildSettings(page()); buildAbout(page())
        val navigation = LinearLayout(this).apply {
            setPadding(dp(8), dp(8), dp(8), dp(8)); setBackgroundColor(Color.WHITE)
        }
        listOf("Monitor", "Update", "Settings", "About").forEachIndexed { index, name ->
            val tab = label(name, 13, TEXT).apply {
                gravity = Gravity.CENTER; minHeight = dp(64)
                setCompoundDrawablesWithIntrinsicBounds(0, listOf(R.drawable.ic_monitor, R.drawable.ic_update, R.drawable.ic_settings, R.drawable.ic_about)[index], 0, 0)
                compoundDrawablePadding = dp(5)
                isClickable = true; isFocusable = true
                setOnClickListener { selectTab(index) }
            }
            tabs.add(tab); navigation.addView(tab, LinearLayout.LayoutParams(0, -2, 1f))
        }
        root.addView(navigation); setContentView(root)
    }
    private fun selectTab(index: Int) {
        activeTab = index.coerceIn(0, 3)
        screens.forEachIndexed { i, view -> view.visibility = if (i == activeTab) View.VISIBLE else View.GONE }
        tabs.forEachIndexed { i, view ->
            view.background = shape(if (i == activeTab) TINT else Color.WHITE, 18)
            view.setTextColor(if (i == activeTab) GREEN else TEXT)
            view.isSelected = i == activeTab
        }
        title.text = listOf("Climate", "Firmware update", "Configure", "About")[activeTab]
    }
    private fun buildMonitor(parent: LinearLayout) {
        val connection = card(parent)
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val details = column()
        deviceName = heading("James's Thunderboard BG22"); details.addView(deviceName)
        deviceState = label("○ Disconnected", 13, GREEN); details.addView(deviceState)
        row.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
        disconnect = button("Disconnect") { ble.disconnect() }.apply { isEnabled = false }
        row.addView(disconnect); connection.addView(row)
        connection.addView(label("James's Thunderboard BG22 · BRD4184A", 13))
        battery = label("Board battery · unavailable in this firmware", 12); connection.addView(battery)
        status = label("Ready to scan", 13).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        connection.addView(status)
        scan = button("Scan for sensors") { scanPressed() }; connection.addView(scan)
        devices = column(); connection.addView(devices)

        val readings = LinearLayout(this)
        val left = column().apply { setPadding(dp(16), dp(16), dp(12), dp(16)); background = shape(Color.WHITE) }
        val right = column().apply { setPadding(dp(16), dp(16), dp(12), dp(16)); background = shape(Color.WHITE) }
        readings.addView(left, LinearLayout.LayoutParams(0, -1, 1f).apply { marginEnd = dp(6) })
        readings.addView(right, LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = dp(6) })
        left.addView(label("Temperature", 16).apply {
            setCompoundDrawablesWithIntrinsicBounds(0, R.drawable.ic_temperature, 0, 0)
            compoundDrawablePadding = dp(10)
            gravity = Gravity.START
        })
        right.addView(label("Humidity", 16).apply {
            setCompoundDrawablesWithIntrinsicBounds(0, R.drawable.ic_humidity, 0, 0)
            compoundDrawablePadding = dp(10)
            gravity = Gravity.START
        })
        temperature = label("— °C", 34, TEXT); humidity = label("— %", 34, TEXT)
        listOf(temperature, humidity).forEach { it.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); it.setPadding(0, dp(16), 0, dp(12)) }
        left.addView(temperature); right.addView(humidity)
        updated = label("No readings yet", 12); left.addView(updated)
        right.addView(label("Relative humidity", 12))
        parent.addView(readings, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        val history = card(parent)
        val chartHeader = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        chartHeader.addView(heading("Temperature history"), LinearLayout.LayoutParams(0, -2, 1f))
        chartHeader.addView(label("Last hour", 12)); history.addView(chartHeader)
        chart = HistoryView(this); history.addView(chart, LinearLayout.LayoutParams(-1, dp(180)))
        history.addView(label("Live readings · this session only", 12))
        refresh = button("Read now") { ble.refresh() }.apply { isEnabled = false }; parent.addView(refresh)
    }
    private fun buildUpdate(parent: LinearLayout) {
        val box = card(parent)
        box.addView(heading("Board firmware")); firmware = label("Not connected"); box.addView(firmware)
        box.addView(label("OTA is experimental. Full phone installation has not yet been verified. Keep the app open and the board powered during transfer and installation."))
        choose = button("Choose update file") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE), PICK_FILE)
        }; box.addView(choose)
        packageInfo = label("Select a .bg22ota package"); box.addView(packageInfo)
        install = button("Update firmware") {
            val image = selected ?: return@button
            AlertDialog.Builder(this).setTitle("Install firmware ${image.version}?")
                .setMessage("Keep the board powered and this app open. The board will verify the image and restart. OTA is experimental; USB installation remains available.")
                .setNegativeButton("Cancel", null).setPositiveButton("Install") { _, _ -> ble.update(image) }.show()
        }.apply { isEnabled = false }; box.addView(install)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progressTintList = ColorStateList.valueOf(GREEN)
            contentDescription = "Firmware transfer progress"
        }; box.addView(progress, LinearLayout.LayoutParams(-1, dp(16)))
        otaStatus = label("Connect to check OTA support").apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        box.addView(otaStatus)
        val help = card(parent); help.addView(heading("Before updating"))
        help.addView(label("The matching OTA bootloader must be installed over USB once. Only validated BRD4184A A02 application packages are accepted. A transfer reaches success only when the board reconnects with the selected firmware version."))
    }
    private fun buildSettings(parent: LinearLayout) {
        val identity = card(parent); identity.addView(heading("Device name"))
        identity.addView(label("A label stored on this phone for the connected board."))
        val name = EditText(this).apply { hint = "For example, Living room"; isSingleLine = true; maxLines = 1 }
        identity.addView(name)
        identity.addView(button("Save label") {
            if (!ready) { Toast.makeText(this, "Connect to a board first", Toast.LENGTH_SHORT).show(); return@button }
            val value = name.text.toString().trim().take(60)
            preferences.edit().putString("name:$address", value).apply(); renderDevice()
            Toast.makeText(this, "Device label saved", Toast.LENGTH_SHORT).show()
        })
        val reporting = card(parent); reporting.addView(heading("Sensor reporting"))
        periodStatus = label("Connect to read the board's period"); reporting.addView(periodStatus)
        periodInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER; isSingleLine = true; setText("2")
            hint = "Seconds (1–3600)"; contentDescription = "Reporting period in seconds"; isEnabled = false
        }; reporting.addView(periodInput)
        applyPeriod = button("Apply period") {
            val seconds = periodInput.text.toString().trim().toIntOrNull()
            if (seconds == null || seconds !in 1..3600) periodInput.error = "Enter a whole number from 1 to 3600"
            else { periodInput.error = null; ble.setReportingPeriod(seconds) }
        }.apply { isEnabled = false }; reporting.addView(applyPeriod)
        reporting.addView(label("Applies to both sensors. A board restart restores the 2-second default. The accepted value is read back from the board."))
        val power = card(parent); power.addView(heading("Power management"))
        val keepAwake = Switch(this).apply {
            text = "Keep screen on while connected"; textSize = 14f
            isChecked = preferences.getBoolean("awake", false)
            setOnCheckedChangeListener { _, checked -> preferences.edit().putBoolean("awake", checked).apply(); updateButtons() }
        }; power.addView(keepAwake)
        power.addView(button("Sleep device") {}.apply { isEnabled = false })
        power.addView(button("Reboot device") {}.apply { isEnabled = false })
        power.addView(label("Sleep and reboot need commands in the board firmware. They are unavailable with the current firmware. You can restart the board with its physical reset button."))
    }
    private fun buildAbout(parent: LinearLayout) {
        val about = card(parent); about.addView(heading("James's Thunderboard"))
        about.addView(label("App ${BuildConfig.VERSION_NAME}\nJames's Thunderboard BG22 · BRD4184A A02\nEFR32BG22C224F512IM40\nSi7021 temperature and humidity sensor"))
        about.addView(label("Designed for Android 12 and later, including Pixel 7. Readings come directly from your board over Bluetooth LE. No account or internet access is required."))
        val support = card(parent); support.addView(heading("Firmware capabilities"))
        support.addView(label("Live temperature and humidity: supported\nReporting period: firmware 4 or newer\nFirmware updates: experimental OTA\nBattery: requires Battery Service support\nSleep and reboot: require firmware commands"))
        support.addView(label("History is collected while this app is running and clears when switching boards or closing the app. Monitoring runs in the foreground; background collection is not provided. Disconnect other Bluetooth clients before connecting."))
    }
    private fun hasPermissions() = checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
        checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    private fun scanPressed() {
        if (scanning) { ble.stopScan(); return }
        if (!hasPermissions()) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT), PERMISSIONS); return
        }
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
        if (!ble.bluetoothEnabled()) { startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), ENABLE_BT); return }
        devices.removeAllViews(); deviceButtons.clear()
        try { ble.scan() } catch (e: RuntimeException) { message("Could not start Bluetooth: ${e.message}") }
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSIONS) {
            if (hasPermissions()) scanPressed()
            else message("Allow Nearby Devices in Android app settings to scan and connect.")
        }
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == ENABLE_BT && resultCode == RESULT_OK) scanPressed()
        if (requestCode != PICK_FILE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        loadingFile = true; selected = null; updateButtons(); packageInfo.text = "Checking update package…"
        files.execute {
            try {
                val parsed = contentResolver.openInputStream(uri).use { OtaImage.read(it) }
                main.post {
                    if (!destroyed) {
                        loadingFile = false; selected = parsed
                        packageInfo.text = "Firmware ${parsed.version} · ${String.format(Locale.getDefault(), "%.1f KiB", parsed.bytes.size / 1024.0)}\nBRD4184A A02 · checksum verified"
                        updateButtons()
                    }
                }
            } catch (e: Exception) {
                main.post { if (!destroyed) { loadingFile = false; packageInfo.text = "Cannot use this file: ${e.message}"; updateButtons() } }
            }
        }
    }
    private fun updateButtons() {
        val busy = ::ble.isInitialized && ble.isUpdating
        scan.isEnabled = !busy; disconnect.isEnabled = ready && !busy; refresh.isEnabled = ready && !busy
        periodInput.isEnabled = ready && periodAvailable && !busy; applyPeriod.isEnabled = periodInput.isEnabled
        choose.isEnabled = !busy && !loadingFile
        install.isEnabled = ready && otaAvailable && selected != null && !busy && !loadingFile
        deviceButtons.values.forEach { it.isEnabled = !busy }
        if (busy || (ready && preferences.getBoolean("awake", false))) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    private fun renderDevice() {
        deviceName.text = preferences.getString("name:$address", "").orEmpty().ifBlank { advertisedName }
        deviceState.text = if (ready) "● Connected" + (signal?.let { " · $it dBm at discovery" } ?: "") else "○ Disconnected"
    }
    override fun message(text: String) { status.text = text }
    override fun scanning(active: Boolean) {
        scanning = active; scan.text = if (active) "Stop scanning" else "Scan for sensors"
        if (!active && deviceButtons.isEmpty()) message("No sensors found. Check power and disconnect other BLE clients.")
    }
    @android.annotation.SuppressLint("MissingPermission")
    override fun device(device: BluetoothDevice, name: String, rssi: Int) {
        val key = device.address
        val item = deviceButtons.getOrPut(key) { button("") {}.also { devices.addView(it) } }
        item.text = "$name\n$key · $rssi dBm"
        item.contentDescription = "Connect to $name, signal $rssi dBm"
        item.isEnabled = !ble.isUpdating
        item.setOnClickListener {
            if (!hasPermissions()) { message("Grant Nearby Devices permission and scan again."); return@setOnClickListener }
            if (address != key) {
                chart.clear(); temperature.text = "— °C"; humidity.text = "— %"; temperatureTime = 0; humidityTime = 0
            }
            address = key; advertisedName = name; signal = rssi
            ble.connect(device); devices.removeAllViews(); deviceButtons.clear(); renderDevice()
        }
    }
    override fun connection(connected: Boolean, supportsOta: Boolean, version: String) {
        ready = connected; otaAvailable = supportsOta; renderDevice()
        if (!connected) chart.breakLine()
        if (!connected) battery.text = "Board battery · disconnected"
        if (connected && !periodAvailable) periodStatus.text = "Period changes need firmware 4 or newer"
        firmware.text = if (connected) "Installed version · $version" else "Board disconnected"
        if (!ble.isUpdating) otaStatus.text = if (!connected) "Connect to check OTA support"
            else if (supportsOta) "Experimental OTA available. Select a .bg22ota package."
            else "Install the OTA bootloader and application over USB to enable updates."
        updateButtons()
    }
    override fun battery(percent: Int) {
        battery.text = if (percent in 0..100) "Board battery · $percent%" else "Board battery · unavailable in this firmware"
    }
    override fun reading(isTemperature: Boolean, value: Double) {
        val now = SystemClock.elapsedRealtime()
        if (isTemperature) {
            temperature.text = String.format(Locale.getDefault(), "%.1f °C", value); temperatureTime = now
            chart.add(now, value, SensorCodec.staleAfterMillis(periodSeconds))
        } else { humidity.text = String.format(Locale.getDefault(), "%.1f %%", value); humidityTime = now }
    }
    override fun reportingPeriod(available: Boolean, seconds: Int) {
        periodAvailable = available; periodSeconds = seconds
        periodInput.setText(seconds.toString()); periodInput.error = null
        periodStatus.text = if (available) "Board reports every $seconds seconds"
            else if (ready) "Period changes need firmware 4 or newer" else "Connect to read the board's period"
        updateButtons()
    }
    override fun otaProgress(active: Boolean, percent: Int, text: String) {
        progress.progress = percent; otaStatus.text = text; updateButtons()
    }
    private val tick = object : Runnable {
        override fun run() {
            if (destroyed) return
            updated.text = when {
                !ready -> if (temperatureTime > 0) "Disconnected · last reading" else "No readings yet"
                temperatureTime == 0L || humidityTime == 0L -> "Waiting for sensors…"
                else -> {
                    val age = (SystemClock.elapsedRealtime() - minOf(temperatureTime, humidityTime)) / 1000
                    if (age * 1000 > SensorCodec.staleAfterMillis(periodSeconds)) "Stale · ${age}s ago"
                    else if (age < 2) "Updated just now" else "Updated ${age}s ago"
                }
            }
            chart.invalidate(); main.postDelayed(this, 1000)
        }
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putInt("tab", activeTab); super.onSaveInstanceState(outState) }
    override fun onBackPressed() {
        if (ble.isUpdating) AlertDialog.Builder(this).setTitle("Leave firmware update?")
            .setMessage("Leaving stops the connection. A completed transfer may already be installing.")
            .setNegativeButton("Stay", null).setPositiveButton("Leave") { _, _ -> finish() }.show()
        else if (activeTab != 0) selectTab(0) else super.onBackPressed()
    }
    override fun onStop() { super.onStop(); if (::ble.isInitialized && hasPermissions()) ble.stopScan() }
    override fun onDestroy() {
        destroyed = true; main.removeCallbacksAndMessages(null); files.shutdownNow()
        if (::ble.isInitialized && hasPermissions()) ble.close()
        super.onDestroy()
    }
    companion object {
        private const val PERMISSIONS = 10
        private const val ENABLE_BT = 11
        private const val PICK_FILE = 12
        private val BACKGROUND = Color.rgb(245, 248, 243)
        private val TEXT = Color.rgb(25, 46, 35)
        private val MUTED = Color.rgb(94, 112, 100)
        private val GREEN = Color.rgb(36, 107, 72)
        private val TINT = Color.rgb(220, 239, 222)
    }
}
