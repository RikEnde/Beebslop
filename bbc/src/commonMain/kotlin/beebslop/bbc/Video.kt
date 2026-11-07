// SPDX-FileCopyrightText: 2026 The Beebslop contributors
// SPDX-License-Identifier: BSD-3-Clause

package beebslop.bbc

/**
 * Builds the picture from the 6845's outputs, as a PAL monitor would see it,
 * one character at a time, so palette and register changes during a frame show
 * where they happen.
 *
 * The picture is [WIDTH] by [HEIGHT] pixels, RGB as `0xRRGGBB`. Horizontally
 * each pixel is 1/16 µs, so a 2 MHz character is 8 pixels and a 1 MHz one 16.
 * The window starts [LEFT_US] µs after the start of horizontal sync. Vertically
 * it starts [TOP_LINE] lines after the start of vertical sync, and the two
 * fields of an interlaced picture are woven together; without interlace each
 * line is drawn twice.
 *
 * - Modes 0-6: each byte is shifted out at the video ULA's pixel rate, with the
 *   logical colour formed from bits 7, 5, 3 and 1 and 1s shifted in. Raster
 *   lines 8-15 of a character row are blank, giving the gaps in modes 3 and 6.
 * - MODE 7: the byte goes to the [Saa5050], whose 12 dots per 1 µs character
 *   are stretched to 16 pixels. Teletext addressing uses MA13. The byte passes
 *   through a latch on the board, which holds it for one character, and the
 *   SAA5050 outputs each character one character after taking it in. So the
 *   picture comes two characters after the 6845's timing. MOS's skew settings
 *   match: display enable 1 (the latch), cursor 2 (the whole path).
 * - The hardware cursor inverts the colours of [VideoUla.cursorWidth]
 *   characters, starting where the 6845's cursor signal arrives.
 *
 * This follows MAME's `bbc_v.cpp`.
 */
class Video(
    private val ram: ByteArray,
    private val latch: AddressableLatch,
    private val ula: VideoUla,
    private val crtc: Crtc6845,
    private val clock: BbcClock,
    private val onVerticalSync: (Boolean) -> Unit,
) : CrtcOutput {
    val teletext = Saa5050()

    /** The picture, updated as it is drawn. */
    val pixels = IntArray(WIDTH * HEIGHT)

    /** Fields completed (one per vertical sync). */
    var fields: Long = 0
        private set

    private var lineStartTick = 0L
    private var line = 0
    private var displayWasEnabled = false
    private var cursorRemaining = 0
    private var teletextLatch = 0

    override fun horizontalSync() {
        lineStartTick = clock.ticks
        line++
        displayWasEnabled = false
    }

    override fun verticalSync(active: Boolean) {
        onVerticalSync(active)
        if (active) {
            teletext.verticalSync()
            line = 0
            fields++
        }
    }

    override fun character(ma: Int, ra: Int, displayEnabled: Boolean, cursor: Boolean) {
        if (cursor) cursorRemaining = ula.cursorWidth
        val invert = if (cursorRemaining > 0) 0x07 else 0
        if (cursorRemaining > 0) cursorRemaining--

        val teletextMode = ula.teletext
        if (teletextMode) {
            // The SAA5050 takes the byte latched one character ago, with display
            // enable already delayed by the 6845's skew to match.
            val code = teletextLatch
            teletextLatch = if (ma and 0x2000 != 0) ram[ScreenAddress.of(ma, ra, latch.screenWrap) and 0x7FFF].toInt() else 0
            if (displayEnabled) {
                if (!displayWasEnabled) teletext.lineStart()
                teletext.field = ra and 1
                teletext.character(code)
            }
        }
        displayWasEnabled = displayEnabled

        val fieldLine = line - TOP_LINE
        if (fieldLine !in 0 until FIELD_LINES) return
        val width = if (ula.fastCharacterClock) 8 else 16
        val x = ((clock.ticks - lineStartTick) * 8).toInt() - LEFT_US * 16
        if (x + width * 2 <= 0 || x >= WIDTH) return

        val interlaced = crtc.register(8) and 0x01 != 0
        val y = if (interlaced) fieldLine * 2 + crtc.field else fieldLine * 2
        val rows = if (interlaced) 1 else 2

        when {
            teletextMode -> {
                // The cursor inverts what is on screen now: the character the
                // SAA5050 output last time. This one appears a character later.
                if (invert != 0) invertPixels(x, y, rows, width)
                val dots = teletext.dots
                val fg = COLOURS[teletext.foreground]
                val bg = COLOURS[teletext.background]
                if (!displayEnabled) {
                    fill(x + width, y, rows, width) { COLOURS[0] }
                } else {
                    fill(x + width, y, rows, width) { i -> if (dots and (0x800 shr (i * 12 / width)) != 0) fg else bg }
                }
            }
            !displayEnabled -> fill(x, y, rows, width) { COLOURS[0] }
            else -> {
                var data = ram[ScreenAddress.of(ma, ra, latch.screenWrap) and 0x7FFF].toInt() and 0xFF
                val pixelWidth = 16 / ula.pixelRate
                val count = width / pixelWidth
                for (p in 0 until count) {
                    val logical = ((data shr 4) and 0x08) or ((data shr 3) and 0x04) or ((data shr 2) and 0x02) or ((data shr 1) and 0x01)
                    val colour = COLOURS[(if (ra and 0x08 != 0) 0 else ula.physical(logical)) xor invert]
                    fill(x + p * pixelWidth, y, rows, pixelWidth) { colour }
                    data = ((data shl 1) or 1) and 0xFF
                }
            }
        }
    }

    private fun invertPixels(x: Int, y: Int, rows: Int, width: Int) {
        for (px in maxOf(x, 0) until minOf(x + width, WIDTH)) {
            for (r in 0 until rows) pixels[(y + r) * WIDTH + px] = pixels[(y + r) * WIDTH + px] xor 0xFFFFFF
        }
    }

    private inline fun fill(x: Int, y: Int, rows: Int, width: Int, colour: (Int) -> Int) {
        for (i in 0 until width) {
            val px = x + i
            if (px !in 0 until WIDTH) continue
            val c = colour(i)
            for (r in 0 until rows) pixels[(y + r) * WIDTH + px] = c
        }
    }

    companion object {
        const val WIDTH = 768
        const val FIELD_LINES = 288
        const val HEIGHT = FIELD_LINES * 2
        const val LEFT_US = 10
        const val TOP_LINE = 22

        /** Physical colours 0-7 as RGB: bit 0 red, bit 1 green, bit 2 blue. */
        val COLOURS = IntArray(8) { c ->
            (if (c and 1 != 0) 0xFF0000 else 0) or (if (c and 2 != 0) 0x00FF00 else 0) or (if (c and 4 != 0) 0x0000FF else 0)
        }
    }
}
