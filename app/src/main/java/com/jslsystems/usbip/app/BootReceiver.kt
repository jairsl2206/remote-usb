package com.jslsystems.usbip.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts the server when the TV boots, if enabled in the status screen. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && Prefs.autostart(context)) {
            UsbIpService.send(context, UsbIpService.ACTION_START)
        }
    }
}
