package com.logibit.universalprint

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager

/**
 * Extension point for USB printer-language drivers.
 *
 * Implementations should match only devices they can positively identify and must
 * never send bytes in a language the printer has not been confirmed to support.
 */
interface UsbPrinterDriver {
    /** Stable identifier used for diagnostics and duplicate-registration protection. */
    val driverId: String

    /** Higher values are tried first so exact model drivers precede generic fallbacks. */
    val priority: Int

    /** Return true only when this driver can identify the USB device. */
    fun matches(device: UsbDevice): Boolean

    /** Convert the PDF to the printer's native language and transmit it. */
    fun printPdf(context: Context, usbManager: UsbManager, device: UsbDevice, pdf: ByteArray)
}

/**
 * Central USB driver registry. Register manufacturer/model-specific drivers here;
 * exact matches should use a higher priority than language-level fallback drivers.
 */
object UsbPrinterDriverRegistry {
    private val lock = Any()
    private val registeredDrivers = mutableListOf<UsbPrinterDriver>(Pcl5UsbPrinterDriver)

    /** Add or replace a driver with the same ID. Safe to call during app startup. */
    fun register(driver: UsbPrinterDriver) {
        require(driver.driverId.isNotBlank()) { "A USB printer driver must have a stable driverId." }
        synchronized(lock) {
            registeredDrivers.removeAll { it.driverId == driver.driverId }
            registeredDrivers.add(driver)
        }
    }

    /** Return a read-only, priority-ordered snapshot for diagnostics and testing. */
    fun drivers(): List<UsbPrinterDriver> = synchronized(lock) {
        registeredDrivers.sortedByDescending { it.priority }.toList()
    }

    fun printPdf(context: Context, usbManager: UsbManager, device: UsbDevice, pdf: ByteArray) {
        val profile = PrinterDriverCatalog.find(device)
        val candidates = drivers().filter { it.matches(device) }

        // A known host-based model must never fall through to the generic PCL renderer.
        // That fallback caused the misleading "does not advertise PCL 5" message for
        // printers whose native language is QPDL, HP XQX, or Brother HBP.
        if (profile != null && profile.availability == DriverAvailability.DRIVER_REQUIRED) {
            val requiredDriver = profile.driverId
            val installed = requiredDriver != null && candidates.any { it.driverId == requiredDriver }
            if (!installed) {
                val engine = when (profile.protocol) {
                    PrinterProtocol.SPL2_QPDL -> "Samsung QPDL/SPL-C"
                    PrinterProtocol.HP_XQX -> "HP XQX"
                    PrinterProtocol.HP_PCLMS_URF_PWG -> "HP PCLmS/URF/PWG raster"
                    PrinterProtocol.BROTHER_HBP -> "Brother HBP"
                    else -> profile.protocol.name
                }
                throw IllegalStateException(
                    "${profile.manufacturer} ${profile.modelAliases.first()} was recognized. " +
                        "It requires the model-specific $engine driver, which is not installed in this build. " +
                        "The app will not send generic PCL data to this printer."
                )
            }
        }

        if (candidates.isEmpty()) {
            throw IllegalStateException(
                "No installed USB driver recognizes ${device.manufacturerName ?: "unknown manufacturer"} " +
                    "${device.productName ?: "unknown model"} (VID=0x${device.vendorId.toString(16)}, " +
                    "PID=0x${device.productId.toString(16)})."
            )
        }

        // The first match is the most specific driver by priority. A selected driver
        // must fail clearly rather than silently falling through after partial output.
        val selected = candidates.first()
        android.util.Log.i("LogibitPrint", "Selected USB driver ${selected.driverId} for ${device.deviceName}")
        selected.printPdf(context, usbManager, device, pdf)
    }
}

/**
 * Generic language-level fallback. The transport performs a live IEEE-1284 CMD
 * check and refuses to print unless PCL 5 is explicitly advertised.
 */
private object Pcl5UsbPrinterDriver : UsbPrinterDriver {
    override val driverId: String = "generic.usb.pcl5"
    override val priority: Int = 0

    // This is a fallback candidate, not a claim that every USB printer speaks PCL.
    override fun matches(device: UsbDevice): Boolean = true

    override fun printPdf(context: Context, usbManager: UsbManager, device: UsbDevice, pdf: ByteArray) {
        UsbPclPrinter.printPdf(context, usbManager, device, pdf)
    }
}
