// SPDX-FileCopyrightText: 2026 The Beebslop contributors
// SPDX-License-Identifier: BSD-3-Clause

package beebslop.bbc

import kotlin.test.Test
import kotlin.test.assertEquals

class Saa5050Test {
    /** Dots for one 20-line character cell, after the given codes on the same line. */
    private fun cell(vararg codes: Int): List<Int> {
        val chip = Saa5050()
        chip.verticalSync()
        return (0 until 20).map { line ->
            if (line and 1 == 0) chip.lineStart()
            chip.field = line and 1
            codes.forEach { chip.character(it) }
            chip.dots
        }
    }

    @Test
    fun letterAWithCharacterRounding() {
        val a = cell('A'.code)
        assertEquals(0, a[0], "the cell's top row is blank")
        assertEquals(0, a[1])
        // Bedstead's A starts with a single dot (&04), doubled to &030.
        assertEquals(0x030, a[2])
        // On the second line, rounding adds half dots towards the next row (&0A).
        assertEquals(0x078, a[3])
        assertEquals(0, a[18], "no descender")
    }

    @Test
    fun mosaicBlocksAndSeparatedGraphics() {
        val solid = cell(0x17, 0x7F) // graphics white, all six blocks
        assertEquals(List(20) { 0xFFF }, solid)
        val separated = cell(0x17, 0x1A, 0x7F)
        assertEquals(0x3CF, separated[0])
        assertEquals(0, separated[4], "gap below the top blocks")
        assertEquals(0, separated[19], "gap below the bottom blocks")
    }

    @Test
    fun colourChangesTakeEffectAfterTheControlCode() {
        val chip = Saa5050()
        chip.lineStart()
        chip.character(0x01) // alphanumeric red
        assertEquals(7, chip.foreground, "the control code itself is still white")
        chip.character('X'.code)
        assertEquals(1, chip.foreground)
        chip.character(0x1D) // new background: set at once
        assertEquals(1, chip.background)
        chip.lineStart()
        chip.character('X'.code)
        assertEquals(7, chip.foreground, "attributes reset each line")
        assertEquals(0, chip.background)
    }

    @Test
    fun heldGraphicsRepeatOverControlCodes() {
        val chip = Saa5050()
        chip.lineStart()
        chip.character(0x17) // graphics white
        chip.character(0x1E) // hold graphics
        chip.character(0x7F) // solid block
        chip.character(0x11) // graphics red: shows the held block
        assertEquals(0xFFF, chip.dots)
    }
}
