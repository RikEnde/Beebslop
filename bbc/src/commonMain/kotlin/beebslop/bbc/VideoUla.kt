// SPDX-FileCopyrightText: 2026 The Beebslop contributors
// SPDX-License-Identifier: BSD-3-Clause

package beebslop.bbc

/**
 * The video ULA's two write-only registers (*Advanced User Guide*, chapter 19):
 * the control register at even addresses in `&FE20-&FE2F`, the palette at odd
 * ones. Reads return the bus's absent value. Decoding follows MAME's
 * `bbc_v.cpp`.
 *
 * Control register bits:
 * - 7-5: cursor width, 0, 0, 1, 2, 1, 0, 2 or 4 characters;
 * - 4: the 6845 runs at 2 MHz (modes 0-3) rather than 1 MHz;
 * - 3-2: pixel rate, 2, 4, 8 or 16 MHz;
 * - 1: teletext (MODE 7);
 * - 0: which colour of each flashing pair is shown.
 *
 * Palette writes give a logical colour in bits 4-7 and a physical colour in
 * bits 0-3. The physical colour is stored inverted in bits 0-2 (EOR 7), and bit
 * 3 makes it flash: while control bit 0 is set, a flashing colour shows
 * uninverted, its complement.
 */
class VideoUla : IoDevice {
    var control: Int = 0
        private set

    /** Physical colour for each of the 16 logical colours, as written. */
    val palette = IntArray(16)

    val fastCharacterClock: Boolean get() = control and 0x10 != 0
    val teletext: Boolean get() = control and 0x02 != 0

    /** Pixel clock in MHz: 2, 4, 8 or 16. */
    val pixelRate: Int get() = 2 shl ((control shr 2) and 0x03)

    /** Characters the hardware cursor covers. */
    val cursorWidth: Int get() = CURSOR_WIDTHS[(control shr 5) and 0x07]

    /** The colour shown for [logical], as RGB bits 0 (red), 1 (green) and 2 (blue). */
    fun physical(logical: Int): Int {
        val entry = palette[logical]
        return if (control and 0x01 != 0 && entry and 0x08 != 0) entry and 0x07 else (entry xor 0x07) and 0x07
    }

    override fun write(offset: Int, value: Int) {
        if (offset and 1 == 0) {
            control = value and 0xFF
        } else {
            palette[(value shr 4) and 0x0F] = value and 0x0F
        }
    }

    override fun read(offset: Int): Int = BbcMemoryBus.UNMAPPED_SHEILA
    override fun peek(offset: Int): Int = BbcMemoryBus.UNMAPPED_SHEILA

    companion object {
        private val CURSOR_WIDTHS = intArrayOf(0, 0, 1, 2, 1, 0, 2, 4)
    }
}
