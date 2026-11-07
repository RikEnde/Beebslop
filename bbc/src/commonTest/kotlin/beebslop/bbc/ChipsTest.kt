// SPDX-FileCopyrightText: 2026 The Beebslop contributors
// SPDX-License-Identifier: BSD-3-Clause

package beebslop.bbc

import beebslop.bbc.KeyboardMatrix.Key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChipsTest {
    @Test
    fun latchSetsOneBitAtATime() {
        val latch = AddressableLatch()
        latch.write(0x08 or 3)
        latch.write(0x08 or 6)
        assertEquals(0x48, latch.value)
        assertFalse(latch.keyboardPolled)
        assertFalse(latch.capsLockLed, "the LEDs light when their bit is low")
        assertTrue(latch.shiftLockLed)
        latch.write(3)
        assertTrue(latch.keyboardPolled)
        assertEquals(0x40, latch.value)
        latch.write(0x08 or 4)
        latch.write(0x08 or 5)
        assertEquals(3, latch.screenWrap)
    }

    @Test
    fun keyboardPollReportsTheKeyInBit7() {
        val keyboard = KeyboardMatrix()
        keyboard.press(Key.A) // column 1, row 4
        assertEquals(0xC1, keyboard.poll(0x41))
        assertEquals(0x42, keyboard.poll(0xC2), "bit 7 is replaced")
        keyboard.release(Key.A)
        assertEquals(0x41, keyboard.poll(0x41))
    }

    @Test
    fun keyboardInterruptIgnoresRow0() {
        val keyboard = KeyboardMatrix()
        keyboard.press(Key.SHIFT)
        keyboard.poll(0x00)
        assertFalse(keyboard.keyInColumn, "SHIFT and CTRL don't interrupt")
        keyboard.press(Key.Q)
        keyboard.poll(0x00)
        assertTrue(keyboard.keyInColumn)
    }

    @Test
    fun keyboardScanFindsAKeyWithin16Cycles() {
        val keyboard = KeyboardMatrix()
        keyboard.press(Key.RETURN)
        var hits = 0
        repeat(32) { keyboard.scan(); if (keyboard.keyInColumn) hits++ }
        assertEquals(2, hits)
    }

    @Test
    fun linksReadAsKeysInRow0AndSurviveReleaseAll() {
        val keyboard = KeyboardMatrix()
        keyboard.link(8, fitted = true)
        assertEquals(0x89, keyboard.poll(0x09))
        keyboard.press(Key.SHIFT)
        keyboard.releaseAll()
        assertEquals(0x89, keyboard.poll(0x09))
        assertEquals(0x00, keyboard.poll(0x00))
    }

    @Test
    fun soundRegistersFollowLatchAndDataBytes() {
        val sound = Sn76489()
        sound.write(0x80 or 0x05) // channel 0 tone, low bits 5
        sound.write(0x2A) // high 6 bits
        assertEquals(0x2A5, sound.tone[0])
        sound.write(0x90 or 0x03) // channel 0 attenuation
        assertEquals(3, sound.attenuation[0])
        sound.write(0xE0 or 0x04) // noise
        assertEquals(4, sound.noise)
        assertEquals(0, sound.attenuation[3], "full volume until written, as at power-on")
    }

    @Test
    fun crtcRegistersAreMaskedAndOnlyR12ToR17ReadBack() {
        val crtc = Crtc6845()
        crtc.write(0, 4); crtc.write(1, 0xFF)
        assertEquals(0x7F, crtc.register(4))
        assertEquals(0, crtc.read(1))
        crtc.write(0, 14); crtc.write(1, 0xFF)
        assertEquals(0x3F, crtc.read(1))
        assertEquals(0, crtc.read(0))
    }

    private fun vsyncListener(onVsync: (Boolean) -> Unit) = object : CrtcOutput {
        override fun character(ma: Int, ra: Int, displayEnabled: Boolean, cursor: Boolean) {}
        override fun horizontalSync() {}
        override fun verticalSync(active: Boolean) = onVsync(active)
    }

    private fun Crtc6845.program(vararg values: Int) {
        values.forEachIndexed { index, value -> write(0, index); write(1, value) }
    }

    @Test
    fun mode7CrtcSettingsGiveA50HzField() {
        // MODE 7's R0-R9, as the MOS programs them.
        val crtc = Crtc6845()
        crtc.program(0x3F, 0x28, 0x33, 0x24, 0x1E, 0x02, 0x19, 0x1C, 0x93, 0x12)
        val edges = mutableListOf<Long>()
        var characters = 0L
        crtc.output = vsyncListener { if (it) edges += characters }
        repeat(64 * 312 * 4) { characters++; crtc.tick() }
        val periods = edges.zipWithNext { a, b -> b - a }
        // 64 characters per line at 1 MHz; 31 rows of 10 lines per field, plus 2.
        assertTrue(periods.isNotEmpty())
        assertTrue(periods.all { it == 64L * 312 }, periods.toString())
    }

    @Test
    fun vsyncLastsR3HighNibbleLines() {
        val crtc = Crtc6845()
        // 8 characters per line, 1-line rows, 10 rows, vsync at row 2 for 3 lines.
        crtc.program(7, 4, 5, 0x34, 9, 0, 8, 2, 0, 0)
        var start = -1L
        var length = -1L
        var characters = 0L
        crtc.output = vsyncListener { level -> if (level) start = characters else if (start >= 0 && length < 0) length = characters - start }
        repeat(8 * 30) { characters++; crtc.tick() }
        assertEquals(3L * 8, length)
    }

    @Test
    fun screenAddressesForTeletextAndHighResolution() {
        // MODE 7: start address &2800 is &7C00.
        assertEquals(0x7C00, ScreenAddress.of(0x2800, 0, 0))
        assertEquals(0x7FFF, ScreenAddress.of(0x2BFF, 0, 0))
        // MODE 0: start address &0600 is &3000; each character is 8 bytes, one per raster.
        // The wrap values are what MOS 1.20 writes to latch B5 and B4 for each mode.
        assertEquals(0x3000, ScreenAddress.of(0x0600, 0, 2))
        assertEquals(0x3007, ScreenAddress.of(0x0600, 7, 2))
        assertEquals(0x3008, ScreenAddress.of(0x0601, 0, 2))
        // Past &7FFF (MA12 set) the screen wraps to its start: modes 0-2 (B5 B4 = 1 0)
        // to &3000, 3 (0 0) to &4000, 4-5 (1 1) to &5800, 6 (0 1) to &6000.
        assertEquals(0x3000, ScreenAddress.of(0x1000, 0, 2))
        assertEquals(0x4000, ScreenAddress.of(0x1000, 0, 0))
        assertEquals(0x5800, ScreenAddress.of(0x1000, 0, 3))
        assertEquals(0x6000, ScreenAddress.of(0x1000, 0, 1))
    }

    @Test
    fun aciaIsQuietAfterResetAndReportsTransmitEmpty() {
        val acia = Acia6850()
        assertEquals(0x00, acia.read(0), "master reset: TDRE reads 0")
        acia.write(0, 0x56) // divide by 64, 8N1, RTS high, no interrupts
        assertEquals(Acia6850.TDRE, acia.read(0))
        assertFalse(acia.irq)
        acia.write(0, 0x36) // transmit interrupt enabled
        assertTrue(acia.irq)
        assertEquals(Acia6850.IRQ or Acia6850.TDRE, acia.read(0))
        acia.cts = true
        assertFalse(acia.irq)
        assertEquals(Acia6850.CTS, acia.read(0))
    }
}
