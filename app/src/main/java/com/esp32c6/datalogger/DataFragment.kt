package com.esp32c6.datalogger

import android.app.AlertDialog
import android.bluetooth.BluetoothDevice
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class DataFragment : Fragment(), BleManager.BleCallback {

    private lateinit var tvStatus: TextView
    private lateinit var tvDeviceName: TextView
    private lateinit var dotView: View
    private lateinit var btnScan: Button
    private lateinit var btnDisconnect: Button

    private lateinit var tvTempAht: TextView
    private lateinit var tvHumidity: TextView
    private lateinit var tvPressure: TextView
    private lateinit var tvTempBmp: TextView
    private lateinit var progressBuffer: ProgressBar
    private lateinit var tvBufferCount: TextView

    private lateinit var btnFetchAll: Button
    private lateinit var btnClearMemory: Button
    private lateinit var btnPublishMqtt: Button
    private lateinit var tvMqttStatus: TextView

    private lateinit var tvDataCount: TextView
    private lateinit var rvData: RecyclerView

    private lateinit var tvLog: TextView
    private lateinit var scrollLog: ScrollView
    private lateinit var btnClearLog: Button

    private lateinit var sensorAdapter: SensorAdapter
    private val sensorRecords = mutableListOf<SensorRecord>()

    private val foundDevices = mutableMapOf<String, BluetoothDevice>()
    private val foundDeviceRssi = mutableMapOf<String, Int>()
    private var scanDialog: AlertDialog? = null
    private val scanHandler = Handler(Looper.getMainLooper())
    private var scanRunnable: Runnable? = null

    private val logBuilder = StringBuilder()
    private val pendingRecords = mutableListOf<SensorRecord>()  // collecting READBUF stream
    private var isFetching = false
    private val fetchTimeoutHandler = Handler(Looper.getMainLooper())

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_data, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Connection card
        tvStatus = view.findViewById(R.id.tvConnectionStatus)
        tvDeviceName = view.findViewById(R.id.tvDeviceName)
        dotView = view.findViewById(R.id.viewDot)
        btnScan = view.findViewById(R.id.btnScan)
        btnDisconnect = view.findViewById(R.id.btnDisconnect)

        // Live sensor card
        tvTempAht = view.findViewById(R.id.tvTempAhtValue)
        tvHumidity = view.findViewById(R.id.tvHumidityValue)
        tvPressure = view.findViewById(R.id.tvPressureValue)
        tvTempBmp = view.findViewById(R.id.tvTempBmpValue)
        progressBuffer = view.findViewById(R.id.progressBuffer)
        tvBufferCount = view.findViewById(R.id.tvBufferCount)

        // Actions card
        btnFetchAll = view.findViewById(R.id.btnFetchAll)
        btnClearMemory = view.findViewById(R.id.btnClearMemory)
        btnPublishMqtt = view.findViewById(R.id.btnPublishMqtt)
        tvMqttStatus = view.findViewById(R.id.tvMqttStatus)

        // Data history card
        tvDataCount = view.findViewById(R.id.tvDataCount)
        rvData = view.findViewById(R.id.rvData)

        // Log card
        tvLog = view.findViewById(R.id.tvLog)
        scrollLog = view.findViewById(R.id.scrollLog)
        btnClearLog = view.findViewById(R.id.btnClearLog)

        // Setup RecyclerView
        sensorAdapter = SensorAdapter(sensorRecords)
        rvData.layoutManager = LinearLayoutManager(requireContext())
        rvData.adapter = sensorAdapter
        rvData.isNestedScrollingEnabled = false

        // Initial state
        setDisconnectedState()

        // Button listeners
        btnScan.setOnClickListener { startScan() }

        btnDisconnect.setOnClickListener {
            (activity as MainActivity).bleManager.disconnect()
            setDisconnectedState()
            addLog("Disconnect requested")
        }

        btnFetchAll.setOnClickListener { fetchAllData() }

        btnClearMemory.setOnClickListener {
            (activity as MainActivity).bleManager.sendCommand("CLRBUF")
            sensorRecords.clear()
            sensorAdapter.updateData(sensorRecords)
            tvDataCount.text = "0 records"
            addLog("Memory cleared (CLRBUF sent)")
        }

        btnPublishMqtt.setOnClickListener { publishAllToMqtt() }

        btnClearLog.setOnClickListener {
            logBuilder.clear()
            tvLog.text = ""
        }

        // Register callback
        (activity as MainActivity).bleManager.callback = this

        // Update MQTT status
        updateMqttStatus()
    }

    override fun onResume() {
        super.onResume()
        (activity as MainActivity).bleManager.callback = this
        updateMqttStatus()
    }

    private fun setDisconnectedState() {
        dotView.setBackgroundResource(R.drawable.dot_disconnected)
        tvDeviceName.text = "Not Connected"
        tvStatus.text = "Disconnected"
        btnDisconnect.isEnabled = false
    }

    private fun setConnectedState(name: String) {
        dotView.setBackgroundResource(R.drawable.dot_connected)
        tvDeviceName.text = name
        tvStatus.text = "Connected"
        btnDisconnect.isEnabled = true
    }

    private fun setScanningState() {
        dotView.setBackgroundResource(R.drawable.dot_scanning)
        tvDeviceName.text = "Scanning..."
        tvStatus.text = "Scanning"
    }

    private fun updateMqttStatus() {
        val ma = activity as? MainActivity ?: return
        val connected = ma.mqttManager.isConnected()
        val enabled = ma.mqttManager.isEnabled
        tvMqttStatus.text = when {
            !enabled -> "MQTT: Disabled"
            connected -> "MQTT: Connected"
            else -> "MQTT: Disconnected"
        }
        tvMqttStatus.setTextColor(
            when {
                !enabled -> Color.parseColor("#757575")
                connected -> Color.parseColor("#4CAF50")
                else -> Color.parseColor("#F44336")
            }
        )
    }

    private fun startScan() {
        foundDevices.clear()
        foundDeviceRssi.clear()
        setScanningState()
        addLog("Starting BLE scan...")

        val ma = activity as MainActivity
        ma.bleManager.startScan()

        // Show dialog after 3 seconds with found devices
        scanRunnable = Runnable {
            ma.bleManager.stopScan()
            showDevicePickerDialog()
        }
        scanHandler.postDelayed(scanRunnable!!, 3000)
    }

    private fun showDevicePickerDialog() {
        if (!isAdded) return

        if (foundDevices.isEmpty()) {
            addLog("No devices found")
            setDisconnectedState()
            AlertDialog.Builder(requireContext())
                .setTitle("No Devices Found")
                .setMessage("No BLE devices were found nearby.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val deviceList = foundDevices.entries.toList()
        val items = deviceList.map { (addr, dev) ->
            "${dev.name ?: "Unknown"} [$addr] RSSI: ${foundDeviceRssi[addr] ?: 0}dBm"
        }.toTypedArray()

        scanDialog = AlertDialog.Builder(requireContext())
            .setTitle("Select Device")
            .setItems(items) { _, which ->
                val device = deviceList[which].value
                addLog("Connecting to ${device.name}...")
                (activity as MainActivity).bleManager.connect(device)
            }
            .setNegativeButton("Cancel") { _, _ ->
                setDisconnectedState()
                addLog("Scan cancelled")
            }
            .show()
    }

    private fun fetchAllData() {
        val ma = activity as MainActivity
        if (!ma.bleManager.isConnected()) {
            showToast("Not connected to device")
            return
        }
        if (isFetching) {
            showToast("Fetch already in progress")
            return
        }
        isFetching = true
        pendingRecords.clear()
        btnFetchAll.isEnabled = false
        btnFetchAll.text = "Fetching..."
        addLog("Sending READBUF — waiting for buffer stream...")
        ma.bleManager.sendCommand("READBUF")

        // Timeout: re-enable button if END:N never arrives within 15 seconds
        fetchTimeoutHandler.postDelayed({
            if (isFetching) {
                isFetching = false
                btnFetchAll.isEnabled = true
                btnFetchAll.text = "FETCH VIA BLE"
                addLog("Fetch timeout — collected ${pendingRecords.size} record(s) so far, no END received")
                showToast("Fetch timed out")
            }
        }, 15000)
    }

    private fun publishAllToMqtt() {
        val ma = activity as MainActivity
        if (!ma.mqttManager.isEnabled) {
            showToast("MQTT is disabled. Enable in Settings.")
            return
        }
        if (sensorRecords.isEmpty()) {
            showToast("No records to publish")
            return
        }

        btnPublishMqtt.isEnabled = false
        addLog("Publishing ${sensorRecords.size} records to MQTT...")

        val recordsCopy = sensorRecords.toList()
        ma.mqttManager.publishAll(
            recordsCopy,
            onProgress = { current, total ->
                requireActivity().runOnUiThread {
                    btnPublishMqtt.text = "Publishing $current/$total"
                }
            },
            onDone = { count ->
                requireActivity().runOnUiThread {
                    btnPublishMqtt.text = "PUBLISH TO MQTT"
                    btnPublishMqtt.isEnabled = true
                    addLog("Published $count/${recordsCopy.size} records")
                    showToast("Published $count records")
                    updateMqttStatus()
                }
            }
        )
    }

    fun addLog(msg: String) {
        val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        logBuilder.append("[$ts] $msg\n")
        tvLog.text = logBuilder.toString()
        scrollLog.post { scrollLog.fullScroll(View.FOCUS_DOWN) }
    }

    private fun showToast(msg: String) {
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }

    // BleCallback implementations
    override fun onConnected(name: String) {
        setConnectedState(name)
        addLog("Connected to $name")
    }

    override fun onDisconnected() {
        setDisconnectedState()
        addLog("Disconnected from device")
    }

    override fun onScanResult(device: BluetoothDevice, rssi: Int) {
        val addr = device.address
        if (!foundDevices.containsKey(addr)) {
            foundDevices[addr] = device
            addLog("Found: ${device.name ?: "Unknown"} [$addr] $rssi dBm")
        }
        foundDeviceRssi[addr] = rssi
    }

    override fun onScanStopped() {
        addLog("Scan stopped, ${foundDevices.size} devices found")
    }

    override fun onSensorUpdate(tempAht: Float, humidity: Float, pressure: Float, tempBmp: Float) {
        tvTempAht.text = "%.1f °C".format(tempAht)
        tvHumidity.text = "%.1f %%".format(humidity)
        tvPressure.text = "%.1f hPa".format(pressure)
        tvTempBmp.text = "%.1f °C".format(tempBmp)

        // Auto-publish if enabled
        if ((activity as MainActivity).isAutoPublish()) {
            val record = SensorRecord(
                sensorRecords.size + 1,
                tempAht, humidity, pressure, tempBmp
            )
            (activity as MainActivity).mqttManager.publish(record.toJsonString())
        }

        // Request count update
        (activity as MainActivity).bleManager.requestCountUpdate()
    }

    override fun onStatusUpdate(status: String) {
        addLog("Status: $status")
    }

    override fun onCountUpdate(count: Int) {
        tvBufferCount.text = count.toString()
        progressBuffer.progress = count
    }

    override fun onBufferRecord(record: SensorRecord) {
        pendingRecords.add(record)
        btnFetchAll.text = "Fetching ${pendingRecords.size}..."
    }

    override fun onBufferComplete(total: Int) {
        fetchTimeoutHandler.removeCallbacksAndMessages(null)
        isFetching = false
        addLog("END received: firmware total=$total, pending=${pendingRecords.size}")
        val snapshot = pendingRecords.toList()
        pendingRecords.clear()
        sensorAdapter.updateData(snapshot)
        tvDataCount.text = "${sensorRecords.size} records"
        btnFetchAll.isEnabled = true
        btnFetchAll.text = "FETCH VIA BLE"
        addLog("Fetch done: ${sensorRecords.size} row(s) in table")
    }

    override fun onLog(msg: String) {
        addLog(msg)
    }
}
