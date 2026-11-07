// SPDX-FileCopyrightText: 2026 The Beebslop contributors
// SPDX-License-Identifier: BSD-3-Clause

package beebslop.bbc

/**
 * Turns a 6845 memory address (MA) and raster address (RA) into a RAM address,
 * as the Model B's address logic does. This follows MAME's `bbc_v.cpp`,
 * `calculate_video_address`, which describes the gates on the board.
 *
 * When MA12 is set, a 4-bit adder (IC39) adds a constant to MA8-MA11 so that
 * screen addresses past `&7FFF` wrap back to the start of screen memory. The
 * constant depends on the screen size in addressable latch bits C0 and C1
 * (*Advanced User Guide*, 23.2).
 *
 * MA13 selects teletext addressing: the 1 KiB of MODE 7 screen at `&7C00`, or
 * `&3C00` when the adder's top bit is clear.
 */
object ScreenAddress {
    fun of(ma: Int, ra: Int, screenWrap: Int): Int {
        val c0 = screenWrap and 1
        val c1 = (screenWrap shr 1) and 1
        val ma12 = (ma shr 12) and 1
        val b1 = (c1 and c0 and ma12).inv() and 1
        val b3 = (c0 and ma12).inv() and 1
        val b2 = (c1 and b3 and ma12).inv() and 1
        val b4 = (b3 and ma12).inv() and 1
        val b = b1 or (b2 shl 1) or (b3 shl 2) or (b4 shl 3)
        val sum = (((ma shr 8) and 0x0F) + b + 1) and 0x0F
        return if (ma and 0x2000 != 0) {
            (ma and 0x3FF) or 0x3C00 or ((sum and 0x8) shl 11)
        } else {
            ((ma and 0xFF) shl 3) or (sum shl 11) or (ra and 0x07)
        }
    }
}
