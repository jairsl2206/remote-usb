package com.jslsystems.usbip.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.jslsystems.usbip.core.LogLevel
import com.jslsystems.usbip.core.ServerEvents
import com.jslsystems.usbip.core.UsbIpServer
import java.io.IOException

/**
 * Foreground service (type connectedDevice) that keeps the USB/IP server alive while Steam Link
 * is in the foreground, and re-exports the wheel when it re-enumerates.
 */
class UsbIpService : Service(), ServerEvents {

    companion object {
        const val ACTION_START = "com.jslsystems.usbip.START"
        const val ACTION_STOP = "com.jslsystems.usbip.STOP"
        const val ACTION_DEVICE_ATTACHED = "com.jslsystems.usbip.DEVICE_ATTACHED"
        const val ACTION_REQUEST_PERMISSIONS = "com.jslsystems.usbip.REQUEST_PERMISSIONS"
        private const val ACTION_USB_PERMISSION = "com.jslsystems.usbip.USB_PERMISSION"
        private const val CHANNEL = "usbip"
        private const val NOTIFICATION_ID = 1
        const val LOGITECH_VENDOR_ID = 0x046d

        /** The running instance, for the status screen. */
        @Volatile var instance: UsbIpService? = null
            private set

        fun send(context: Context, action: String, device: UsbDevice? = null) {
            val intent = Intent(context, UsbIpService::class.java).setAction(action)
            device?.let { intent.putExtra(UsbManager.EXTRA_DEVICE, it) }
            if (action == ACTION_STOP) context.startService(intent) else context.startForegroundService(intent)
        }
    }

    lateinit var backend: AndroidUsbBackend
        private set
    var server: UsbIpServer? = null
        private set

    private lateinit var usb: UsbManager
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = intent.usbDevice() ?: return
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_DETACHED -> onDetached(device)
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> onAttached(device)
                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    log(LogLevel.INFO, "Permiso USB para ${device.label()}: ${if (granted) "concedido" else "negado"}")
                    if (granted) onAttached(device)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        usb = getSystemService(UsbManager::class.java)
        backend = AndroidUsbBackend(this, this)
        createChannel()
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_USB_PERMISSION)
        }
        // Not exported: system broadcasts and our own PendingIntent still arrive, spoofed ones don't.
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(usbReceiver, filter)
        }
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        goForeground()
        startServer()
        when (intent?.action) {
            ACTION_DEVICE_ATTACHED -> intent.usbDevice()?.let { onAttached(it) }
            ACTION_REQUEST_PERMISSIONS -> requestMissingPermissions(onlyLogitech = false)
            else -> requestMissingPermissions(onlyLogitech = true)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        server?.stop()
        server = null
        unregisterReceiver(usbReceiver)
        wifiLock?.takeIf { it.isHeld }?.release()
        wakeLock?.takeIf { it.isHeld }?.release()
        AppState.changed()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ServerEvents
    override fun log(level: LogLevel, message: String) = AppState.log(level, message)

    override fun sessionsChanged() {
        updateNotification()
        AppState.changed()
    }

    private fun startServer() {
        if (server?.isRunning == true) return
        val s = UsbIpServer(backend, this)
        try {
            s.start()
        } catch (e: IOException) {
            log(LogLevel.ERROR, "No se pudo abrir el puerto 3240: ${e.message}")
            return
        }
        server = s
        acquireLocks()
        updateNotification()
        AppState.changed()
    }

    private fun onAttached(device: UsbDevice) {
        val busId = backend.busIdOf(device)
        if (usb.hasPermission(device)) {
            log(LogLevel.INFO, "Conectado ${device.label()} → exportado como $busId")
        } else {
            log(LogLevel.INFO, "Conectado ${device.label()} ($busId), falta permiso USB")
            if (device.vendorId == LOGITECH_VENDOR_ID) requestPermission(device)
        }
        AppState.changed()
    }

    private fun onDetached(device: UsbDevice) {
        val busId = backend.busIdOf(device)
        log(LogLevel.INFO, "Desconectado ${device.label()} ($busId)")
        // Close the TCP session so usbip-win2 notices and re-attaches (*Auto*) when it comes back.
        server?.closeSession(busId)
        backend.onDetached(device)
        AppState.changed()
    }

    private fun requestMissingPermissions(onlyLogitech: Boolean) {
        backend.attachedDevices()
            .filter { !usb.hasPermission(it) && (!onlyLogitech || it.vendorId == LOGITECH_VENDOR_ID) }
            .forEach { requestPermission(it) }
    }

    private fun requestPermission(device: UsbDevice) {
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(packageName)
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        usb.requestPermission(device, PendingIntent.getBroadcast(this, device.deviceId, intent, flags))
    }

    private fun acquireLocks() {
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "usbip:server")
                .apply { acquire() }
        }
        if (wifiLock == null) {
            val mode = if (Build.VERSION.SDK_INT >= 29) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = applicationContext.getSystemService(WifiManager::class.java)
                ?.createWifiLock(mode, "usbip:server")
                ?.apply { acquire() }
        }
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val sessions = server?.sessions().orEmpty()
        val text = if (sessions.isEmpty()) {
            getString(R.string.notification_idle)
        } else {
            sessions.joinToString { "${it.busId} → ${it.client.substringBefore(':')}" }
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun goForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }
}

@Suppress("DEPRECATION")
fun Intent.usbDevice(): UsbDevice? = if (Build.VERSION.SDK_INT >= 33) {
    getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
} else {
    getParcelableExtra(UsbManager.EXTRA_DEVICE)
}

fun UsbDevice.label(): String =
    "${productName ?: "USB"} (%04x:%04x)".format(vendorId, productId)
