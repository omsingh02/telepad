package com.omsingh.telepad.core.wifi

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.ChecksumException
import com.google.zxing.DecodeHintType
import com.google.zxing.FormatException
import com.google.zxing.LuminanceSource
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/**
 * Reads a QR code out of a camera frame.
 *
 * It works on the frame's brightness values alone, which is what a camera hands over first and
 * all a QR code needs, and nothing here touches Android, so it is tested with pictures of codes.
 *
 * A code is dark squares on a light ground. A terminal with light text on a dark background draws
 * it the other way round, and a scanner that only knows the usual way never finds it, so every frame
 * is tried both ways.
 */
class QrDecoder {

    private val reader = QRCodeReader()
    private val hints = mapOf(
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
    )

    /**
     * The text of the QR code in the frame, or null if there is none.
     *
     * @param luminance one byte of brightness per pixel, [rowStride] bytes per row. A camera's rows are
     *        often padded, which is why the stride is not always the [width].
     */
    fun decode(luminance: ByteArray, width: Int, height: Int, rowStride: Int = width): String? {
        if (width <= 0 || height <= 0 || rowStride < width || luminance.size < rowStride * (height - 1) + width) return null
        val source = PlanarYUVLuminanceSource(luminance, rowStride, height, 0, 0, width, height, false)
        return read(source) ?: read(source.invert())
    }

    private fun read(source: LuminanceSource): String? = try {
        reader.decode(BinaryBitmap(HybridBinarizer(source)), hints).text
    } catch (_: NotFoundException) {
        null
    } catch (_: ChecksumException) {
        null
    } catch (_: FormatException) {
        null
    } finally {
        reader.reset()
    }
}
