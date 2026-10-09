package cn.huohuas001.virga.server.connect

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import cn.huohuas001.virga.server.game.GameMapCanvas
import cn.huohuas001.virga.server.game.MapTone

/**
 * Draws a QR code onto one 128×128 map: whole pixels per module (no blurry scaling), centred on
 * white paper with the largest quiet zone that still fits, and a thin lavender frame when there is room.
 */
object QrMapImage {
    private const val SIZE = GameMapCanvas.SIZE
    /** Scanners need a light margin; two map pixels is the least that still scans reliably. */
    private const val MIN_QUIET = 2

    fun render(text: String): Array<MapTone> {
        // Level L keeps the long connect URL at the smallest version, so modules can be 3 px wide.
        val matrix = QRCodeWriter().encode(
            text, BarcodeFormat.QR_CODE, 0, 0,
            mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L)
        )
        val modules = matrix.width
        val scale = (SIZE - MIN_QUIET * 2) / modules
        require(scale >= 1) { "二维码太大，一张地图放不下" }
        val offset = (SIZE - modules * scale) / 2
        val pixels = Array(SIZE * SIZE) { MapTone.PAPER }
        for (y in 0 until modules) for (x in 0 until modules) {
            if (!matrix.get(x, y)) continue
            for (dy in 0 until scale) for (dx in 0 until scale) {
                pixels[(offset + y * scale + dy) * SIZE + offset + x * scale + dx] = MapTone.INK
            }
        }
        // A lavender edge only where it stays clear of the code's own quiet zone.
        if (offset >= MIN_QUIET + 2) {
            for (i in 0 until SIZE) {
                pixels[i] = MapTone.ACCENT
                pixels[(SIZE - 1) * SIZE + i] = MapTone.ACCENT
                pixels[i * SIZE] = MapTone.ACCENT
                pixels[i * SIZE + SIZE - 1] = MapTone.ACCENT
            }
        }
        return pixels
    }
}
