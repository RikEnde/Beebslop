// SPDX-FileCopyrightText: 2026 The Beebslop contributors
// SPDX-License-Identifier: BSD-3-Clause

package beebslop.bbc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Via6522Test {
    private fun via(ports: ViaPorts = object : ViaPorts {}) = Via6522(ports).apply { reset() }

    @Test
    fun resetDisablesInterruptsAndIerReadsBit7Set() {
        val via = via()
        via.write(14, 0xFF)
        via.reset()
        assertEquals(0x80, via.read(14))
        assertEquals(0x00, via.read(13))
    }

    @Test
    fun ierSetsAndClearsBitsByBit7() {
        val via = via()
        via.write(14, 0x80 or Via6522.T1 or Via6522.CA1)
        assertEquals(0x80 or Via6522.T1 or Via6522.CA1, via.read(14))
        via.write(14, Via6522.CA1)
        assertEquals(0x80 or Via6522.T1, via.read(14))
    }

    @Test
    fun timer1OneShotCountsLatchThenFFFFAndFlagsOnce() {
        val via = via()
        via.write(4, 3)
        via.write(5, 0)
        val seen = mutableListOf(via.timer1)
        repeat(12) { via.tick(); seen += via.timer1 }
        // Hold one cycle after loading, count to 0, &FFFF, reload, and keep going.
        assertEquals(listOf(3, 3, 2, 1, 0, 0xFFFF, 3, 2, 1, 0, 0xFFFF, 3, 2), seen)
        assertEquals(Via6522.T1, via.ifr)
        via.read(4) // reading T1C-L clears the flag
        assertEquals(0, via.ifr)
        repeat(10) { via.tick() }
        assertEquals(0, via.ifr, "one-shot: no second interrupt without a new T1C-H write")
    }

    @Test
    fun timer1FreeRunInterruptsEveryLatchPlusTwoCycles() {
        val via = via()
        via.write(11, 0x40)
        via.write(14, 0x80 or Via6522.T1)
        via.write(4, 0x0E)
        via.write(5, 0x27) // 9998: the OS's 100 Hz clock is 10,000 cycles
        val interrupts = mutableListOf<Int>()
        for (cycle in 1..30_000) {
            via.tick()
            if (via.irq) {
                interrupts += cycle
                via.write(13, Via6522.T1)
            }
        }
        assertEquals(listOf(10_000, 20_000, 30_000), interrupts)
    }

    @Test
    fun timer2IsOneShotAndKeepsCounting() {
        val via = via()
        via.write(8, 2)
        via.write(9, 0)
        val seen = mutableListOf(via.timer2)
        repeat(6) { via.tick(); seen += via.timer2 }
        assertEquals(listOf(2, 2, 1, 0, 0xFFFF, 0xFFFE, 0xFFFD), seen)
        assertEquals(Via6522.T2, via.ifr)
        via.read(8)
        repeat(0x10000) { via.tick() }
        assertEquals(0, via.ifr)
    }

    @Test
    fun ifrBit7ShowsAnEnabledActiveFlag() {
        val via = via()
        // CA1 idles high. PCR bit 0 clear: CA1 is active on the falling edge.
        via.setCa1(false)
        assertEquals(Via6522.CA1, via.read(13))
        via.write(13, Via6522.CA1)
        via.setCa1(true)
        assertEquals(0, via.ifr, "rising edge ignored")
        via.setCa1(false)
        assertEquals(Via6522.CA1, via.read(13))
        assertFalse(via.irq)
        via.write(14, 0x80 or Via6522.CA1)
        assertEquals(0x80 or Via6522.CA1, via.read(13))
        assertTrue(via.irq)
        via.write(13, 0x7F)
        assertFalse(via.irq)
    }

    @Test
    fun ca1RisingEdgeWhenPcrBit0Set() {
        val via = via()
        via.write(12, 0x01)
        via.setCa1(false)
        assertEquals(0, via.ifr)
        via.setCa1(true)
        assertEquals(Via6522.CA1, via.ifr)
    }

    @Test
    fun readingPortAClearsCa1AndCa2UnlessCa2IsIndependent() {
        val via = via()
        via.write(12, 0x04) // CA2 positive edge, not independent
        via.setCa1(false)
        via.setCa2(false); via.setCa2(true)
        assertEquals(Via6522.CA1 or Via6522.CA2, via.ifr)
        via.read(1)
        assertEquals(0, via.ifr)

        via.write(12, 0x06) // CA2 independent positive edge
        via.setCa1(true); via.setCa1(false)
        via.setCa2(false); via.setCa2(true)
        via.read(1)
        assertEquals(Via6522.CA2, via.ifr)
        via.read(15) // no handshake: flags untouched
        assertEquals(Via6522.CA2, via.ifr)
    }

    @Test
    fun portsDriveOutputsAndReadPins() {
        var drivenA = -1
        var drivenB = -1
        val via = via(object : ViaPorts {
            override fun readA(driven: Int) = driven and 0x7F // pin 7 pulled low outside
            override fun readB(driven: Int) = 0x0F
            override fun writeA(driven: Int) { drivenA = driven }
            override fun writeB(driven: Int) { drivenB = driven }
        })
        via.write(3, 0x0F)
        via.write(1, 0x05)
        assertEquals(0xF5, drivenA, "inputs float high")
        assertEquals(0x75, via.read(1))
        via.write(2, 0xF0)
        via.write(0, 0xA0)
        assertEquals(0xAF, drivenB)
        assertEquals(0xAF, via.read(0), "port B: outputs from ORB, inputs from pins")
    }
}
