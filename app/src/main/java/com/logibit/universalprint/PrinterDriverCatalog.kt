package com.logibit.universalprint

import android.hardware.usb.UsbDevice
import java.util.Locale

/**
 * Versioned, data-only printer catalogue.
 *
 * Add model aliases and USB IDs here as devices are verified. A catalogue entry is
 * descriptive: it never claims that a protocol is supported unless an executable
 * driver is registered for that protocol.
 */
enum class PrinterProtocol {
    IPP, PCL5, POSTSCRIPT, PWG_RASTER, SPL2_QPDL, BROTHER_HBP, UNKNOWN
}

enum class DriverAvailability {
    BUILT_IN,
    DRIVER_REQUIRED,
    STANDARD_PROTOCOL
}

data class PrinterProfile(
    val id: String,
    val manufacturer: String,
    val modelAliases: Set<String>,
    val usbVendorIds: Set<Int>,
    val protocol: PrinterProtocol,
    val availability: DriverAvailability,
    val driverId: String? = null
)

object PrinterDriverCatalog {
    const val CATALOG_VERSION = 1

    // Keep this list conservative: only add a BUILT_IN entry after its driver has
    // been implemented and tested against the corresponding physical printer.
    private val profiles = listOf(
        PrinterProfile(
            id = "samsung.ml-1670",
            manufacturer = "Samsung",
            modelAliases = setOf("ML-1670", "ML 1670"),
            usbVendorIds = setOf(0x04E8),
            protocol = PrinterProtocol.SPL2_QPDL,
            availability = DriverAvailability.BUILT_IN,
            driverId = "samsung.usb.spl2"
        ),
        PrinterProfile(
            id = "brother.hl-l2320d",
            manufacturer = "Brother",
            modelAliases = setOf("HL-L2320D", "HL L2320D"),
            usbVendorIds = setOf(0x04F9),
            protocol = PrinterProtocol.BROTHER_HBP,
            availability = DriverAvailability.DRIVER_REQUIRED
        )
    )

    fun allProfiles(): List<PrinterProfile> = profiles.toList()

    fun find(device: UsbDevice): PrinterProfile? {
        val identity = normalize(
            listOfNotNull(device.manufacturerName, device.productName).joinToString(" ")
        )
        if (identity.isBlank()) return null

        return profiles.firstOrNull { profile ->
            device.vendorId in profile.usbVendorIds &&
                profile.modelAliases.any { alias -> identity.contains(normalize(alias)) }
        }
    }

    private fun normalize(value: String): String =
        value.uppercase(Locale.ROOT).replace(Regex("[^A-Z0-9]"), "")
}
