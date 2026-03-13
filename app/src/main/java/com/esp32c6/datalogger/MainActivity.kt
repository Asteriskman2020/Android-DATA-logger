package com.esp32c6.datalogger

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator

class MainActivity : AppCompatActivity() {

    val bleManager: BleManager by lazy { BleManager(this) }
    val mqttManager: MqttManager = MqttManager()

    private lateinit var viewPager: ViewPager2
    private lateinit var tabLayout: TabLayout

    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) {
            Toast.makeText(this, "Bluetooth is required for BLE scanning", Toast.LENGTH_LONG).show()
        }
    }

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (!allGranted) {
            Toast.makeText(
                this,
                "BLE permissions are required for device scanning",
                Toast.LENGTH_LONG
            ).show()
        } else {
            checkBluetoothEnabled()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar: Toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = "ESP32-C6 Data Logger"

        viewPager = findViewById(R.id.viewPager)
        tabLayout = findViewById(R.id.tabLayout)

        val pagerAdapter = ViewPagerAdapter(this)
        viewPager.adapter = pagerAdapter

        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> "DATA"
                1 -> "SETTINGS"
                else -> ""
            }
        }.attach()

        // Load and apply MQTT settings
        loadAndApplyMqttSettings()

        // Request permissions
        requestBlePermissions()
    }

    private inner class ViewPagerAdapter(fa: FragmentActivity) : FragmentStateAdapter(fa) {
        override fun getItemCount(): Int = 2
        override fun createFragment(position: Int): Fragment {
            return when (position) {
                0 -> DataFragment()
                1 -> SettingsFragment()
                else -> DataFragment()
            }
        }
    }

    private fun requestBlePermissions() {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }

        if (permissions.isNotEmpty()) {
            requestPermissionsLauncher.launch(permissions.toTypedArray())
        } else {
            checkBluetoothEnabled()
        }
    }

    private fun checkBluetoothEnabled() {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter

        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth is not supported on this device", Toast.LENGTH_LONG).show()
            return
        }

        if (!bluetoothAdapter.isEnabled) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            enableBluetoothLauncher.launch(enableBtIntent)
        }
    }

    fun loadAndApplyMqttSettings() {
        val prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        mqttManager.isEnabled = prefs.getBoolean(KEY_MQTT_ENABLED, false)
        mqttManager.config = MqttManager.Config(
            brokerIp = prefs.getString(KEY_MQTT_IP, "") ?: "",
            port = prefs.getString(KEY_MQTT_PORT, "1883")?.toIntOrNull() ?: 1883,
            username = prefs.getString(KEY_MQTT_USER, "") ?: "",
            password = prefs.getString(KEY_MQTT_PASS, "") ?: "",
            topic = prefs.getString(KEY_MQTT_TOPIC, "esp32c6/sensor") ?: "esp32c6/sensor"
        )
    }

    fun getEsp32Ip(): String {
        val prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_ESP32_IP, "192.168.4.1") ?: "192.168.4.1"
    }

    fun isAutoPublish(): Boolean {
        val prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_AUTO_PUBLISH, false)
    }

    override fun onDestroy() {
        super.onDestroy()
        bleManager.close()
        mqttManager.disconnect()
    }
}
