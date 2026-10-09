package cn.huohuas001.virga.server.connect

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import cn.huohuas001.virga.server.game.GameMapCanvas
import cn.huohuas001.virga.server.game.MapTone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QrMapImageTest {
    private val size = GameMapCanvas.SIZE

    @Test
    fun `connect url drawn on one map decodes back`() {
        // Same shape as QqBotQrConnector.connectUrl with a long task id.
        val url = "https://q.qq.com/qqbot/openclaw/connect.html?task_id=" + "a1B2c3D4e5".repeat(4) + "&source=Virga&_wv=2"
        val pixels = QrMapImage.render(url)
        assertEquals(size * size, pixels.size)
        assertEquals(url, decode(pixels))
    }

    @Test
    fun `code keeps a light quiet zone on every side`() {
        val pixels = QrMapImage.render("https://q.qq.com/qqbot/openclaw/connect.html?task_id=abc&source=Virga&_wv=2")
        for (i in 0 until size) for (edge in 1..2) {
            assertTrue(pixels[edge * size + i] != MapTone.INK)
            assertTrue(pixels[(size - 1 - edge) * size + i] != MapTone.INK)
            assertTrue(pixels[i * size + edge] != MapTone.INK)
            assertTrue(pixels[i * size + size - 1 - edge] != MapTone.INK)
        }
    }

    @Test
    fun `menu descriptions wrap into short lore lines`() {
        val lines = SettingsMenu.wrap("默认关闭；豁免名单可免绑定入服，机器人不可用时临时放行；豁免名单在配置文件中设置")
        assertTrue(lines.size > 1)
        assertTrue(lines.all { it.length <= 18 })
        assertEquals("默认关闭；豁免名单可免绑定入服，机器人不可用时临时放行；豁免名单在配置文件中设置", lines.joinToString(""))
    }

    private fun decode(pixels: Array<MapTone>): String {
        // Scale up like a phone camera would see it; map pixels are big squares on screen.
        val zoom = 4
        val width = size * zoom
        val argb = IntArray(width * width) { index ->
            val tone = pixels[(index / width / zoom) * size + (index % width) / zoom]
            if (tone == MapTone.INK) 0xFF191919.toInt() else if (tone == MapTone.PAPER) 0xFFFFFFFF.toInt() else 0xFFC9BEF2.toInt()
        }
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(width, width, argb)))
        return QRCodeReader().decode(bitmap, mapOf(DecodeHintType.TRY_HARDER to true)).text
    }
}
