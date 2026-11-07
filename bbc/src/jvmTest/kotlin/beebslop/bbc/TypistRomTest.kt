// SPDX-FileCopyrightText: 2026 The Beebslop contributors
// SPDX-License-Identifier: BSD-3-Clause

package beebslop.bbc

import beebslop.bbc.KeyboardMatrix.Key
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Types into MOS 1.20 and BASIC II through the keyboard matrix. Skips without the ROMs. */
class TypistRomTest {
    private val romDirectory = File(System.getProperty("beebslop.romDirectory") ?: "roms")

    @Test
    fun typesEveryCharacterTheKeyboardHas() = withMachine { machine, typist ->
        val printable = (' '..'~').filter { it != '`' }.joinToString("") + "£"
        typist.enqueue("10 REM $printable\n")
        runUntilIdle(machine, typist)
        // The line is stored at PAGE (&0E00 without a filing system ROM) as &0D,
        // line number, length, then the text with REM tokenised as &F4.
        val ram = machine.ram
        assertEquals(listOf(0x0D, 0x00, 0x0A), (0..2).map { ram[0x0E00 + it].toInt() and 0xFF })
        val start = (0x0E04 until 0x0E40).first { ram[it].toInt() and 0xFF == 0xF4 } + 1
        val end = (start until 0x0F00).first { ram[it].toInt() == 0x0D }
        val stored = (start until end).map { ram[it].toInt() and 0xFF }
        // The BBC's character set has £ at &60.
        val expected = " $printable".map { if (it == '£') 0x60 else it.code }
        assertEquals(expected.map(::hex), stored.map(::hex))
        assertFalse(machine.latch.capsLockLed, "lower case turned CAPS LOCK off")
    }

    @Test
    fun turnsShiftLockOffBeforeTyping() = withMachine { machine, typist ->
        typist.enqueueChord(listOf(Key.SHIFT_LOCK))
        runUntilIdle(machine, typist)
        assertTrue(machine.latch.shiftLockLed)
        typist.enqueue("P.1")
        runUntilIdle(machine, typist)
        assertFalse(machine.latch.shiftLockLed)
        assertEquals(">P.1", machine.teletextLines()[5])
    }

    @Test
    fun ctrlBreakIsAColdStart() = withMachine { machine, typist ->
        typist.enqueue("10 PRINT\n")
        runUntilIdle(machine, typist)
        typist.enqueueChord(listOf(Key.CTRL), holdFrames = 25) { it.reset() }
        runUntilIdle(machine, typist)
        frames(machine, 50)
        // A cold start shows the memory size, as at power-on; BREAK alone doesn't.
        assertEquals("BBC Computer 32K", machine.teletextLines()[1])
        assertEquals(0xFF, machine.ram[0x0E01].toInt() and 0xFF, "the program is gone")
    }

    @Test
    fun functionKeysTypeTheirDefinitions() = withMachine { machine, typist ->
        typist.enqueue("*KEY0 P.42|M\n")
        runUntilIdle(machine, typist)
        typist.enqueueChord(listOf(KeyboardText.functionKey(0)))
        runUntilIdle(machine, typist)
        frames(machine, 25)
        val screen = machine.teletextLines()
        assertTrue(screen.contains("        42"), screen.joinToString("\n"))
    }

    private fun hex(value: Int) = "&" + value.toString(16).uppercase().padStart(2, '0')

    private fun frames(machine: BbcMachine, count: Int) = machine.runForTicks(count * FRAME_TICKS)

    private fun runUntilIdle(machine: BbcMachine, typist: Typist) {
        var frames = 0
        while (typist.busy) {
            typist.onFrame(machine)
            frames(machine, 1)
            check(++frames < 10_000) { "typing did not finish" }
        }
        frames(machine, 10)
    }

    private fun withMachine(test: (BbcMachine, Typist) -> Unit) {
        val os = File(romDirectory, "os.rom")
        val basic = File(romDirectory, "basic.rom")
        if (!os.isFile || !basic.isFile) {
            println("Skipping: no ROMs in ${romDirectory.absolutePath}")
            return
        }
        val machine = BbcMachine(os.readBytes(), basic.readBytes())
        machine.runForSeconds(2.0)
        test(machine, Typist())
    }

    private companion object {
        const val FRAME_TICKS = BbcClock.TICKS_PER_SECOND / 50L
    }
}
