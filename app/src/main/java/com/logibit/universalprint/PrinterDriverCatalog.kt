package com.logibit.universalprint

import android.hardware.usb.UsbDevice
import java.util.Locale

/**
 * Versioned, data-only printer catalogue.
 *
 * A BUILT_IN entry is allowed only after its native driver has been verified
 * against the physical model. Recognition alone is not protocol support.
 */
enum class PrinterProtocol {
    IPP, PCL5, POSTSCRIPT, PWG_RASTER, HP_PCLMS_URF_PWG, SPL2_QPDL, HP_XQX, BROTHER_HBP, UNKNOWN
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
    val usbProductIds: Set<Int> = emptySet(),
    val protocol: PrinterProtocol,
    val availability: DriverAvailability,
    val driverId: String? = null
)

object PrinterDriverCatalog {
    const val CATALOG_VERSION = 3

    private val profiles = listOf(
        PrinterProfile(
            id = "samsung.ml-1670",
            manufacturer = "Samsung",
            modelAliases = setOf("ML-1670", "ML 1670"),
            usbVendorIds = setOf(0x04E8),
            protocol = PrinterProtocol.SPL2_QPDL,
            // The previous hand-written encoder was not validated on hardware.
            // Do not select it until the proven SpliX-compatible implementation is integrated.
            availability = DriverAvailability.DRIVER_REQUIRED
        ),
        PrinterProfile(
            id = "hp.laserjet-tank-mfp-1005",
            manufacturer = "HP",
            modelAliases = setOf(
                "LaserJet Tank MFP 1005",
                "LaserJet Tank MFP 1005w",
                "Tank MFP 1005",
                "Tank MFP 1005w"
            ),
            usbVendorIds = setOf(0x03F0),
            // Product ID is not yet confirmed from the user's physical USB device.
            protocol = PrinterProtocol.HP_PCLMS_URF_PWG,
            availability = DriverAvailability.DRIVER_REQUIRED,
            driverId = "hp.laserjet-tank-mfp-1005.pwg"
        ),
        PrinterProfile(
            id = "hp.laserjet-m1005",
            manufacturer = "HP",
            modelAliases = setOf("LaserJet M1005", "M1005", "M1005 MFP"),
            usbVendorIds = setOf(0x03F0),
            // 0x3B17 is reported for the M1005 MFP; retain model-name matching as fallback.
            usbProductIds = setOf(0x3B17),
            protocol = PrinterProtocol.HP_XQX,
            availability = DriverAvailability.DRIVER_REQUIRED
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
        return profiles.firstOrNull { profile ->
            device.vendorId in profile.usbVendorIds &&
                (device.productId in profile.usbProductIds ||
                    (identity.isNotBlank() &&
                        profile.modelAliases.any { alias -> identity.contains(normalize(alias)) }))
        }
    }

    private fun normalize(value: String): String =
        value.uppercase(Locale.ROOT).replace(Regex("[^A-Z0-9]"), "")
}
