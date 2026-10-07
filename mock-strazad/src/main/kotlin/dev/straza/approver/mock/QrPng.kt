package dev.straza.approver.mock

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/** Renders the enrollment payload as a QR PNG. zxing encodes; JDK ImageIO writes the image. */
object QrPng {

    fun write(payload: String, out: File, sizePx: Int = 640) {
        out.outputStream().use { ImageIO.write(image(payload, sizePx), "png", it) }
    }

    /** The same PNG as bytes, for the review page. */
    fun render(payload: String, sizePx: Int = 640): ByteArray {
        val buffer = java.io.ByteArrayOutputStream()
        ImageIO.write(image(payload, sizePx), "png", buffer)
        return buffer.toByteArray()
    }

    private fun image(payload: String, sizePx: Int): BufferedImage {
        val hints = mapOf(
            EncodeHintType.MARGIN to 2,
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        )
        val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)

        val image = BufferedImage(matrix.width, matrix.height, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until matrix.width) {
            for (y in 0 until matrix.height) {
                image.setRGB(x, y, if (matrix.get(x, y)) 0x000000 else 0xFFFFFF)
            }
        }
        return image
    }
}
