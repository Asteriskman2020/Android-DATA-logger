package com.esp32c6.datalogger

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.Fragment

class SettingsFragment : Fragment() {

    private lateinit var switchMqttEnabled: SwitchCompat
    private lateinit var etBrokerIp: EditText
    private lateinit var etBrokerPort: EditText
    private lateinit var etUsername: EditText
    private lateinit var etPassword: EditText
    private lateinit var etTopic: EditText
    private lateinit var btnTestConnection: Button

    private lateinit var etEsp32Ip: EditText
    private lateinit var switchAutoPublish: SwitchCompat

    private lateinit var btnSaveSettings: Button

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        switchMqttEnabled = view.findViewById(R.id.switchMqttEnabled)
        etBrokerIp = view.findViewById(R.id.etBrokerIp)
        etBrokerPort = view.findViewById(R.id.etBrokerPort)
        etUsername = view.findViewById(R.id.etUsername)
        etPassword = view.findViewById(R.id.etPassword)
        etTopic = view.findViewById(R.id.etTopic)
        btnTestConnection = view.findViewById(R.id.btnTestConnection)

        etEsp32Ip = view.findViewById(R.id.etEsp32Ip)
        switchAutoPublish = view.findViewById(R.id.switchAutoPublish)

        btnSaveSettings = view.findViewById(R.id.btnSaveSettings)

        loadSettings()

        btnSaveSettings.setOnClickListener {
            saveSettings()
            Toast.makeText(requireContext(), "Settings saved", Toast.LENGTH_SHORT).show()
        }

        btnTestConnection.setOnClickListener {
            saveSettings()
            val ma = activity as MainActivity
            btnTestConnection.isEnabled = false
            btnTestConnection.text = "Testing..."
            ma.mqttManager.connect { success, msg ->
                requireActivity().runOnUiThread {
                    btnTestConnection.isEnabled = true
                    btnTestConnection.text = "TEST CONNECTION"
                    val icon = if (success) "✓" else "✗"
                    Toast.makeText(context, "$icon $msg", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        loadSettings()
    }

    private fun loadSettings() {
        val prefs = requireActivity().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        switchMqttEnabled.isChecked = prefs.getBoolean(KEY_MQTT_ENABLED, false)
        etBrokerIp.setText(prefs.getString(KEY_MQTT_IP, ""))
        etBrokerPort.setText(prefs.getString(KEY_MQTT_PORT, "1883"))
        etUsername.setText(prefs.getString(KEY_MQTT_USER, ""))
        etPassword.setText(prefs.getString(KEY_MQTT_PASS, ""))
        etTopic.setText(prefs.getString(KEY_MQTT_TOPIC, "esp32c6/sensor"))
        etEsp32Ip.setText(prefs.getString(KEY_ESP32_IP, "192.168.4.1"))
        switchAutoPublish.isChecked = prefs.getBoolean(KEY_AUTO_PUBLISH, false)
    }

    fun saveSettings() {
        val prefs = requireActivity().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putBoolean(KEY_MQTT_ENABLED, switchMqttEnabled.isChecked)
            putString(KEY_MQTT_IP, etBrokerIp.text.toString().trim())
            putString(KEY_MQTT_PORT, etBrokerPort.text.toString().trim().ifBlank { "1883" })
            putString(KEY_MQTT_USER, etUsername.text.toString().trim())
            putString(KEY_MQTT_PASS, etPassword.text.toString())
            putString(KEY_MQTT_TOPIC, etTopic.text.toString().trim().ifBlank { "esp32c6/sensor" })
            putString(KEY_ESP32_IP, etEsp32Ip.text.toString().trim())
            putBoolean(KEY_AUTO_PUBLISH, switchAutoPublish.isChecked)
            apply()
        }

        // Apply settings to MqttManager immediately
        val ma = activity as MainActivity
        ma.mqttManager.isEnabled = switchMqttEnabled.isChecked
        ma.mqttManager.config = MqttManager.Config(
            brokerIp = etBrokerIp.text.toString().trim(),
            port = etBrokerPort.text.toString().trim().toIntOrNull() ?: 1883,
            username = etUsername.text.toString().trim(),
            password = etPassword.text.toString(),
            topic = etTopic.text.toString().trim().ifBlank { "esp32c6/sensor" }
        )
    }
}
