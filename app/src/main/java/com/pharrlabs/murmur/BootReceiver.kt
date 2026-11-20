package com.pharrlabs.murmur

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        try { PushService.start(c) } catch (e: Exception) { /* background start can be refused; MainActivity restarts it */ }
    }
}
