package com.esp32c6.datalogger

import android.util.Log
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.util.concurrent.Executors

class MqttManager {
    companion object {
        private const val TAG = "MqttManager"
    }

    private var client: MqttClient? = null
    private val executor = Executors.newSingleThreadExecutor()

    data class Config(
        val brokerIp: String,
        val port: Int = 1883,
        val username: String = "",
        val password: String = "",
        val topic: String = "esp32c6/sensor"
    )

    var config: Config = Config("")
    var isEnabled: Boolean = false

    fun connect(onResult: (Boolean, String) -> Unit) {
        if (config.brokerIp.isBlank()) {
            onResult(false, "Broker IP not set")
            return
        }
        executor.execute {
            try {
                disconnect()
                val brokerUri = "tcp://${config.brokerIp}:${config.port}"
                val opts = MqttConnectOptions().apply {
                    isCleanSession = true
                    connectionTimeout = 10
                    keepAliveInterval = 30
                    if (config.username.isNotBlank()) {
                        userName = config.username
                        if (config.password.isNotBlank()) password = config.password.toCharArray()
                    }
                }
                client = MqttClient(
                    brokerUri,
                    "AndroidDataLogger-${System.currentTimeMillis()}",
                    MemoryPersistence()
                )
                client!!.connect(opts)
                Log.d(TAG, "Connected to $brokerUri")
                onResult(true, "Connected to ${config.brokerIp}:${config.port}")
            } catch (e: Exception) {
                Log.e(TAG, "Connect failed", e)
                onResult(false, e.message ?: "Connection failed")
            }
        }
    }

    fun publish(payload: String, onResult: ((Boolean, String) -> Unit)? = null) {
        if (!isEnabled) return
        executor.execute {
            try {
                if (client?.isConnected != true) {
                    client?.reconnect()
                }
                val msg = MqttMessage(payload.toByteArray()).apply { qos = 1 }
                client?.publish(config.topic, msg)
                onResult?.invoke(true, "Published")
            } catch (e: Exception) {
                Log.e(TAG, "Publish failed", e)
                onResult?.invoke(false, e.message ?: "Publish failed")
            }
        }
    }

    fun publishAll(
        records: List<SensorRecord>,
        onProgress: (Int, Int) -> Unit,
        onDone: (Int) -> Unit
    ) {
        if (!isEnabled) {
            onDone(0)
            return
        }
        executor.execute {
            var published = 0
            records.forEachIndexed { i, record ->
                try {
                    if (client?.isConnected != true) client?.reconnect()
                    val msg = MqttMessage(record.toJsonString().toByteArray()).apply { qos = 1 }
                    client?.publish(config.topic, msg)
                    published++
                    onProgress(i + 1, records.size)
                } catch (e: Exception) {
                    Log.e(TAG, "Publish record $i failed", e)
                }
                Thread.sleep(20)
            }
            onDone(published)
        }
    }

    fun disconnect() {
        try {
            if (client?.isConnected == true) client?.disconnect()
            client?.close()
        } catch (_: Exception) {}
        client = null
    }

    fun isConnected(): Boolean = client?.isConnected == true
}
