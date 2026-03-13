package com.esp32c6.datalogger

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.*
import java.util.concurrent.LinkedBlockingQueue

@SuppressLint("MissingPermission")
class BleManager(private val context: Context) {

    companion object {
        private const val TAG = "BleManager"
        val SERVICE_UUID: UUID = UUID.fromString("ab000001-0000-1000-8000-00805f9b34fb")
        val CHAR_MESSAGE_UUID: UUID = UUID.fromString("ab000002-0000-1000-8000-00805f9b34fb")
        val CHAR_COMMAND_UUID: UUID = UUID.fromString("ab000003-0000-1000-8000-00805f9b34fb")
        val CHAR_STATUS_UUID: UUID = UUID.fromString("ab000004-0000-1000-8000-00805f9b34fb")
        val CHAR_SENSOR_UUID: UUID = UUID.fromString("ab000005-0000-1000-8000-00805f9b34fb")
        val CHAR_COUNT_UUID: UUID = UUID.fromString("ab000006-0000-1000-8000-00805f9b34fb")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val GATT_DELAY_MS = 300L
    }

    interface BleCallback {
        fun onConnected(name: String)
        fun onDisconnected()
        fun onScanResult(device: BluetoothDevice, rssi: Int)
        fun onScanStopped()
        fun onSensorUpdate(tempAht: Float, humidity: Float, pressure: Float, tempBmp: Float)
        fun onStatusUpdate(status: String)
        fun onCountUpdate(count: Int)
        fun onLog(msg: String)
    }

    var callback: BleCallback? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? get() = bluetoothManager.adapter
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var isScanning = false

    private val operationQueue = LinkedBlockingQueue<Runnable>()
    private var isExecutingOperation = false
    private val queueHandler = Handler(Looper.getMainLooper())

    private fun enqueueOperation(op: Runnable) {
        operationQueue.add(op)
        if (!isExecutingOperation) processNextOperation()
    }

    private fun processNextOperation() {
        val op = operationQueue.poll()
        if (op == null) {
            isExecutingOperation = false
            return
        }
        isExecutingOperation = true
        op.run()
    }

    private fun operationComplete() {
        queueHandler.postDelayed({
            processNextOperation()
        }, GATT_DELAY_MS)
    }

    // Scan callback
    private val leScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = device.name ?: return
            if (name.isNotBlank()) {
                mainHandler.post {
                    callback?.onScanResult(device, result.rssi)
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed: $errorCode")
            mainHandler.post {
                callback?.onLog("Scan failed: error $errorCode")
                callback?.onScanStopped()
            }
        }
    }

    // GATT callback
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "Connected to GATT server")
                    mainHandler.post { callback?.onLog("Connected, discovering services...") }
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "Disconnected from GATT server")
                    bluetoothGatt?.close()
                    bluetoothGatt = null
                    operationQueue.clear()
                    isExecutingOperation = false
                    mainHandler.post {
                        callback?.onLog("Disconnected")
                        callback?.onDisconnected()
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Services discovered")
                val deviceName = gatt.device.name ?: "Unknown"
                mainHandler.post {
                    callback?.onConnected(deviceName)
                    callback?.onLog("Services discovered, enabling notifications...")
                }
                setupNotifications(gatt)
            } else {
                mainHandler.post { callback?.onLog("Service discovery failed: $status") }
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val value = characteristic.value
                handleCharacteristicValue(characteristic.uuid, value)
            }
            operationComplete()
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    handleCharacteristicValue(characteristic.uuid, value)
                }
                operationComplete()
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                val value = characteristic.value
                handleCharacteristicValue(characteristic.uuid, value)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                handleCharacteristicValue(characteristic.uuid, value)
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            val statusStr = if (status == BluetoothGatt.GATT_SUCCESS) "OK" else "FAIL($status)"
            mainHandler.post { callback?.onLog("Write ${characteristic.uuid} $statusStr") }
            operationComplete()
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            val statusStr = if (status == BluetoothGatt.GATT_SUCCESS) "OK" else "FAIL($status)"
            mainHandler.post { callback?.onLog("Descriptor write ${descriptor.characteristic.uuid} $statusStr") }
            operationComplete()
        }
    }

    private fun handleCharacteristicValue(uuid: UUID, value: ByteArray) {
        val strValue = String(value).trim()
        when (uuid) {
            CHAR_MESSAGE_UUID -> mainHandler.post { callback?.onLog("MSG: $strValue") }
            CHAR_STATUS_UUID -> mainHandler.post { callback?.onStatusUpdate(strValue) }
            CHAR_SENSOR_UUID -> {
                val record = parseSensorData(strValue)
                if (record != null) {
                    mainHandler.post {
                        callback?.onSensorUpdate(record.tempAht, record.humidity, record.pressure, record.tempBmp)
                    }
                }
            }
            CHAR_COUNT_UUID -> {
                val count = strValue.toIntOrNull() ?: 0
                mainHandler.post { callback?.onCountUpdate(count) }
            }
        }
    }

    private fun setupNotifications(gatt: BluetoothGatt) {
        val service = gatt.getService(SERVICE_UUID) ?: run {
            mainHandler.post { callback?.onLog("Service not found!") }
            return
        }

        // Enable notifications for ab000002, ab000004, ab000005
        listOf(CHAR_MESSAGE_UUID, CHAR_STATUS_UUID, CHAR_SENSOR_UUID).forEach { charUuid ->
            val char = service.getCharacteristic(charUuid)
            if (char != null) {
                enqueueOperation(Runnable { enableNotification(gatt, char) })
            }
        }

        // Read ab000006 (count)
        val countChar = service.getCharacteristic(CHAR_COUNT_UUID)
        if (countChar != null) {
            enqueueOperation(Runnable { readCharacteristic(gatt, countChar) })
        }
    }

    @Suppress("DEPRECATION")
    private fun enableNotification(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        gatt.setCharacteristicNotification(characteristic, true)
        val descriptor = characteristic.getDescriptor(CCCD_UUID)
        if (descriptor != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(descriptor)
            }
        } else {
            mainHandler.post { callback?.onLog("No CCCD for ${characteristic.uuid}") }
            operationComplete()
        }
    }

    private fun readCharacteristic(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        gatt.readCharacteristic(characteristic)
    }

    fun parseSensorData(data: String): SensorRecord? {
        return try {
            val parts = data.trim().split(" ")
            var tempAht = 0f
            var humidity = 0f
            var pressure = 0f
            var tempBmp = 0f
            parts.forEach { part ->
                when {
                    part.startsWith("T:") -> tempAht = part.substring(2).toFloat()
                    part.startsWith("H:") -> humidity = part.substring(2).toFloat()
                    part.startsWith("P:") -> pressure = part.substring(2).toFloat()
                    part.startsWith("Tb:") -> tempBmp = part.substring(3).toFloat()
                }
            }
            SensorRecord(0, tempAht, humidity, pressure, tempBmp)
        } catch (e: Exception) {
            Log.e(TAG, "Parse error: ${e.message}")
            null
        }
    }

    fun startScan() {
        if (isScanning) return
        bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        bluetoothLeScanner?.startScan(null, settings, leScanCallback)
        isScanning = true
        mainHandler.post { callback?.onLog("Scanning...") }
    }

    fun stopScan() {
        if (!isScanning) return
        bluetoothLeScanner?.stopScan(leScanCallback)
        isScanning = false
        mainHandler.post {
            callback?.onLog("Scan stopped")
            callback?.onScanStopped()
        }
    }

    fun connect(device: BluetoothDevice) {
        bluetoothGatt?.close()
        bluetoothGatt = null
        mainHandler.post { callback?.onLog("Connecting to ${device.name}...") }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            @Suppress("DEPRECATION")
            bluetoothGatt = device.connectGatt(context, false, gattCallback)
        }
    }

    fun disconnect() {
        bluetoothGatt?.disconnect()
    }

    fun isConnected(): Boolean {
        return bluetoothGatt != null &&
            bluetoothManager.getConnectionState(
                bluetoothGatt!!.device,
                BluetoothProfile.GATT
            ) == BluetoothProfile.STATE_CONNECTED
    }

    @Suppress("DEPRECATION")
    fun sendCommand(command: String) {
        val gatt = bluetoothGatt ?: run {
            mainHandler.post { callback?.onLog("Not connected") }
            return
        }
        val service = gatt.getService(SERVICE_UUID) ?: run {
            mainHandler.post { callback?.onLog("Service not found") }
            return
        }
        val char = service.getCharacteristic(CHAR_COMMAND_UUID) ?: run {
            mainHandler.post { callback?.onLog("Command char not found") }
            return
        }
        enqueueOperation(Runnable {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(
                    char,
                    command.toByteArray(),
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                )
            } else {
                char.value = command.toByteArray()
                char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                gatt.writeCharacteristic(char)
            }
            mainHandler.post { callback?.onLog("CMD: $command") }
        })
    }

    fun requestCountUpdate() {
        val gatt = bluetoothGatt ?: return
        val service = gatt.getService(SERVICE_UUID) ?: return
        val countChar = service.getCharacteristic(CHAR_COUNT_UUID) ?: return
        enqueueOperation(Runnable { readCharacteristic(gatt, countChar) })
    }

    fun close() {
        stopScan()
        bluetoothGatt?.close()
        bluetoothGatt = null
    }
}
