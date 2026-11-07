// SPDX-FileCopyrightText: 2026 The Beebslop contributors
// SPDX-License-Identifier: BSD-3-Clause

package beebslop.bbc

import kotlin.test.Test
import kotlin.test.assertEquals

/** Whole instructions on the machine, with a synthetic MOS holding a program at &C000. */
class BbcMachineTimingTest {
    private fun machine(vararg program: Int): BbcMachine {
        val os = ByteArray(0x4000)
        program.forEachIndexed { index, byte -> os[index] = byte.toByte() }
        os[0x3FFC] = 0x00 // reset vector &C000
        os[0x3FFD] = 0xC0.toByte()
        return BbcMachine(os, ByteArray(0x4000))
    }

    @Test
    fun resetTakesSevenTicks() {
        val machine = machine()
        assertEquals(7, machine.clock.ticks)
        assertEquals(0xC000, machine.cpu.state().programCounter)
    }

    @Test
    fun absoluteReadOfTheSystemViaIsStretchedByPhase() {
        // LDA &FE40, twice. Reset ends on tick 7.
        val machine = machine(0xAD, 0x40, 0xFE, 0xAD, 0x40, 0xFE)
        // Opcode and operand fetches: ticks 7-10. The VIA read starts on an even tick: 2 ticks.
        assertEquals(5, machine.step())
        // Fetches: ticks 12-15. The VIA read starts on an odd tick: 3 ticks.
        assertEquals(6, machine.step())
        assertEquals(18, machine.clock.ticks)
    }

    @Test
    fun videoUlaAndRomselAreNotStretched() {
        // STA &FE20; STA &FE30
        val machine = machine(0x8D, 0x20, 0xFE, 0x8D, 0x30, 0xFE)
        assertEquals(4, machine.step())
        assertEquals(4, machine.step())
    }

    @Test
    fun readModifyWriteStretchesEachAccessToTheDevice() {
        // INC &FE60 (user VIA): 3 fetches, then read, dummy write, write, each to the VIA.
        val machine = machine(0xEE, 0x60, 0xFE)
        // Fetches: ticks 7-10. Accesses start on even ticks: 2 ticks each.
        assertEquals(3 + 2 + 2 + 2, machine.step())
    }
}
