package com.logibit.universalprint

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * USB driver for Samsung's own page-description language, commonly called SPL2 or QPDL
 * (Quick Page Description Language). Most budget Samsung/Xerox mono laser printers from the
 * ML/SCX/CLX/Phaser-6100 families — including the Samsung ML-1670 — do **not** speak PCL or
 * PostScript at all. They are "host-based"/GDI printers whose only native language is this
 * one, which is why the generic PCL 5 driver correctly refuses them.
 *
 * This implementation follows the openly published SPL2 protocol write-up by the SpliX /
 * OpenPrinting project (https://openprinting.github.io/splix/specs.html) — a factual
 * description of the wire format, not copied code — and keeps a deliberately simple,
 * spec-legal encoding:
 *   - every band is sent as a sequence of literal ("uncompressed") runs only; the
 *     back-reference compression SPL2 also supports is skipped entirely. This produces
 *     larger USB payloads than a real compressor would, but removes an entire class of
 *     compression bugs that would be very hard to debug without the printer in hand.
 *
 * IMPORTANT: this protocol was reconstructed from third-party reverse-engineering docs, not
 * from Samsung's own specification, and has not been exercised against real hardware here.
 * Some header fields are documented as "purpose unclear" even by the people who reverse
 * engineered them. Treat the first print as a test: watch Logcat (tag LogibitPrint) and the
 * printer's own error/status lights, and report back anything that looks wrong so the
 * framing can be corrected.
 */
private object Spl2UsbPrinterDriver : UsbPrinterDriver {
    override val driverId: String = "samsung.usb.spl2"

    // Tried before the generic PCL 5 fallback, but after any future driver that does a
    // positive, connection-level identification (e.g. matching the full IEEE-1284 model
    // string). VID-only matching is coarse, so printPdf() still re-checks the device's
    // advertised languages before sending anything.
    override val priority: Int = 5

    // Samsung Electronics' USB-IF vendor id (public registry value, the same one every
    // Samsung USB device reports) is the only thing we can see before opening the device.
    private const val SAMSUNG_VID = 0x04E8

    override fun matches(device: UsbDevice): Boolean = device.vendorId == SAMSUNG_VID

    override fun printPdf(context: Context, usbManager: UsbManager, device: UsbDevice, pdf: ByteArray) {
        val connection = UsbTransport.openWithPermission(context, usbManager, device)
        try {
            val (printerInterface, outEndpoint) = UsbTransport.findPrinterInterfaceAndBulkOut(device)

            val deviceId = UsbTransport.readIeee1284Id(connection, printerInterface)
            val commands = UsbTransport.commandLanguages(deviceId)
            Log.i(TAG, "USB IEEE-1284 device ID: ${deviceId.ifBlank { "not provided (normal for many SPL2 printers)" }}")

            // Many SPL2/QPDL printers don't return a usable IEEE-1284 string at all, which is
            // exactly why the generic driver rejected this device in the first place. We only
            // bail out here if the printer explicitly claims a standard language we already
            // support properly elsewhere — e.g. a newer Samsung model that really does speak
            // PCL6/PostScript and would be better served by a driver for that language.
            val standardLanguage = commands.firstOrNull {
                it.equals("PCL", true) || it.startsWith("PCL", true) || it.equals("POSTSCRIPT", true) || it.equals("PS", true)
            }
            if (standardLanguage != null) {
                throw IllegalStateException(
                    "This Samsung printer advertises '$standardLanguage', not SPL2/QPDL. " +
                        "The SPL2 driver only handles devices with no confirmed standard language."
                )
            }

            val qpdl = renderPdfToQpdl(context, pdf)
            UsbTransport.sendJob(connection, printerInterface, outEndpoint, qpdl)
        } finally {
            connection.close()
        }
    }

    // ---- QPDL job assembly -------------------------------------------------------------

    /** Standard HP PJL "Universal Exit Language" marker: ESC %-12345X. Used industry-wide to
     *  enter/exit PJL, including by this printer's own PPD (PJL BeginPJL/EndPJL fields). */
    private val UEL = byteArrayOf(0x1B, '%'.code.toByte(), '-'.code.toByte(), '1'.code.toByte(), '2'.code.toByte(), '3'.code.toByte(), '4'.code.toByte(), '5'.code.toByte(), 'X'.code.toByte())

    private const val RESOLUTION_DPI = 600
    private const val BAND_HEIGHT_LINES = 128 // fixed by the protocol: one band = 128 raster lines

    // SPL2 standard paper-size codes for the sizes this build actually offers in
    // PrinterCapabilitiesInfo (see LogibitPrintService.discoverUsbPrinters/IPP capabilities).
    private const val PAPER_LETTER = 0
    private const val PAPER_A4 = 2
    private const val PAPER_NOT_LISTED = 13

    private fun renderPdfToQpdl(context: Context, pdf: ByteArray): ByteArray {
        val file = File.createTempFile("logibit-print-", ".pdf", context.cacheDir)
        try {
            FileOutputStream(file).use { it.write(pdf) }
            val out = ByteArrayOutputStream()

            out.write(UEL)
            out.write("@PJL\n".toByteArray(Charsets.US_ASCII))
            out.write("@PJL ENTER LANGUAGE = QPDL\n".toByteArray(Charsets.US_ASCII))

            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    for (pageIndex in 0 until renderer.pageCount) {
                        renderer.openPage(pageIndex).use { page ->
                            writePage(out, page)
                        }
                    }
                }
            }

            // JCL end marker: tab + UEL, taken verbatim from this printer family's own PPD.
            out.write(0x09)
            out.write(UEL)
            return out.toByteArray()
        } finally {
            file.delete()
        }
    }

    private fun writePage(out: ByteArrayOutputStream, page: PdfRenderer.Page) {
        val widthPx = (page.width * RESOLUTION_DPI / 72f).toInt().coerceAtLeast(1)
        val heightPx = (page.height * RESOLUTION_DPI / 72f).toInt().coerceAtLeast(1)
        require(widthPx.toLong() * heightPx <= 60_000_000L) { "PDF page is too large to rasterize at $RESOLUTION_DPI dpi." }

        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)

            val rowBytes = (widthPx + 7) / 8
            val paperCode = paperSizeCode(page.width, page.height)

            writePageHeader(out, paperCode)

            var line = 0
            var bandNr = 0
            while (line < heightPx) {
                val bandLines = minOf(BAND_HEIGHT_LINES, heightPx - line)
                val bandRows = Array(bandLines) { ByteArray(rowBytes) }
                for (r in 0 until bandLines) {
                    packMonochromeRow(bitmap, line + r, widthPx, bandRows[r])
                }
                writeBand(out, bandNr, widthPx, bandLines, rowBytes, bandRows)
                line += bandLines
                bandNr++
            }

            writePageFooter(out)
        } finally {
            bitmap.recycle()
        }
    }

    private fun packMonochromeRow(bitmap: Bitmap, y: Int, widthPx: Int, rowOut: ByteArray) {
        java.util.Arrays.fill(rowOut, 0)
        for (x in 0 until widthPx) {
            val pixel = bitmap.getPixel(x, y)
            val gray = (Color.red(pixel) * 299 + Color.green(pixel) * 587 + Color.blue(pixel) * 114) / 1000
            // PBM convention: a set bit is black. This is inverted for the wire format below.
            if (gray < 190) rowOut[x / 8] = (rowOut[x / 8].toInt() or (0x80 ushr (x % 8))).toByte()
        }
    }

    /** 17-byte "begin page" record. Tag byte 0 doubles as the record type per the spec. */
    private fun writePageHeader(out: ByteArrayOutputStream, paperCode: Int) {
        val h = ByteArray(0x11)
        h[0x0] = 0 // record tag / fixed value
        h[0x1] = (RESOLUTION_DPI / 100).toByte()
        writeU16BE(h, 0x2, 1) // copies
        h[0x4] = paperCode.toByte()
        writeU16BE(h, 0x5, 0) // custom width: unused, we only emit known standard sizes
        writeU16BE(h, 0x7, 0) // custom length: unused
        h[0x9] = 1 // paper feeder: Auto
        h[0xA] = 0
        h[0xB] = 0 // duplex off
        h[0xC] = 0 // duplex tumble off
        h[0xD] = 0
        h[0xE] = 1
        writeU16BE(h, 0xF, 0)
        out.write(h)
    }

    /** 3-byte "end page" record. */
    private fun writePageFooter(out: ByteArrayOutputStream) {
        val f = ByteArray(3)
        f[0] = 1 // record tag
        writeU16BE(f, 1, 1) // copies
        out.write(f)
    }

    /**
     * One band: an 11-byte band header followed by a "compressed data" block (here always
     * literal/uncompressed runs) and a 4-byte checksum, per the SPL2 band format.
     */
    private fun writeBand(out: ByteArrayOutputStream, bandNr: Int, widthPx: Int, bandLines: Int, rowBytes: Int, rows: Array<ByteArray>) {
        // Rearrange row-major data into the column-major order the printer expects: byte
        // column 0 of every line, then byte column 1 of every line, and so on. Each byte is
        // bit-inverted (printer treats a clear bit as black, the reverse of our PBM-style rows).
        val transposed = ByteArray(rowBytes * bandLines)
        var idx = 0
        for (col in 0 until rowBytes) {
            for (r in 0 until bandLines) {
                transposed[idx++] = rows[r][col].inv()
            }
        }

        val body = ByteArrayOutputStream()
        // Compressed-data header: auto-detected-endianness signature, zero bytes of raw
        // "history" data (we never emit a back-reference, so none is needed), and the
        // mandatory-but-unused 64-entry pointer table.
        body.write(byteArrayOf(0x09, 0xAB.toByte(), 0xCD.toByte(), 0xEF.toByte()))
        writeU32BE(body, 0) // raw data length = 0
        body.write(ByteArray(64 * 2)) // pointer table, unused since we never back-reference

        // Literal-only encoding: chunks of at most 64 bytes, top bit of the length byte clear.
        var off = 0
        while (off < transposed.size) {
            val chunkLen = minOf(64, transposed.size - off)
            body.write(chunkLen - 1)
            body.write(transposed, off, chunkLen)
            off += chunkLen
        }

        val bodyBytes = body.toByteArray()
        var checksum = 0L
        for (b in bodyBytes) checksum = (checksum + (b.toInt() and 0xff)) and 0xFFFFFFFFL

        val header = ByteArray(0xB)
        header[0x0] = 0xC.toByte() // band record tag
        header[0x1] = (bandNr and 0xFF).toByte()
        writeU16BE(header, 0x2, widthPx)
        writeU16BE(header, 0x4, bandLines)
        header[0x6] = 0x11 // compression version, matches this model's PPD (cupsCompression 17)
        writeU32BE(header, 0x7, bodyBytes.size + 4) // body + trailing checksum

        out.write(header)
        out.write(bodyBytes)
        writeU32BE(out, checksum.toInt())
    }

    private fun paperSizeCode(widthPt: Int, heightPt: Int): Int {
        fun near(a: Int, b: Int) = kotlin.math.abs(a - b) <= 2
        return when {
            near(widthPt, 612) && near(heightPt, 792) -> PAPER_LETTER
            near(widthPt, 595) && near(heightPt, 842) -> PAPER_A4
            else -> {
                Log.w(TAG, "Unrecognized page size ${widthPt}x${heightPt}pt; falling back to A4 sizing.")
                PAPER_NOT_LISTED
            }
        }
    }

    private fun writeU16BE(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = ((value shr 8) and 0xFF).toByte()
        buf[offset + 1] = (value and 0xFF).toByte()
    }

    private fun writeU32BE(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 24) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    private fun writeU32BE(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = ((value ushr 24) and 0xFF).toByte()
        buf[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        buf[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        buf[offset + 3] = (value and 0xFF).toByte()
    }

    private const val TAG = "LogibitPrint"
}

/** Registers the SPL2 driver with the shared registry. Called once from app startup. */
internal fun registerSamsungSpl2Driver() {
    UsbPrinterDriverRegistry.register(Spl2UsbPrinterDriver)
}
