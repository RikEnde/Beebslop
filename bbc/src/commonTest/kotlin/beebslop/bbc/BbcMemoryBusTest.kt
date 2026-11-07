// SPDX-FileCopyrightText: 2026 The Beebslop contributors
// SPDX-License-Identifier: BSD-3-Clause

package beebslop.bbc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BbcMemoryBusTest {
    /** A recognisable synthetic MOS: each byte holds the high byte of its address. */
    private fun os() = ByteArray(0x4000) { ((0xC000 + it) shr 8).toByte() }
    private fun rom(fill: Int) = ByteArray(0x4000) { fill.toByte() }

    private fun bus(): BbcMemoryBus = BbcMemoryBus(ByteArray(0x8000), os(), PagedRoms(), BbcClock())

    private class RecordingDevice(private val value: Int) : IoDevice {
        val log = mutableListOf<String>()
        override fun read(offset: Int): Int { log += "R$offset"; return value }
        override fun write(offset: Int, value: Int) { log += "W$offset=$value" }
        override fun peek(offset: Int): Int = value
    }

    @Test
    fun ramIsReadWriteAcross32K() {
        val bus = bus()
        bus.write(0x0000, 0x12)
        bus.write(0x7FFF, 0x34)
        assertEquals(0x12, bus.read(0x0000))
        assertEquals(0x34, bus.read(0x7FFF))
    }

    @Test
    fun mosIsReadOnlyAndVisibleOutsideTheIoPages() {
        val bus = bus()
        assertEquals(0xC0, bus.read(0xC000))
        assertEquals(0xFB, bus.read(0xFBFF))
        assertEquals(0xFF, bus.read(0xFF00))
        assertEquals(0xFF, bus.read(0xFFFF))
        bus.write(0xC000, 0x00)
        assertEquals(0xC0, bus.read(0xC000))
    }

    @Test
    fun ioPagesHideTheMos() {
        val bus = bus()
        assertEquals(BbcMemoryBus.EMPTY_ONE_MHZ_BUS, bus.read(0xFC00)) // FRED
        assertEquals(BbcMemoryBus.EMPTY_ONE_MHZ_BUS, bus.read(0xFDFF)) // JIM
        for (address in 0xFE00..0xFEFF) {
            val expected = if (address in 0xFE80..0xFE9F) BbcMemoryBus.EMPTY_DISC_SLOT else BbcMemoryBus.UNMAPPED_SHEILA
            assertEquals(expected, bus.read(address), "&${hex(address)}")
        }
    }

    @Test
    fun romselSelectsOneOfFourSocketsUsingItsLowTwoBits() {
        val bus = bus()
        for (number in 12..15) bus.roms.install(number, rom(number))
        for (value in 0..255) {
            bus.write(0xFE30, value)
            assertEquals(12 + (value and 3), bus.read(0x8000), "ROMSEL=$value")
            assertEquals(12 + (value and 3), bus.read(0xBFFF), "ROMSEL=$value")
        }
    }

    @Test
    fun romselAnswersThroughoutItsRegionAndIsWriteOnly() {
        val bus = bus()
        bus.roms.install(15, rom(0xBA))
        bus.write(0xFE3F, 15)
        assertEquals(15, bus.roms.romsel)
        assertEquals(BbcMemoryBus.UNMAPPED_SHEILA, bus.read(0xFE30))
        assertEquals(15, bus.roms.romsel)
        assertEquals(0xBA, bus.read(0x8000))
    }

    @Test
    fun emptySocketsReadFFAndPagedRomIgnoresWrites() {
        val bus = bus()
        bus.roms.install(15, rom(0x5A))
        bus.write(0xFE30, 12)
        assertEquals(PagedRoms.EMPTY_SOCKET, bus.read(0x8000))
        bus.write(0xFE30, 15)
        bus.write(0x8000, 0x00)
        assertEquals(0x5A, bus.read(0x8000))
    }

    @Test
    fun onlyBoardSocketsCanBeFilled() {
        val roms = PagedRoms()
        assertFailsWith<IllegalArgumentException> { roms.install(11, rom(0)) }
        assertFailsWith<IllegalArgumentException> { roms.install(15, ByteArray(0x2000)) }
    }

    @Test
    fun sheilaRegionsMatchTheAdvancedUserGuide() {
        val expected = listOf(
            0x00 to SheilaRegion.CRTC, 0x07 to SheilaRegion.CRTC,
            0x08 to SheilaRegion.ACIA, 0x0F to SheilaRegion.ACIA,
            0x10 to SheilaRegion.SERIAL_ULA, 0x17 to SheilaRegion.SERIAL_ULA,
            0x18 to SheilaRegion.STATID, 0x1F to SheilaRegion.STATID,
            0x20 to SheilaRegion.VIDEO_ULA, 0x2F to SheilaRegion.VIDEO_ULA,
            0x30 to SheilaRegion.ROMSEL, 0x3F to SheilaRegion.ROMSEL,
            0x40 to SheilaRegion.SYSTEM_VIA, 0x5F to SheilaRegion.SYSTEM_VIA,
            0x60 to SheilaRegion.USER_VIA, 0x7F to SheilaRegion.USER_VIA,
            0x80 to SheilaRegion.FDC, 0x9F to SheilaRegion.FDC,
            0xA0 to SheilaRegion.ADLC, 0xBF to SheilaRegion.ADLC,
            0xC0 to SheilaRegion.ADC, 0xDF to SheilaRegion.ADC,
            0xE0 to SheilaRegion.TUBE, 0xFF to SheilaRegion.TUBE,
        )
        for ((offset, region) in expected) assertEquals(region, SheilaRegion.of(0xFE00 + offset))
        assertEquals(256, SheilaRegion.entries.sumOf { it.size })
    }

    @Test
    fun devicesSeeOffsetsWithinTheirRegion() {
        val bus = bus()
        val via = RecordingDevice(0x42)
        bus.attach(SheilaRegion.SYSTEM_VIA, via)
        assertEquals(0x42, bus.read(0xFE4E))
        bus.write(0xFE5F, 0x1FF)
        assertEquals(0x42, bus.peek(0xFE40))
        assertEquals(listOf("R14", "W31=255"), via.log)
        assertFailsWith<IllegalArgumentException> { bus.attach(SheilaRegion.ROMSEL, via) }
    }

    @Test
    fun ordinaryAccessesTakeOneTick() {
        val bus = bus()
        for (address in listOf(0x0000, 0x7FFF, 0x8000, 0xC000, 0xFBFF, 0xFF00, 0xFFFF)) {
            val before = bus.clock.ticks
            bus.read(address)
            assertEquals(1, bus.clock.ticks - before, "&${hex(address)}")
        }
    }

    @Test
    fun oneMhzAccessesEndOnAOneMhzEdge() {
        val stretched = (0x00..0x1F) + (0x40..0x7F) + (0xC0..0xDF)
        for (offset in 0x00..0xFF) {
            for (startOdd in listOf(false, true)) {
                val bus = bus()
                if (startOdd) bus.clock.advance(1)
                val before = bus.clock.ticks
                bus.write(0xFE00 + offset, 0)
                val expected = when {
                    offset !in stretched -> 1L
                    startOdd -> 3L
                    else -> 2L
                }
                assertEquals(expected, bus.clock.ticks - before, "&${hex(0xFE00 + offset)}, odd start $startOdd")
            }
        }
    }

    @Test
    fun fredAndJimAreStretched() {
        for (address in listOf(0xFC00, 0xFCFF, 0xFD00, 0xFDFF)) {
            val bus = bus()
            bus.read(address)
            assertEquals(2, bus.clock.ticks, "&${hex(address)} from an even tick")
            bus.read(address)
            assertEquals(4, bus.clock.ticks, "&${hex(address)} again, still on an edge")
            bus.clock.advance(1)
            bus.read(address)
            assertEquals(8, bus.clock.ticks, "&${hex(address)} from an odd tick")
        }
    }

    @Test
    fun peekDoesNotAdvanceTime() {
        val bus = bus()
        bus.peek(0xFE40)
        bus.peek(0x0000)
        assertEquals(0, bus.clock.ticks)
    }
}

internal fun hex(value: Int): String = value.toString(16).uppercase().padStart(4, '0')
