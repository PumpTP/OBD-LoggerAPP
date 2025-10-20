package com.pump.obdlogger

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

data class MetricRow(val label: String, var value: String = "--")

class RealtimeAdapter(private val items: MutableList<MetricRow>)
    : RecyclerView.Adapter<RealtimeAdapter.VH>() {

    class VH(v: View): RecyclerView.ViewHolder(v) {
        val label: TextView = v.findViewById(R.id.tvLabel)
        val value: TextView = v.findViewById(R.id.tvValue)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_metric, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.label.text = item.label
        holder.value.text = item.value
    }

    override fun getItemCount() = items.size

    fun updateValue(index: Int, newValue: String) {
        items[index].value = newValue
        notifyItemChanged(index)
    }
}
