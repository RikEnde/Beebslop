// SPDX-FileCopyrightText: 2026 The Elkslop contributors
// SPDX-FileCopyrightText: 2026 The Beebslop contributors
// SPDX-License-Identifier: BSD-3-Clause

package beebslop.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DisassembleOptionsTest {
    @Test
    fun parsesRangesAndOrigin() {
        assertEquals(0xFFE3..0xFFF9, Options.parse(arrayOf("--disassemble", "FFE3-FFF9")).disassemble)
        assertEquals(0xFFEE..0xFFEE, Options.parse(arrayOf("--disassemble", "\$FFEE")).disassemble)
        assertEquals(0xFFEE..0xFFF0, Options.parse(arrayOf("--disassemble", "&FFEE-&FFF0")).disassemble)
        assertFailsWith<IllegalArgumentException> { Options.parse(arrayOf("--disassemble", "FFFF-0000")) }
        assertFailsWith<IllegalArgumentException> { Options.parse(arrayOf("--disassemble", "G000")) }
        assertEquals(0x1900, Options.parse(arrayOf("--disassemble", "3000-30FF", "--origin", "1900")).origin)
        assertFailsWith<IllegalArgumentException> { Options.parse(arrayOf("--origin", "1900")) }
        assertFailsWith<IllegalArgumentException> { Options.parse(arrayOf("--disassemble", "3000", "--origin", "10000")) }
        assertNull(Options.parse(arrayOf()).disassemble)
    }

    @Test
    fun disassemblesTheOs() {
        val machine = runHeadless(Roms.load(null), Options(screenshotFrames = 1))
        // OSWRCH, in the OS's jump table, goes through the vector WRCHV at $020E.
        assertEquals("FFEE  6C 0E 02  JMP (\$020E)", disassemble(machine, 0xFFEE..0xFFEE))
    }

    @Test
    fun runsUntilTheTextIsTyped() {
        // ?&70 then holds what was typed into it: &EA, the opcode for NOP.
        val machine = runHeadless(Roms.load(null), Options(screenshotFrames = 1, typeText = "?&70=&EA\n"))
        assertEquals(0xEA, machine.bus.peek(0x70))
        assertTrue(disassemble(machine, 0x70..0x70).endsWith("NOP"))
    }
}
