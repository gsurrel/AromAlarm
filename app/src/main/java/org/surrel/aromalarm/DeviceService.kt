package org.surrel.aromalarm

import android.content.Intent
import android.util.Log
import androidx.lifecycle.LifecycleService

import org.surrel.aromalarm.Constants.MAC_ADDRESS

class DeviceService : LifecycleService() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i("DeviceService", intent?.getStringExtra(MAC_ADDRESS) ?: "No MAC address")
        return super.onStartCommand(intent, flags, startId)
    }

}