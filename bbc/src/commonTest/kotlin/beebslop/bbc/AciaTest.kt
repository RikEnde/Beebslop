// SPDX-FileCopyrightText: 2026 The Beebslop contributors
// SPDX-License-Identifier: BSD-3-Clause

package beebslop.bbc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AciaTest {
    /** Out of reset at divide by 16, 8N1, RTS low, receive interrupts on. */
    private fun acia() = Acia6850().apply {
        write(0, 0x03)
        write(0, 0x95)
    }

    private fun Acia6850.receiveBit(bit: Boolean) {
        rxd = bit
        repeat(16) { receiveClock() }
    }

    @Test
    fun receivesAFramedByteAndInterrupts() {
        val acia = acia()
        repeat(3) { acia.receiveBit(true) }
        acia.receiveBit(false)
        for (i in 0 until 8) acia.receiveBit((0xA5 shr i) and 1 != 0)
        assertTrue(acia.irq)
        acia.receiveBit(true)
        assertEquals(Acia6850.RDRF, acia.read(0) and Acia6850.RDRF)
        assertEquals(0xA5, acia.read(1))
        assertFalse(acia.irq, "reading the data clears RDRF")
    }

    @Test
    fun carrierDetectInterruptsUntilStatusThenDataAreRead() {
        val acia = acia()
        acia.dcd = true
        acia.receiveClock()
        assertTrue(acia.irq)
        assertEquals(Acia6850.DCD, acia.read(0) and Acia6850.DCD)
        assertTrue(acia.irq, "still pending until the data register is read")
        acia.read(1)
        assertFalse(acia.irq)
        acia.dcd = false
        acia.receiveClock()
        assertEquals(0, acia.read(0) and Acia6850.DCD)
    }

    @Test
    fun transmitsBackToBackFramesOfTenBitTimes() {
        val acia = acia()
        val line = StringBuilder()
        fun clock(count: Int) = repeat(count) {
            acia.transmitClock()
            if (it % 16 == 15) line.append(if (acia.txd) '1' else '0')
        }
        clock(16 * 2)
        acia.write(1, 0x0F)
        clock(16)
        acia.write(1, 0xF0) // waits in the data register
        clock(16 * 22)
        val sent = line.toString().trimStart('1')
        assertEquals("0" + "11110000" + "1" + "0" + "00001111" + "1", sent.substring(0, 20))
    }

    @Test
    fun anIdleTransmitterStartsAtTheNextBitBoundary() {
        val acia = acia()
        repeat(20) { acia.transmitClock() }
        acia.write(1, 0x55)
        var clocks = 0
        while (acia.read(0) and Acia6850.TDRE == 0) {
            acia.transmitClock()
            clocks++
        }
        assertTrue(clocks in 1..16, "took $clocks clocks")
        assertTrue(clocks > 1, "not on the very next clock")
    }
}
