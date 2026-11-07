// SPDX-FileCopyrightText: 2026 The Beebslop contributors
// SPDX-License-Identifier: BSD-3-Clause

package beebslop.bbc

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Acorn DFS 1.20 (DNFS) running the 8271, with the discs in `discs/`. Skips
 * without the ROMs.
 */
class DiscRomTest {
    private val romDirectory = File(System.getProperty("beebslop.romDirectory") ?: "roms")
    private val discDirectory = File(System.getProperty("beebslop.discDirectory") ?: "discs")

    private fun welcome() = Disc.parse(File(discDirectory, "welcome.ssd").readBytes(), 1)

    @Test
    fun bootsWithTheDiscFilingSystem() = boot(Disc.blank(1)) { machine, _ ->
        assertEquals(listOf("BBC Computer 32K", "Acorn DFS", "BASIC", ">"), machine.screen())
        assertEquals(0x19, machine.ram[0x18].toInt(), "PAGE is &1900")
    }

    @Test
    fun cataloguesTheWelcomeDisc() = boot(welcome()) { machine, typist ->
        typist.type(machine, "*CAT\n")
        machine.runForSeconds(2.0)
        // The listing scrolls the banner off the top of the screen.
        val screen = machine.screen()
        val title = screen.indexOf("WELCOME-DISK (04)")
        assertTrue(title >= 0, screen.joinToString("\n"))
        assertEquals("Drive 0             Option 3 (EXEC)", screen[title + 1])
        assertEquals("    !B                  !BOOT", screen[title + 3])
        assertEquals("  W.SKETCH            W.WELCOME", screen[title + 15])
        assertEquals(">", screen.last())
    }

    @Test
    fun shiftBreakBootsTheWelcomeDisc() = boot(welcome()) { machine, typist ->
        typist.enqueueChord(listOf(KeyboardMatrix.Key.SHIFT), holdFrames = 25) { it.reset() }
        typist.type(machine, "")
        machine.runForSeconds(8.0)
        val screen = machine.screen()
        assertEquals("Hello, this is the BBC Microcomputer.", screen.first(), screen.joinToString("\n"))
        assertEquals("     Press  SPACE  to see them", screen.last())
    }

    @Test
    fun savesAndLoadsThroughADiscImage() {
        val disc = Disc.blank(1)
        boot(disc) { machine, typist ->
            typist.type(machine, "10 PRINT \"HELLO FROM DISC\"\nSAVE \"PROG\"\n")
            machine.runForSeconds(2.0)
        }
        val image = disc.toImage()
        boot(Disc.parse(image, 1)) { machine, typist ->
            typist.type(machine, "LOAD \"PROG\"\nLIST\n*INFO PROG\n")
            machine.runForSeconds(2.0)
            val screen = machine.screen()
            assertEquals("   10 PRINT \"HELLO FROM DISC\"", screen[screen.indexOf(">LIST") + 1])
            // Saved from PAGE (&1900), 26 bytes long, at sector 2.
            assertEquals("\$.PROG        FF1900 FF8023 00001A 002", screen[screen.indexOf(">*INFO PROG") + 1])
        }
    }

    @Test
    fun theSecondSideIsDriveTwo() {
        val disc = Disc.blank(2)
        boot(disc) { machine, typist ->
            typist.type(machine, "10 REM\nSAVE \":2.BACK\"\n")
            machine.runForSeconds(2.0)
        }
        val catalogue = disc.sector(1, 0, 0)!!.data
        assertEquals("BACK   $", String(catalogue, 8, 8, Charsets.ISO_8859_1))
        assertEquals(0, disc.sector(0, 0, 1)!!.data[5].toInt(), "side 0 still has no files")
    }

    @Test
    fun formatsAndVerifiesADiscWithAcornsUtilities() {
        val unformatted = Disc.parse(ByteArray(512), 1)
        boot(welcome(), unformatted) { machine, typist ->
            // Each utility empties the keyboard buffer before it asks, so wait for every prompt.
            fun answer(prompt: String, seconds: Double, text: String) {
                machine.runUntil(seconds) { prompt in it.screen().last() }
                typist.type(machine, text)
            }
            typist.type(machine, "*FORM40\n")
            answer("Format which drive ?", 5.0, "1")
            answer("Do you really want to format drive 1 ?", 5.0, "Y")
            answer("Disk formatted - repeat (Y/N) ?", 60.0, "N")
            typist.type(machine, "*DRIVE 1\n*VERIFY\n")
            answer("Press 'Y' to verify drive 1 :", 5.0, "Y")
            answer("Disk verified - repeat (Y/N) ?", 60.0, "N")
            typist.type(machine, "*CAT\n")
            machine.runForSeconds(2.0)
            assertEquals("Drive 1             Option 0 (off)", machine.screen().dropLast(2).last())
        }
        assertEquals(40 * 10, (0 until 80).sumOf { unformatted.track(0, it)!!.sectors.size })
    }

    @Test
    fun aWriteProtectedDiscIsReadOnly() {
        val disc = Disc.blank(1).apply { writeProtected = true }
        boot(disc) { machine, typist ->
            typist.type(machine, "SAVE \"P\"\n")
            machine.runForSeconds(2.0)
            assertEquals("Disk read only", machine.screen().dropLast(1).last())
        }
    }

    @Test
    fun anEmptyDriveWaitsUntilEscape() = boot(Disc.blank(1)) { machine, typist ->
        typist.type(machine, "*CAT 1\n")
        machine.runForSeconds(3.0)
        assertEquals(">*CAT 1", machine.screen().last(), "still waiting")
        typist.enqueueChord(listOf(KeyboardMatrix.Key.ESCAPE))
        typist.type(machine, "")
        assertEquals(listOf("Escape", ">"), machine.screen().takeLast(2))
    }

    private fun BbcMachine.screen() = teletextLines().filter { it.isNotEmpty() }

    private fun BbcMachine.runUntil(seconds: Double, done: (BbcMachine) -> Boolean) {
        var elapsed = 0.0
        while (!done(this)) {
            runForSeconds(0.5)
            elapsed += 0.5
            check(elapsed < seconds) { "timed out:\n" + screen().joinToString("\n") }
        }
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

    private fun boot(drive0: Disc?, drive1: Disc? = null, test: (BbcMachine, Typist) -> Unit) {
        val os = File(romDirectory, "os.rom")
        val basic = File(romDirectory, "basic.rom")
        val dfs = File(romDirectory, "dnfs.rom")
        if (!os.isFile || !basic.isFile || !dfs.isFile) return println("Skipping: no ROMs in ${romDirectory.absolutePath}")
        val machine = BbcMachine(os.readBytes(), basic.readBytes(), dfs.readBytes())
        machine.fdc!!.drives[0].disc = drive0
        machine.fdc.drives[1].disc = drive1
        machine.runForSeconds(2.0)
        test(machine, Typist())
    }
}
