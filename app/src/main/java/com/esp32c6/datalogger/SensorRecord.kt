package com.esp32c6.datalogger

import java.text.SimpleDateFormat
import java.util.*

data class SensorRecord(
    val index: Int,
    val tempAht: Float,
    val humidity: Float,
    val pressure: Float,
    val tempBmp: Float,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toJsonString(): String =
        """{"index":$index,"temp_aht":${"%.1f".format(tempAht)},"humidity":${"%.1f".format(humidity)},"pressure":${"%.1f".format(pressure)},"temp_bmp":${"%.1f".format(tempBmp)},"ts":$timestamp}"""

    fun toDisplayTime(): String =
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
}
