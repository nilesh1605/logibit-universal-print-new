package com.logibit.universalprint

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Shared USB Printer Class permission, identification and transfer utilities. */
internal object UsbTransport {
    private const val TAG = "LogibitPrint"
    private const val ACTION_USB_PERMISSION = "com.logibit.universalprint.USB_PERMISSION"

    fun openWithPermission(context: Context, manager: UsbManager, device: UsbDevice): UsbDeviceConnection {
        if (manager.hasPermission(device)) {
            return manager.openDevice(device)
                ?: throw IllegalStateException("Android could not open the USB printer. Reconnect the OTG cable.")
        }
        val latch = CountDownLatch(1)
        var granted = false
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action == ACTION_USB_PERMISSION) {
                    granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    latch.countDown()
                }
            }
        }
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") context.registerReceiver(receiver, filter)
        try {
            val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
            val pending = PendingIntent.getBroadcast(
                context, device.deviceId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            manager.requestPermission(device, pending)
            if (!latch.await(45, TimeUnit.SECONDS)) {
                throw IllegalStateException("USB permission request timed out. Unlock your phone and approve the USB access prompt.")
            }
            if (!granted) throw IllegalStateException("USB printer permission was denied.")
        } finally {
            try { context.unregisterReceiver(receiver) } catch (_: Exception) { }
        }
        return manager.openDevice(device)
            ?: throw IllegalStateException("Android granted USB access but could not open the printer.")
    }

    fun findPrinterInterfaceAndBulkOut(device: UsbDevice): Pair<UsbInterface, UsbEndpoint> {
        val printerInterface = (0 until device.interfaceCount).map { device.getInterface(it) }
            .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_PRINTER }
            ?: throw IllegalStateException("No USB Printer Class interface was found on this device.")
        val outEndpoint = (0 until printerInterface.endpointCount).map { printerInterface.getEndpoint(it) }
            .firstOrNull {
                it.direction == UsbConstants.USB_DIR_OUT &&
                    it.type == UsbConstants.USB_ENDPOINT_XFER_BULK
            }
            ?: throw IllegalStateException("The USB printer has no bulk OUT endpoint.")
        return printerInterface to outEndpoint
    }

    /** IEEE-1284 Device ID control request (USB Printer Class GET_DEVICE_ID). */
    fun readIeee1284Id(connection: UsbDeviceConnection, intf: UsbInterface): String {
        val header = ByteArray(2)
        val n = connection.controlTransfer(0xA1, 0, 0, intf.id, header, 2, 1500)
        if (n < 2) return ""
        val length = ((header[0].toInt() and 0xff) shl 8) or (header[1].toInt() and 0xff)
        if (length < 2 || length > 4096) return ""
        val data = ByteArray(length)
        val got = connection.controlTransfer(0xA1, 0, 0, intf.id, data, length, 1500)
        return if (got > 2) String(data, 2, got - 2, Charsets.US_ASCII).trim('\u0000', ' ') else ""
    }

    fun commandLanguages(deviceId: String): List<String> =
        Regex("CMD:([^;]+)", RegexOption.IGNORE_CASE).find(deviceId)
            ?.groupValues?.get(1)?.split(',', ' ')?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: emptyList()

    /**
     * Bounded USB transfer. The interface is always released, including on timeout/error.
     * Reuses the original payload rather than allocating a new byte array for every chunk.
     */
    fun sendJob(
        connection: UsbDeviceConnection,
        printerInterface: UsbInterface,
        outEndpoint: UsbEndpoint,
        payload: ByteArray
    ) {
        require(payload.isNotEmpty()) { "The printer payload is empty." }
        if (!connection.claimInterface(printerInterface, true)) {
            throw IllegalStateException("Could not claim the USB printer interface. Close other apps using this printer and retry.")
        }
        try {
            Log.i(TAG, "Sending ${payload.size} bytes over USB")
            var offset = 0
            var consecutiveTimeouts = 0
            // Smaller chunks and bounded retries are more reliable on phones with
            // OTG adapters and printers that pause while processing raster bands.
            val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(5)
            while (offset < payload.size) {
                if (System.nanoTime() >= deadline) {
                    throw IllegalStateException("USB print transfer timed out after 5 minutes at byte $offset of ${payload.size}.")
                }
                val length = minOf(4 * 1024, payload.size - offset)
                val sent = connection.bulkTransfer(outEndpoint, payload, offset, length, 10_000)
                if (sent <= 0) {
                    consecutiveTimeouts++
                    if (consecutiveTimeouts >= 3) {
                        throw IllegalStateException("USB printer stopped accepting data at byte $offset of ${payload.size}. Check printer readiness, OTG cable and power, then retry.")
                    }
                    Thread.sleep(150L * consecutiveTimeouts)
                    continue
                }
                consecutiveTimeouts = 0
                offset += sent
            }
            Log.i(TAG, "USB transfer finished: $offset bytes")
        } finally {
            try {
                connection.releaseInterface(printerInterface)
            } catch (t: Throwable) {
                Log.w(TAG, "Could not release USB printer interface", t)
            }
        }
    }
}
