package dev.mrkcnd.zill

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import dev.mrkcnd.zill.protocol.Base64Url
import dev.mrkcnd.zill.protocol.MacId
import dev.mrkcnd.zill.protocol.PairLink
import dev.mrkcnd.zill.protocol.sha256
import dev.mrkcnd.zill.scan.QrDecode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class QrDecodeTest {
    private val fp = sha256("example".toByteArray())
    private val link = "sill://pair?v=1&m=${MacId.make(fp)}&k=${Base64Url.encode(fp)}&s=${Base64Url.encode(ByteArray(16) { 0x22 })}" +
        "&n=Mac%20mini&p=7455&a=192.168.1.20&a=home.example.com%3A7455"

    /** The code drawn into a grey frame like a camera's Y plane, with padding at the end of each row. */
    private fun frame(text: String, width: Int, height: Int, stride: Int, scale: Int): ByteArray {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0,
            mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 4))
        val y = ByteArray(stride * height) { 0x70 }
        val left = (width - m.width * scale) / 2
        val top = (height - m.height * scale) / 2
        for (r in 0 until m.height * scale) for (c in 0 until m.width * scale) {
            y[(top + r) * stride + left + c] = if (m[c / scale, r / scale]) 0x18 else 0xE8.toByte()
        }
        return y
    }

    @Test fun reads_the_pairing_link() {
        val text = QrDecode.decode(frame(link, 640, 480, 704, 6), 640, 480, 704)
        assertEquals(link, text)
        val parsed = PairLink.parse(text!!)
        assertNotNull(parsed)
        assertEquals("Mac mini", parsed!!.name)
        assertEquals(listOf("192.168.1.20", "home.example.com:7455"), parsed.addresses)
    }

    @Test fun no_code_in_the_frame() {
        assertNull(QrDecode.decode(ByteArray(640 * 480) { 0x70 }, 640, 480))
    }

    @Test fun other_qr_codes_are_not_links() {
        val text = QrDecode.decode(frame("https://example.com", 640, 480, 640, 8), 640, 480)
        assertEquals("https://example.com", text)
        assertNull(PairLink.parse(text!!))
    }
}
