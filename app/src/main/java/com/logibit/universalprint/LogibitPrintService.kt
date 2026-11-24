package com.logibit.universalprint

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.ParcelFileDescriptor
import android.os.Handler
import android.os.Looper
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.print.PrintAttributes
import android.printservice.PrintJob
import android.print.PrinterCapabilitiesInfo
import android.print.PrinterId
import android.print.PrinterInfo
import android.printservice.PrintService
import android.printservice.PrinterDiscoverySession
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class LogibitPrintService : PrintService() {
    private val io = Executors.newCachedThreadPool()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val printers = ConcurrentHashMap<String, DiscoveredPrinter>()
    private var nsd: NsdManager? = null
    private var usbManager: UsbManager? = null

    data class DiscoveredPrinter(
        val id: PrinterId, val name: String,
        val host: String = "", val port: Int = 0, val resource: String = "",
        val usbDevice: UsbDevice? = null
    )

    override fun onCreate() {
        super.onCreate()
        nsd = getSystemService(NSD_SERVICE) as NsdManager
        usbManager = getSystemService(USB_SERVICE) as UsbManager
        registerSamsungSpl2Driver()
        UsbPrinterDriverRegistry.register(HpTankPwgRasterDriver)
    }

    override fun onCreatePrinterDiscoverySession(): PrinterDiscoverySession = LogibitDiscoverySession()

    override fun onPrintJobQueued(printJob: PrintJob) {
        // Android PrintJob APIs must be accessed on the main thread.
        val targetAndDescriptor = try {
            val printerId = printJob.info.printerId
                ?: throw IllegalStateException("The print job has no selected printer.")
            val target = printers[printerId.localId]
                ?: throw IllegalStateException("Printer is no longer available. Refresh printer discovery.")
            if (!printJob.start()) {
                throw IllegalStateException("Android could not start this print job.")
            }
            val pfd = printJob.document.data
                ?: throw IllegalStateException("Android did not provide a document file descriptor.")
            target to pfd
        } catch (t: Throwable) {
            Log.e(TAG, "Could not start print job", t)
            printJob.fail(t.message ?: "Could not start print job")
            return
        }

        val (target, pfd) = targetAndDescriptor
        io.execute {
            try {
                val pdf = ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        out.write(buffer, 0, count)
                    }
                    out.toByteArray()
                }
                require(pdf.isNotEmpty()) { "The PDF document is empty." }
                require(pdf.size >= 5 && String(pdf, 0, 5, Charsets.US_ASCII) == "%PDF-") {
                    "The Android print job did not contain a PDF document."
                }
                Log.i(TAG, "Read ${pdf.size} PDF bytes for ${target.name}")
                rememberPrintSettings(printJob.info.attributes)
                if (target.usbDevice != null) {
                    Log.i(TAG, "Sending USB/OTG job to ${target.name}")
                    UsbPrinterDriverRegistry.printPdf(this@LogibitPrintService, usbManager!!, target.usbDevice, pdf)
                } else {
                    Log.i(TAG, "Sending IPP Print-Job to ${target.name}")
                    IppClient.printPdf(target, pdf)
                }
                mainHandler.post {
                    if (!printJob.complete()) Log.w(TAG, "Android did not accept print-job completion")
                }
                Log.i(TAG, "Print job completed for ${target.name}")
            } catch (t: Throwable) {
                Log.e(TAG, "Print job failed", t)
                mainHandler.post {
                    printJob.fail(t.message ?: "Unknown printing error")
                }
            }
        }
    }

    override fun onRequestCancelPrintJob(printJob: PrintJob) {
        if (!printJob.isCompleted && !printJob.isCancelled) printJob.cancel()
    }

    private fun discoverUsbPrinters(session: PrinterDiscoverySession) {
        val manager = usbManager ?: return
        val found = mutableListOf<PrinterInfo>()
        for (device in manager.deviceList.values) {
            val printerInterface = (0 until device.interfaceCount).map { device.getInterface(it) }
                .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_PRINTER }
                ?: continue
            val id = generatePrinterId("usb:${device.deviceName}:${device.vendorId}:${device.productId}")
            val name = listOfNotNull(device.manufacturerName, device.productName).joinToString(" ")
                .ifBlank { "USB printer ${device.vendorId.toString(16)}:${device.productId.toString(16)}" }
            val item = DiscoveredPrinter(id, name, usbDevice = device)
            printers[id.localId] = item
            val caps = buildCapabilities(id,
                listOf(PrintAttributes.MediaSize.ISO_A4, PrintAttributes.MediaSize.ISO_A4.asLandscape(),
                    PrintAttributes.MediaSize.ISO_A5, PrintAttributes.MediaSize.ISO_A5.asLandscape(),
                    PrintAttributes.MediaSize.NA_LETTER),
                listOf(PrintAttributes.Resolution("usb300", "300 dpi", 300, 300)),
                PrintAttributes.COLOR_MODE_MONOCHROME, PrintAttributes.COLOR_MODE_MONOCHROME)
            found.add(PrinterInfo.Builder(id, name, PrinterInfo.STATUS_IDLE)
                .setDescription("USB / OTG printer · interface ${printerInterface.id}")
                .setCapabilities(caps).build())
            Log.i(TAG, "Discovered USB printer $name (${device.deviceName}), interface=${printerInterface.id}")
        }
        if (found.isNotEmpty()) session.addPrinters(found)
    }

    private fun buildCapabilities(id: PrinterId, mediaSizes: List<PrintAttributes.MediaSize>,
        resolutions: List<PrintAttributes.Resolution>, colorModes: Int, defaultColorMode: Int): PrinterCapabilitiesInfo {
        val prefs = getSharedPreferences("last_print_settings", Context.MODE_PRIVATE)
        val defaultMedia = mediaSizes.firstOrNull { it.id == prefs.getString("media_id", null) } ?: mediaSizes.first()
        val defaultResolution = resolutions.firstOrNull { it.id == prefs.getString("resolution_id", null) } ?: resolutions.first()
        val savedColor = prefs.getInt("color_mode", defaultColorMode)
        val color = if ((savedColor == PrintAttributes.COLOR_MODE_COLOR || savedColor == PrintAttributes.COLOR_MODE_MONOCHROME)
            && colorModes and savedColor != 0) savedColor else defaultColorMode
        return PrinterCapabilitiesInfo.Builder(id).apply {
            mediaSizes.forEach { addMediaSize(it, it.id == defaultMedia.id) }
            resolutions.forEach { addResolution(it, it.id == defaultResolution.id) }
            setColorModes(colorModes, color)
            setDuplexModes(PrintAttributes.DUPLEX_MODE_NONE, PrintAttributes.DUPLEX_MODE_NONE)
        }.build()
    }

    private fun rememberPrintSettings(attributes: PrintAttributes?) {
        if (attributes == null) return
        val e = getSharedPreferences("last_print_settings", Context.MODE_PRIVATE).edit()
        attributes.mediaSize?.let { e.putString("media_id", it.id) }
        attributes.resolution?.let { e.putString("resolution_id", it.id) }
        e.putInt("color_mode", attributes.colorMode)
        e.putInt("duplex_mode", attributes.duplexMode)
        attributes.minMargins?.let {
            e.putInt("margin_left_mils", it.leftMils); e.putInt("margin_top_mils", it.topMils)
            e.putInt("margin_right_mils", it.rightMils); e.putInt("margin_bottom_mils", it.bottomMils)
        }
        e.apply()
    }

    private inner class LogibitDiscoverySession : PrinterDiscoverySession() {
        private var listener: NsdManager.DiscoveryListener? = null

        override fun onStartPrinterDiscovery(priorityList: MutableList<PrinterId>) {
            val manager = nsd ?: return
            discoverUsbPrinters(this)
            if (listener != null) return
            val l = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) { Log.i(TAG, "IPP discovery started") }
                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    if (!serviceInfo.serviceType.contains("_ipp._tcp") && !serviceInfo.serviceType.contains("_ipps._tcp")) return
                    try {
                        manager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                                Log.w(TAG, "Could not resolve ${info.serviceName}: $errorCode")
                            }
                            override fun onServiceResolved(info: NsdServiceInfo) {
                                val host = info.host?.hostAddress ?: return
                                val attrs = info.attributes
                                val rp = attrs["rp"]?.toString(Charsets.UTF_8)?.trim('/')?.takeIf { it.isNotBlank() } ?: "ipp/print"
                                val resource = "/$rp"
                                val id = generatePrinterId("ipp:$host:${info.port}:$resource")
                                val name = info.serviceName ?: "Network printer ($host)"
                                val item = DiscoveredPrinter(id, name, host, info.port, resource)
                                this@LogibitPrintService.printers[id.localId] = item
                                val caps = buildCapabilities(id,
                                    listOf(PrintAttributes.MediaSize.ISO_A4, PrintAttributes.MediaSize.ISO_A4.asLandscape(),
                                        PrintAttributes.MediaSize.ISO_A5, PrintAttributes.MediaSize.ISO_A5.asLandscape(),
                                        PrintAttributes.MediaSize.NA_LETTER),
                                    listOf(PrintAttributes.Resolution("default", "Default", 300, 300)),
                                    PrintAttributes.COLOR_MODE_MONOCHROME or PrintAttributes.COLOR_MODE_COLOR,
                                    PrintAttributes.COLOR_MODE_COLOR)
                                val printer = PrinterInfo.Builder(id, name, PrinterInfo.STATUS_IDLE)
                                    .setDescription("IPP network printer · $host:${info.port}")
                                    .setCapabilities(caps)
                                    .build()
                                addPrinters(mutableListOf(printer))
                                Log.i(TAG, "Discovered IPP printer $name at $host:${info.port}$resource")
                            }
                        })
                    } catch (t: Throwable) { Log.w(TAG, "Resolve error", t) }
                }
                override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                    val match = this@LogibitPrintService.printers.values.firstOrNull { it.name == serviceInfo.serviceName }
                    if (match != null) {
                        this@LogibitPrintService.printers.remove(match.id.localId)
                        removePrinters(mutableListOf(match.id))
                    }
                }
                override fun onDiscoveryStopped(serviceType: String) { Log.i(TAG, "IPP discovery stopped") }
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    Log.e(TAG, "IPP discovery start failed: $errorCode")
                    try { manager.stopServiceDiscovery(this) } catch (_: Exception) { }
                    listener = null
                }
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                    Log.e(TAG, "IPP discovery stop failed: $errorCode")
                }
            }
            listener = l
            try { manager.discoverServices("_ipp._tcp.", NsdManager.PROTOCOL_DNS_SD, l) }
            catch (t: Throwable) { listener = null; Log.e(TAG, "Could not start IPP discovery", t) }
        }

        override fun onStopPrinterDiscovery() {
            listener?.let { try { nsd?.stopServiceDiscovery(it) } catch (_: Exception) { } }
            listener = null
        }
        override fun onValidatePrinters(printerIds: MutableList<PrinterId>) { }
        override fun onStartPrinterStateTracking(printerId: PrinterId) { }
        override fun onStopPrinterStateTracking(printerId: PrinterId) { }
        override fun onDestroy() { onStopPrinterDiscovery() }
    }

    companion object { private const val TAG = "LogibitPrint" }
}

/** USB Printer Class transport for PCL 5 raster devices. */
internal object UsbPclPrinter {
    fun printPdf(context: Context, manager: UsbManager, device: UsbDevice, pdf: ByteArray) {
        val connection = UsbTransport.openWithPermission(context, manager, device)
        try {
            val (printerInterface, outEndpoint) = UsbTransport.findPrinterInterfaceAndBulkOut(device)

            val deviceId = UsbTransport.readIeee1284Id(connection, printerInterface)
            Log.i("LogibitPrint", "USB IEEE-1284 device ID: ${deviceId.ifBlank { "not provided" }}")
            val commands = UsbTransport.commandLanguages(deviceId)
            val pclAdvertised = commands.any { it.equals("PCL", true) || it.equals("PCL5", true) || it.equals("PCL5E", true) || it.equals("PCL5C", true) }
            if (!pclAdvertised) {
                throw IllegalStateException("USB printer connected, but it does not advertise PCL 5. Its language is '${commands.joinToString(",").ifBlank { "unknown" }}'. This build cannot safely send a compatible job to this model yet; a model-specific driver is required.")
            }
            val pcl = renderPdfToPcl(context, pdf)
            UsbTransport.sendJob(connection, printerInterface, outEndpoint, pcl)
        } finally {
            connection.close()
        }
    }

    private fun renderPdfToPcl(context: Context, pdf: ByteArray): ByteArray {
        val file = File.createTempFile("logibit-print-", ".pdf", context.cacheDir)
        try {
            FileOutputStream(file).use { it.write(pdf) }
            val output = ByteArrayOutputStream()
            output.write(byteArrayOf(0x1b, 0x45)) // ESC E: reset
            output.write("\u001B&l0o\u001B*t300R".toByteArray(Charsets.US_ASCII))
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    for (pageIndex in 0 until renderer.pageCount) {
                        renderer.openPage(pageIndex).use { page ->
                            val width = (page.width * 300f / 72f).toInt().coerceAtLeast(1)
                            val height = (page.height * 300f / 72f).toInt().coerceAtLeast(1)
                            require(width.toLong() * height <= 45_000_000L) { "PDF page is too large to rasterize at 300 dpi." }
                            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                            try {
                                bitmap.eraseColor(Color.WHITE)
                                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                                val rowBytes = (width + 7) / 8
                                output.write("\u001B*r${width}S\u001B*r${height}T\u001B*r1A".toByteArray(Charsets.US_ASCII))
                                val row = ByteArray(rowBytes)
                                for (y in 0 until height) {
                                    java.util.Arrays.fill(row, 0)
                                    for (x in 0 until width) {
                                        val pixel = bitmap.getPixel(x, y)
                                        val gray = (Color.red(pixel) * 299 + Color.green(pixel) * 587 + Color.blue(pixel) * 114) / 1000
                                        if (gray < 190) row[x / 8] = (row[x / 8].toInt() or (0x80 ushr (x % 8))).toByte()
                                    }
                                    output.write("\u001B*b${row.size}W".toByteArray(Charsets.US_ASCII))
                                    output.write(row)
                                }
                                output.write("\u001B*rB".toByteArray(Charsets.US_ASCII))
                                output.write(0x0c) // form feed between pages
                            } finally { bitmap.recycle() }
                        }
                    }
                }
            }
            output.write(byteArrayOf(0x1b, 0x45))
            return output.toByteArray()
        } finally { file.delete() }
    }
}

/** Minimal IPP 2.0 Print-Job client. It submits the original PDF to printers advertising IPP. */
private object IppClient {
    private val requestIds = AtomicInteger(1)

    fun printPdf(printer: LogibitPrintService.DiscoveredPrinter, pdf: ByteArray) {
        val uri = URI("ipp", null, printer.host, printer.port, printer.resource, null, null)
        val body = ByteArrayOutputStream()
        val header = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
            .put(2.toByte()).put(0.toByte()).putShort(0x0002.toShort()).putInt(requestIds.getAndIncrement()).array()
        body.write(header)
        body.write(0x01) // operation-attributes-tag
        attribute(body, 0x47, "attributes-charset", "utf-8")
        attribute(body, 0x48, "attributes-natural-language", "en")
        attribute(body, 0x45, "printer-uri", uri.toString())
        attribute(body, 0x42, "requesting-user-name", "Logibit Universal Print")
        attribute(body, 0x49, "document-format", "application/pdf")
        body.write(0x03) // end-of-attributes-tag
        body.write(pdf)

        val url = URL("http://${printer.host}:${printer.port}${printer.resource}")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 12_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("Content-Type", "application/ipp")
            setRequestProperty("Accept", "application/ipp")
            setFixedLengthStreamingMode(body.size())
        }
        try {
            connection.outputStream.use { body.writeTo(it) }
            val code = connection.responseCode
            val response = (if (code in 200..299) connection.inputStream else connection.errorStream)?.use { it.readBytes() }
                ?: ByteArray(0)
            if (code !in 200..299) throw IllegalStateException("Printer HTTP error $code")
            if (response.size < 4) throw IllegalStateException("Printer returned an invalid IPP response")
            val status = ((response[2].toInt() and 0xff) shl 8) or (response[3].toInt() and 0xff)
            if (status >= 0x0400) throw IllegalStateException("Printer rejected the IPP job (status 0x${status.toString(16)})")
        } finally { connection.disconnect() }
    }

    private fun attribute(out: ByteArrayOutputStream, tag: Int, name: String, value: String) {
        val n = name.toByteArray(Charsets.UTF_8)
        val v = value.toByteArray(Charsets.UTF_8)
        out.write(tag)
        out.write((n.size shr 8) and 0xff); out.write(n.size and 0xff); out.write(n)
        out.write((v.size shr 8) and 0xff); out.write(v.size and 0xff); out.write(v)
    }
}
