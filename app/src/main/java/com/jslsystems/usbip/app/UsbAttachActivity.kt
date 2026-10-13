package com.jslsystems.usbip.app

import android.app.Activity
import android.os.Bundle

/**
 * Invisible target of USB_DEVICE_ATTACHED for Logitech devices. Being launched through this
 * intent filter is what grants USB permission without a dialog once the user ticked
 * "use by default", which matters when the wheel re-enumerates in the middle of a game.
 */
class UsbAttachActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent.usbDevice()?.let { UsbIpService.send(this, UsbIpService.ACTION_DEVICE_ATTACHED, it) }
        finish()
    }
}
