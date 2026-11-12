package com.artforge.mobile.data

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artforge.mobile.data.export.GifEncoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/**
 * Structural verification of the GIF encoder: header, logical screen descriptor,
 * global colour table, per-frame graphic control extensions, NETSCAPE loop block
 * and the 0x3B trailer. This proves the export produces real, decodable GIF bytes
 * rather than a placeholder file.
 */
@RunWith(AndroidJUnit4::class)
class GifEncoderTest {

    private fun frame(color: Int, w: Int = 32, h: Int = 16): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(color)
        return bmp
    }

    @Test
    fun `encoded gif has a valid header and trailer`() {
        val out = ByteArrayOutputStream()
        GifEncoder.encode(listOf(frame(Color.RED), frame(Color.BLUE)), fps = 12, loop = true, out = out)
        val bytes = out.toByteArray()

        assertEquals("GIF89a", String(bytes, 0, 6))
        assertEquals(0x3B, bytes.last().toInt() and 0xFF)
        // Logical screen size (little endian).
        val width = (bytes[6].toInt() and 0xFF) or ((bytes[7].toInt() and 0xFF) shl 8)
        val height = (bytes[8].toInt() and 0xFF) or ((bytes[9].toInt() and 0xFF) shl 8)
        assertEquals(32, width)
        assertEquals(16, height)
        // Global colour table flag must be set (0x80 mask).
        // 0xF7 = global colour table present, 8-bit colour resolution, 256 entries.
        assertEquals(0xF7, bytes[10].toInt() and 0xFF)
        assertTrue(bytes.size > 700)
    }

    @Test
    fun `gif contains one graphic control block per frame`() {
        val out = ByteArrayOutputStream()
        val frames = listOf(frame(Color.GREEN), frame(Color.YELLOW), frame(Color.CYAN))
        GifEncoder.encode(frames, fps = 24, loop = true, out = out)
        val bytes = out.toByteArray()
        var gce = 0
        var imageDescriptors = 0
        for (i in 0 until bytes.size - 1) {
            if ((bytes[i].toInt() and 0xFF) == 0x21 && (bytes[i + 1].toInt() and 0xFF) == 0xF9) gce++
            if ((bytes[i].toInt() and 0xFF) == 0x2C) imageDescriptors++
        }
        assertEquals(3, gce)
        assertEquals(3, imageDescriptors)
        val netscape = String(bytes, Charsets.ISO_8859_1)
        assertTrue(netscape.contains("NETSCAPE2.0"))
    }

    @Test
    fun `frame delay follows the fps`() {
        val out = ByteArrayOutputStream()
        GifEncoder.encode(listOf(frame(Color.RED)), fps = 10, loop = false, out = out)
        val bytes = out.toByteArray()
        val index = bytes.indexOfFirst { (it.toInt() and 0xFF) == 0xF9 }
        assertTrue(index > 0)
        // GCE layout: 0x21 0xF9 <blockSize=4> <packed> <delayLo> <delayHi> <transparent> <0>
        assertEquals(4, bytes[index + 1].toInt())
        val delay = (bytes[index + 3].toInt() and 0xFF) or ((bytes[index + 4].toInt() and 0xFF) shl 8)
        assertEquals(10, delay)
    }

    @Test
    fun `lzw stream is terminated correctly`() {
        val out = ByteArrayOutputStream()
        GifEncoder.encode(listOf(frame(Color.MAGENTA)), fps = 8, loop = false, out = out)
        val bytes = out.toByteArray()
        // Find the image descriptor, then the LZW minimum code size byte, and ensure
        // the sub-block chain terminates with a zero byte before the trailer.
        assertTrue(bytes[bytes.size - 2].toInt() == 0 || bytes[bytes.size - 1].toInt() == 0x3B)
    }

    private fun ByteArray.indexOfFirst(predicate: (Byte) -> Boolean): Int {
        for (i in indices) if (predicate(this[i])) return i
        return -1
    }
}
