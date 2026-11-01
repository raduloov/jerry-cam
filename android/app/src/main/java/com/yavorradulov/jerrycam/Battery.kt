package com.yavorradulov.jerrycam

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

data class BatteryState(val level: Int, val charging: Boolean)

/** Converts a battery broadcast's raw values; null when the reading is missing. */
fun batteryState(level: Int, scale: Int, plugged: Int): BatteryState? {
    if (level < 0 || scale <= 0) return null
    return BatteryState(level * 100 / scale, charging = plugged != 0)
}

/** Reports the camera phone's battery whenever the percentage or charging state changes. */
class BatteryMonitor(private val context: Context, private val onChange: (BatteryState) -> Unit) {
    private var last: BatteryState? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = report(intent)
    }

    fun start() {
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let(::report)
    }

    fun stop() {
        context.unregisterReceiver(receiver)
    }

    private fun report(intent: Intent) {
        val state = batteryState(
            intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
            intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1),
            intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0),
        ) ?: return
        if (state == last) return
        last = state
        onChange(state)
    }
}
