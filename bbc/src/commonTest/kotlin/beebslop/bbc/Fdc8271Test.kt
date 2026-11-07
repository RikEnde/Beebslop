// SPDX-FileCopyrightText: 2026 The Beebslop contributors
// SPDX-License-Identifier: BSD-3-Clause

package beebslop.bbc

import beebslop.bbc.Fdc8271.Companion.ERR_LATE_DMA
import beebslop.bbc.Fdc8271.Companion.ERR_NF
import beebslop.bbc.Fdc8271.Companion.ERR_NONE
import beebslop.bbc.Fdc8271.Companion.ERR_NR
import beebslop.bbc.Fdc8271.Companion.ERR_WP
import beebslop.bbc.Fdc8271.Companion.SR_BSY
import beebslop.bbc.Fdc8271.Companion.SR_DRQ
import beebslop.bbc.Fdc8271.Companion.SR_IRQ
import beebslop.bbc.Fdc8271.Companion.SR_RF
import beebslop.bbc.Fdc8271.Companion.TICKS_PER_REVOLUTION
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The 8271 through its registers, as DFS drives it: non-DMA, moving bytes on each DRQ. */
class Fdc8271Test {
    private val fdc = Fdc8271()
    private var ticks = 0L

    /** Track t, sector s holds bytes t*16 + s + i. */
    private val disc = Disc.parse(ByteArray(80 * Disc.TRACK_BYTES) { i ->
        val sector = i / Disc.SECTOR_SIZE
        ((sector / 10) * 16 + sector % 10 + i % Disc.SECTOR_SIZE).toByte()
    }, 1)

    private fun run(count: Int) = repeat(count) {
        fdc.tick()
        ticks++
    }

    private fun send(command: Int, vararg parameters: Int) {
        fdc.write(0, command)
        for (p in parameters) fdc.write(1, p)
    }

    /** A command that finishes at once: its result, with no interrupt. */
    private fun immediate(command: Int, vararg parameters: Int): Int {
        send(command, *parameters)
        assertEquals(0, fdc.read(0) and (SR_BSY or SR_IRQ), "finished at once")
        return fdc.read(1)
    }

    private fun writeSpecial(register: Int, value: Int) = immediate(0x7A, register, value)

    /** Polls like an NMI handler would, every [pollTicks]; returns the result. */
    private fun execute(
        command: Int,
        vararg parameters: Int,
        received: MutableList<Int>? = null,
        supply: (() -> Int)? = null,
        pollTicks: Int = 16,
        limit: Int = 4 * TICKS_PER_REVOLUTION,
    ): Int {
        send(command, *parameters)
        val start = ticks
        while (ticks - start < limit) {
            run(pollTicks)
            val status = fdc.read(0)
            if (status and SR_DRQ != 0) {
                if (supply != null) fdc.write(4, supply()) else received?.add(fdc.read(4))
            }
            if (status and SR_RF != 0) {
                assertTrue(fdc.interrupt, "a result interrupts")
                val result = fdc.read(1)
                assertFalse(fdc.interrupt, "reading the result clears the interrupt")
                return result
            }
        }
        error("command &%02X never finished".format(command))
    }

    private fun setUp(withDisc: Boolean = true) {
        if (withDisc) fdc.drives[0].disc = disc
        // Step rate 3 (6 ms), head settle, and unload after 12 idle turns.
        immediate(0x75, 0x0D, 3, 0, 0xC0)
        immediate(0x75, 0x10, 0xFF, 0xFF, 0)
        writeSpecial(0x17, 0xC1) // non-DMA mode
        writeSpecial(0x23, 0x48) // select drive 0, load the head
    }

    @Test
    fun driveStatusShowsReadyTrackZeroAndWriteProtect() {
        setUp()
        // Bit 4 is the index hole, which comes and goes.
        assertEquals(0x44 or 0x02, immediate(0x6C) and 0x10.inv(), "ready, at track 0")
        disc.writeProtected = true
        assertEquals(0x08, immediate(0x6C) and 0x08)
        fdc.drives[0].disc = null
        assertEquals(0, immediate(0x6C) and 0x44, "an empty drive isn't ready")
    }

    @Test
    fun seekStepsAtTheStepRate() {
        setUp()
        val start = ticks
        assertEquals(ERR_NONE, execute(0x69, 40))
        val ms = (ticks - start) / 2000.0
        assertTrue(ms in 238.0..245.0, "40 steps of 6 ms took $ms ms")
        assertEquals(40, fdc.drives[0].headTrack)
        assertEquals(40, immediate(0x7D, 0x12), "present cylinder number")
        assertEquals(ERR_NONE, execute(0x69, 0), "seek to 0 recalibrates")
        assertEquals(0, fdc.drives[0].headTrack)
    }

    @Test
    fun readsSectorsInOrderWithinATurn() {
        setUp()
        val received = mutableListOf<Int>()
        // Read 3 sectors of 256 bytes from track 2, sector 4.
        val start = ticks
        assertEquals(ERR_NONE, execute(0x53, 2, 4, 0x20 or 3, received = received))
        assertEquals(3 * 256, received.size)
        for (s in 0 until 3) {
            val expected = ByteArray(256) { (2 * 16 + 4 + s + it).toByte() }
            assertContentEquals(expected, received.subList(s * 256, (s + 1) * 256).map { it.toByte() }.toByteArray())
        }
        assertTrue(ticks - start < 2 * TICKS_PER_REVOLUTION, "found and read within the seek and a turn")
        assertEquals(2, fdc.drives[0].headTrack)
    }

    @Test
    fun aByteTheCpuIsTooSlowForEndsTheCommand() {
        setUp()
        // Polling every 200 ticks misses bytes that arrive every 128.
        assertEquals(ERR_LATE_DMA, execute(0x53, 0, 0, 0x21, received = mutableListOf(), pollTicks = 200))
    }

    @Test
    fun aMissingSectorIsNotFoundAfterTwoTurns() {
        setUp()
        val start = ticks
        assertEquals(ERR_NF, execute(0x53, 0, 12, 0x21, received = mutableListOf()))
        val turns = (ticks - start).toDouble() / TICKS_PER_REVOLUTION
        assertTrue(turns in 1.0..2.0, "gave up after $turns turns, at the second index hole")
    }

    @Test
    fun anEmptyDriveIsNotReady() {
        setUp(withDisc = false)
        assertEquals(ERR_NR, execute(0x53, 0, 0, 0x21, received = mutableListOf()))
    }

    @Test
    fun writesASector() {
        setUp()
        val before = disc.changes
        var next = 0
        assertEquals(ERR_NONE, execute(0x4B, 5, 9, 0x21, supply = { next++ and 0xFF }))
        assertEquals(256, next)
        assertContentEquals(ByteArray(256) { it.toByte() }, disc.sector(0, 5, 9)!!.data)
        assertTrue(disc.changes > before)
        disc.writeProtected = true
        assertEquals(ERR_WP, execute(0x4B, 5, 9, 0x21, supply = { 0 }))
    }

    @Test
    fun formatsATrackWithTheIdsGivenAndReadsThemBack() {
        setUp()
        // Five sectors of 512 bytes, numbered backwards, with head number 1.
        val ids = (0 until 5).flatMap { listOf(7, 1, 20 - it, 2) }.iterator()
        assertEquals(ERR_NONE, execute(0x63, 7, 21, 0x40 or 5, 0, 16, supply = { ids.next() }))
        assertFalse(ids.hasNext())
        val track = disc.track(0, 7)!!.sectors
        assertEquals(listOf(20, 19, 18, 17, 16), track.map { it.number })
        assertTrue(track.all { it.data.size == 512 && it.data.all { b -> b == Disc.FORMAT_FILL } })

        val idBytes = mutableListOf<Int>()
        assertEquals(ERR_NONE, execute(0x5B, 7, 0, 5, received = idBytes))
        assertEquals(5 * 4, idBytes.size)
        assertEquals(setOf(20, 19, 18, 17, 16), idBytes.chunked(4).map { it[2] }.toSet())
        assertTrue(idBytes.chunked(4).all { it[0] == 7 && it[1] == 1 && it[3] == 2 })
    }

    @Test
    fun theOutputPortSelectsTheSide() {
        val dsd = Disc.blank(2)
        dsd.sector(1, 0, 3)!!.data.fill(0x55)
        fdc.drives[0].disc = dsd
        setUp(withDisc = false)
        writeSpecial(0x23, 0x48 or 0x20)
        assertEquals(1, fdc.side)
        val received = mutableListOf<Int>()
        assertEquals(ERR_NONE, execute(0x53, 0, 3, 0x21, received = received))
        assertTrue(received.all { it == 0x55 })
    }

    @Test
    fun theHeadUnloadsAfterTheIdleIndexCount() {
        setUp()
        assertEquals(ERR_NONE, execute(0x53, 0, 0, 0x21, received = mutableListOf()))
        assertTrue(fdc.headLoaded)
        run(11 * TICKS_PER_REVOLUTION)
        assertTrue(fdc.headLoaded)
        run(2 * TICKS_PER_REVOLUTION)
        assertFalse(fdc.headLoaded, "unloaded after 12 turns idle")
    }
}
