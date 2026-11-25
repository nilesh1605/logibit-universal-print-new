package com.logibit.universalprint

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Driverless PWG Raster path for HP LaserJet Tank MFP 1005.
 *
 * HP lists PWG as a native print language for this model. The stream is a
 * standards-based PWG Raster (RaS2) document, sent raw over the USB printer
 * bulk endpoint. Keep this driver model-scoped; never use it as a generic
 * fallback for unrelated USB printers.
 */
internal object HpTankPwgRasterDriver : UsbPrinterDriver {
    override val driverId = "hp.laserjet-tank-mfp-1005.pwg"
    override val priority = 100

    override fun matches(device: UsbDevice): Boolean =
        PrinterDriverCatalog.find(device)?.id == "hp.laserjet-tank-mfp-1005"

    override fun printPdf(context: Context, usbManager: UsbManager, device: UsbDevice, pdf: ByteArray) {
        val connection = UsbTransport.openWithPermission(context, usbManager, device)
        try {
            val (intf, endpoint) = UsbTransport.findPrinterInterfaceAndBulkOut(device)
            val raster = renderPdfToPwg(context, pdf)
            UsbTransport.sendJob(connection, intf, endpoint, raster)
        } finally {
            connection.close()
        }
    }

    private fun renderPdfToPwg(context: Context, pdf: ByteArray): ByteArray {
        val prefs = context.getSharedPreferences("last_print_settings", Context.MODE_PRIVATE)
        val paperChoice = prefs.getInt("layout_paper", 0)
        val orientation = prefs.getInt("layout_orientation", 0)
        val layoutMode = prefs.getInt("layout_mode", 0)
        val customScale = prefs.getInt("layout_scale", 100).coerceIn(25, 200) / 100f
        val marginChoice = prefs.getInt("layout_margins", 0)
        val file = File.createTempFile("logibit-hp-pwg-", ".pdf", context.cacheDir)
        try {
            FileOutputStream(file).use { it.write(pdf) }
            val out = ByteArrayOutputStream()
            out.write(byteArrayOf(0x52, 0x61, 0x53, 0x32)) // PWG sync: RaS2
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    require(renderer.pageCount in 1..500) { "PDF page count is outside the supported range." }
                    for (index in 0 until renderer.pageCount) {
                        renderer.openPage(index).use { page ->
                            val sourceW = page.width.toFloat()
                            val sourceH = page.height.toFloat()
                            var paperWPt = when (paperChoice) {
                                1 -> 419.53f // A5 short edge, points
                                2 -> 612f // US Letter
                                3 -> sourceW
                                else -> 595.28f // A4
                            }
                            var paperHPt = when (paperChoice) {
                                1 -> 595.28f
                                2 -> 792f
                                3 -> sourceH
                                else -> 841.89f
                            }
                            val landscape = when (orientation) {
                                1 -> false
                                2 -> true
                                else -> sourceW > sourceH
                            }
                            if ((paperWPt > paperHPt) != landscape) {
                                val swap = paperWPt
                                paperWPt = paperHPt
                                paperHPt = swap
                            }
                            val dpi = 300
                            val width = (paperWPt * dpi / 72f).toInt().coerceAtLeast(1)
                            val height = (paperHPt * dpi / 72f).toInt().coerceAtLeast(1)
                            require(width.toLong() * height <= 12_000_000L) {
                                "Selected paper size is too large for safe USB raster printing at 300 dpi."
                            }
                            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                            try {
                                bitmap.eraseColor(Color.WHITE)
                                val marginPt = when (marginChoice) {
                                    1 -> 8f
                                    2 -> 18f
                                    3 -> 36f
                                    else -> 12f
                                }
                                val printableW = (paperWPt - marginPt * 2).coerceAtLeast(1f)
                                val printableH = (paperHPt - marginPt * 2).coerceAtLeast(1f)
                                val sx = printableW / sourceW
                                val sy = printableH / sourceH
                                val scale = when (layoutMode) {
                                    1 -> 1f // Actual size
                                    2 -> maxOf(sx, sy) // Fill page; clip at page bounds
                                    3 -> customScale
                                    else -> minOf(sx, sy) // Fit to page
                                }
                                val drawW = sourceW * scale
                                val drawH = sourceH * scale
                                val leftPt = marginPt + (printableW - drawW) / 2f
                                val topPt = marginPt + (printableH - drawH) / 2f
                                val matrix = Matrix().apply {
                                    setScale(scale * dpi / 72f, scale * dpi / 72f)
                                    postTranslate(leftPt * dpi / 72f, topPt * dpi / 72f)
                                }
                                page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                                writePageHeader(out, width, height, dpi, paperWPt, paperHPt)
                                val row = ByteArray(width)
                                for (y in 0 until height) {
                                    for (x in 0 until width) {
                                        val pixel = bitmap.getPixel(x, y)
                                        row[x] = ((Color.red(pixel) * 299 + Color.green(pixel) * 587 +
                                            Color.blue(pixel) * 114) / 1000).toByte()
                                    }
                                    out.write(0)
                                    writePackBitsGrayRow(out, row)
                                }
                            } finally {
                                bitmap.recycle()
                            }
                        }
                    }
                }
            }
            return out.toByteArray()
        } finally {
            file.delete()
        }
    }

    private fun writePageHeader(out: ByteArrayOutputStream, width: Int, height: Int, dpi: Int, pageWidthPt: Float, pageHeightPt: Float) {
        val h = ByteBuffer.allocate(1796).order(ByteOrder.BIG_ENDIAN)
        putCString(h, 0, 64, "PwgRaster")
        putCString(h, 64, 64, "white")
        putCString(h, 128, 64, "stationery")
        putCString(h, 192, 64, "auto")
        h.putInt(272, 0) // simplex
        h.putInt(276, dpi)
        h.putInt(280, dpi)
        h.putInt(340, 1) // copies
        h.putInt(344, 0) // portrait
        h.putInt(352, pageWidthPt.toInt())
        h.putInt(356, pageHeightPt.toInt())
        h.putInt(372, width)
        h.putInt(376, height)
        h.putInt(384, 8) // bits per color
        h.putInt(388, 8) // bits per pixel
        h.putInt(392, width) // bytes per line
        h.putInt(396, 0) // chunky
        h.putInt(400, 18) // sGray
        h.putInt(420, 1) // number of colors
        // PWG 5102.4 reserves bytes 404-419 and 424-451; leave them zero.
        h.putInt(452 + 4, 1) // CrossFeedTransform
        h.putInt(452 + 8, 1) // FeedTransform
        h.putInt(452 + 7 * 4, 0x00FFFFFF.toInt()) // AlternatePrimary
        h.putInt(452 + 8 * 4, 0) // normal print quality
        putCString(h, 1732, 64, pwgMediaSize(pageWidthPt, pageHeightPt))
        out.write(h.array())
    }

    private fun pwgMediaSize(widthPt: Float, heightPt: Float): String {
        val shortMm = minOf(widthPt, heightPt) * 25.4f / 72f
        val longMm = maxOf(widthPt, heightPt) * 25.4f / 72f
        return if (kotlin.math.abs(shortMm - 148f) < 3f && kotlin.math.abs(longMm - 210f) < 3f) {
            "iso_a5_148x210mm"
        } else if (kotlin.math.abs(shortMm - 210f) < 3f && kotlin.math.abs(longMm - 297f) < 3f) {
            "iso_a4_210x297mm"
        } else {
            // Preserve actual PDF dimensions for other sizes; A5 and A4 are explicitly named.
            "custom_${shortMm.toInt()}x${longMm.toInt()}mm"
        }
    }

    private fun putCString(buffer: ByteBuffer, offset: Int, capacity: Int, value: String) {
        val bytes = value.toByteArray(Charsets.US_ASCII)
        require(bytes.size < capacity)
        for (i in bytes.indices) buffer.put(offset + i, bytes[i])
    }

    private fun writePackBitsGrayRow(out: ByteArrayOutputStream, row: ByteArray) {
        var i = 0
        while (i < row.size) {
            var run = 1
            while (i + run < row.size && row[i + run] == row[i] && run < 128) run++
            if (run >= 2) {
                out.write(run - 1)
                out.write(row[i].toInt() and 0xff)
                i += run
            } else {
                val start = i
                i++
                while (i < row.size && i - start < 128) {
                    var nextRun = 1
                    while (i + nextRun < row.size && row[i + nextRun] == row[i] && nextRun < 2) nextRun++
                    if (nextRun >= 2) break
                    i++
                }
                val count = i - start
                out.write(257 - count)
                out.write(row, start, count)
            }
        }
    }
}
