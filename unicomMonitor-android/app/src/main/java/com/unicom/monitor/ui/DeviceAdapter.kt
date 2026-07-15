package com.unicom.monitor.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.unicom.monitor.R
import com.unicom.monitor.model.Device

class DeviceAdapter(
    private val onClick: (Device) -> Unit
) : ListAdapter<Device, DeviceAdapter.DeviceViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_device, parent, false)
        return DeviceViewHolder(view)
    }

    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class DeviceViewHolder(itemView: android.view.View) :
        RecyclerView.ViewHolder(itemView) {

        private val tvName: android.widget.TextView =
            itemView.findViewById(R.id.tvDeviceName)
        private val tvInfo: android.widget.TextView =
            itemView.findViewById(R.id.tvDeviceInfo)

        init {
            itemView.setOnClickListener {
                val pos = adapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onClick(getItem(pos))
                }
            }
        }

        fun bind(device: Device) {
            tvName.text = device.name
            val info = "ID: ${device.deviceId} | 通道: ${device.channelNo} | 状态: ${device.onlineStatus}"
            tvInfo.text = info
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<Device>() {
        override fun areItemsTheSame(oldItem: Device, newItem: Device): Boolean {
            return oldItem.deviceId == newItem.deviceId
        }

        override fun areContentsTheSame(oldItem: Device, newItem: Device): Boolean {
            return oldItem == newItem
        }
    }
}
