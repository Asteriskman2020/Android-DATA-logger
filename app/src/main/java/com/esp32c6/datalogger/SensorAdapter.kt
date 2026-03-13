package com.esp32c6.datalogger

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class SensorAdapter(
    private var records: MutableList<SensorRecord> = mutableListOf(),
    private val onItemClick: ((SensorRecord) -> Unit)? = null
) : RecyclerView.Adapter<SensorAdapter.ViewHolder>() {

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvIndex: TextView = view.findViewById(R.id.tvIndex)
        val tvTime: TextView = view.findViewById(R.id.tvTime)
        val tvTempAht: TextView = view.findViewById(R.id.tvTempAht)
        val tvHumidity: TextView = view.findViewById(R.id.tvHumidity)
        val tvPressure: TextView = view.findViewById(R.id.tvPressure)
        val tvTempBmp: TextView = view.findViewById(R.id.tvTempBmp)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_sensor, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val record = records[position]
        holder.tvIndex.text = record.index.toString()
        holder.tvTime.text = record.toDisplayTime()
        holder.tvTempAht.text = "%.1f".format(record.tempAht)
        holder.tvHumidity.text = "%.1f".format(record.humidity)
        holder.tvPressure.text = "%.1f".format(record.pressure)
        holder.tvTempBmp.text = "%.1f".format(record.tempBmp)

        // Alternating row colors: white for even, light yellow for odd
        if (position % 2 == 0) {
            holder.itemView.setBackgroundColor(Color.WHITE)
        } else {
            holder.itemView.setBackgroundColor(Color.parseColor("#FFF9C4"))
        }

        onItemClick?.let { click ->
            holder.itemView.setOnClickListener { click(record) }
        }
    }

    override fun getItemCount(): Int = records.size

    fun updateData(newList: List<SensorRecord>) {
        records.clear()
        records.addAll(newList)
        notifyDataSetChanged()
    }

    fun addRecord(record: SensorRecord) {
        records.add(record)
        notifyItemInserted(records.size - 1)
    }

    fun clear() {
        records.clear()
        notifyDataSetChanged()
    }
}
