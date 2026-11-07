// SPDX-FileCopyrightText: 2026 The Beebslop contributors
// SPDX-License-Identifier: BSD-3-Clause

package beebslop.bbc

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs the real MOS 1.20 and BASIC II from roms/. The tests skip, with a
 * message, if the images are missing.
 */
class RomResetTest {
    private val romDirectory = File(System.getProperty("beebslop.romDirectory") ?: "roms")
    private val os = File(romDirectory, "os.rom")
    private val basic = File(romDirectory, "basic.rom")

    @Test
    fun powerOnReadsTheSystemViaInterruptEnableRegisterWithAStretchedCycle() = withMachine { machine ->
        assertEquals(0xD9CD, machine.cpu.state().programCounter)
        // LDA #&40, STA &0D00, SEI, CLD, LDX #&FF, TXS: 14 ticks after the 7 of reset.
        repeat(6) { machine.step() }
        assertEquals(21, machine.clock.ticks)
        assertEquals(0x40, machine.ram[0x0D00].toInt(), "RTI placed at the NMI routine")
        // LDA &FE4E: three fetches from tick 21, then the VIA read from tick 24, an edge.
        assertEquals(5, machine.step())
        // A freshly reset VIA has no interrupts enabled; bit 7 reads as 1.
        assertEquals(0x80, machine.cpu.state().accumulator)
    }

    @Test
    fun bootsToTheBasicPromptInMode7() = withMachine { machine ->
        machine.runForSeconds(2.0)
        val screen = machine.teletextLines()
        val text = screen.joinToString("\n")
        // The MOS prints LF CR before the banner, so row 0 stays blank.
        assertEquals("", screen[0], text)
        assertEquals("BBC Computer 32K", screen[1], text)
        assertEquals("BASIC", screen[3], text)
        assertEquals(">", screen[5], text)
        assertTrue(screen.drop(6).all { it.isEmpty() }, text)
        assertTrue(machine.videoUla.teletext)
    }

    @Test
    fun vsyncInterruptsArriveAt50Hz() = withMachine { machine ->
        machine.runForSeconds(1.0)
        val before = machine.crtc.frames
        machine.runForSeconds(1.0)
        assertEquals(50.0, (machine.crtc.frames - before).toDouble(), 0.5)
    }

    @Test
    fun breakIsAWarmStartThatKeepsBasic() = withMachine { machine ->
        machine.runForSeconds(2.0)
        machine.reset()
        machine.runForSeconds(1.0)
        val screen = machine.teletextLines()
        val text = screen.joinToString("\n")
        // The memory size (and its beep) follow the banner only at power-on: the
        // MOS string table at &C304 holds "BBC Computer ", then "16K" and "32K".
        assertEquals("BBC Computer", screen[1], text)
        assertEquals("BASIC", screen[3], text)
        assertEquals(">", screen[5], text)
    }

    @Test
    fun startupLinksChooseTheScreenMode() = withMachine { machine ->
        // Links 6-8 are the mode bits 4, 2 and 1; fitting all three selects MODE 0.
        for (link in 6..8) machine.keyboard.link(link, fitted = true)
        machine.runForSeconds(2.0)
        assertTrue(!machine.videoUla.teletext)
        assertTrue(machine.videoUla.fastCharacterClock)
        assertEquals(0x0600, machine.crtc.startAddress, "MODE 0 screen starts at &3000")
    }

    @Test
    fun basicIsRom15AndAppearsUnderFourRomNumbers() = withMachine { machine ->
        for (romNumber in listOf(3, 7, 11, 15)) {
            machine.bus.write(0xFE30, romNumber)
            val title = (0x8009 until 0x800E).map { machine.bus.peek(it).toChar() }.joinToString("")
            assertEquals("BASIC", title, "ROM $romNumber")
        }
        machine.bus.write(0xFE30, 14)
        assertEquals(PagedRoms.EMPTY_SOCKET, machine.bus.peek(0x8009))
    }

    @Test
    fun ioPagesHideTheMosCredits() = withMachine { machine ->
        val image = os.readBytes()
        val hidden = image.copyOfRange(0x3C00, 0x3C1D).toString(Charsets.US_ASCII)
        assertEquals("(C) 1981 Acorn Computers Ltd.", hidden)
        val visible = (0xFC00 until 0xFC1D).map { machine.bus.peek(it) }
        assertTrue(visible.all { it == BbcMemoryBus.EMPTY_ONE_MHZ_BUS }, visible.toString())
    }

    private fun withMachine(block: (BbcMachine) -> Unit) {
        if (!os.isFile || !basic.isFile) {
            println("Skipping: BBC ROMs not found in $romDirectory")
            return
        }
        block(BbcMachine(os.readBytes(), basic.readBytes()))
    }
}
