// SPDX-FileCopyrightText: 2026 The Beebslop contributors
// SPDX-License-Identifier: BSD-3-Clause

package beebslop.bbc

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** SAVE and LOAD through MOS 1.20's cassette filing system. Skips without the ROMs. */
class CassetteRomTest {
    private val romDirectory = File(System.getProperty("beebslop.romDirectory") ?: "roms")

    @Test
    fun savesToTapeAndLoadsBackFromAUefImage() {
        val tape = Tape.blank()
        boot { machine, typist ->
            machine.serialUla.tape = tape
            typist.type(machine, "10 PRINT \"HELLO FROM TAPE\"\n20 GOTO 10\nSAVE \"PROG\"\n")
            assertEquals("RECORD then RETURN", machine.teletextLines().last { it.isNotEmpty() })
            assertTrue(machine.serialUla.motorOn, "MOS starts the motor")
            typist.type(machine, "\n")
            waitForMotor(machine)
            assertEquals("PROG       00 0025", machine.teletextLines().filter { it.isNotEmpty() }.dropLast(1).last())
        }
        // About 5 s of leader, then one block of 37 bytes of program at 1200 baud.
        assertTrue(tape.seconds in 10.0..12.0, "${tape.seconds} s of tape")
        val image = tape.toUef()

        boot { machine, typist ->
            machine.serialUla.tape = Tape.parse(image)
            typist.type(machine, "LOAD \"PROG\"\n")
            val seconds = waitForMotor(machine)
            assertTrue(seconds in 4..10, "loaded in $seconds s")
            typist.type(machine, "LIST\n")
            val screen = machine.teletextLines().filter { it.isNotEmpty() }
            assertEquals(listOf("Loading", "PROG       00 0025", ">LIST", "   10 PRINT \"HELLO FROM TAPE\"", "   20 GOTO 10", ">"), screen.takeLast(6), screen.joinToString("\n"))
        }
    }

    @Test
    fun loadReportsADataErrorOnADamagedTape() {
        val tape = Tape.blank()
        boot { machine, typist ->
            machine.serialUla.tape = tape
            typist.type(machine, "10 PRINT\nSAVE \"P\"\n\n")
            waitForMotor(machine)
        }
        // Flip one cell among the block's last bytes: the program and its checksum.
        val damaged = Tape.blank()
        val middle = (0 until tape.length).last { tape[it] == TapeBit.ZERO } - 25
        for (i in 0 until tape.length) {
            val cell = tape[i]
            damaged.writeBit(if (i == middle) (if (cell == TapeBit.ONE) TapeBit.ZERO else TapeBit.ONE) else cell)
        }
        damaged.rewind()
        boot { machine, typist ->
            machine.serialUla.tape = damaged
            typist.type(machine, "LOAD \"P\"\n")
            machine.runForSeconds(15.0)
            val screen = machine.teletextLines().joinToString("\n")
            assertTrue("Data?" in screen, screen)
        }
    }

    /** Runs until the motor stops; returns the seconds it took. */
    private fun waitForMotor(machine: BbcMachine): Int {
        var seconds = 0
        while (machine.serialUla.motorOn) {
            machine.runForSeconds(1.0)
            check(++seconds < 60) { "the motor kept running" }
        }
        assertFalse(machine.serialUla.motorOn)
        return seconds
    }

    private fun Typist.type(machine: BbcMachine, text: String) {
        enqueue(text)
        var frames = 0
        while (busy || frames < 20) {
            onFrame(machine)
            machine.runForTicks(BbcClock.TICKS_PER_SECOND / 50L)
            frames++
        }
    }

    private fun boot(test: (BbcMachine, Typist) -> Unit) {
        val os = File(romDirectory, "os.rom")
        val basic = File(romDirectory, "basic.rom")
        if (!os.isFile || !basic.isFile) return println("Skipping: no ROMs in ${romDirectory.absolutePath}")
        val machine = BbcMachine(os.readBytes(), basic.readBytes())
        machine.runForSeconds(2.0)
        test(machine, Typist())
    }
}
