// SPDX-FileCopyrightText: 2026 The Beebslop contributors
// SPDX-License-Identifier: BSD-3-Clause

package beebslop.bbc

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiscTest {
    /** An image whose every sector starts with its own track, side and sector number. */
    private fun numbered(sides: Int, tracks: Int): ByteArray {
        val image = ByteArray(tracks * sides * Disc.TRACK_BYTES)
        for (t in 0 until tracks) for (side in 0 until sides) for (s in 0 until Disc.SECTORS) {
            val at = ((t * sides + side) * Disc.SECTORS + s) * Disc.SECTOR_SIZE
            image[at] = t.toByte()
            image[at + 1] = side.toByte()
            image[at + 2] = s.toByte()
        }
        return image
    }

    @Test
    fun anSsdIsTrackAfterTrack() {
        val disc = Disc.parse(numbered(1, 40), 1)
        val sector = disc.sector(0, 12, 7)!!
        assertEquals(listOf(12, 0, 7), sector.data.take(3).map { it.toInt() })
        assertEquals(12, sector.track)
        assertEquals(1, sector.sizeCode)
        assertEquals(256, sector.data.size)
    }

    @Test
    fun aDsdAlternatesTheSidesTrackByTrack() {
        val disc = Disc.parse(numbered(2, 80), 2)
        assertEquals(listOf(5, 1, 3), disc.sector(1, 5, 3)!!.data.take(3).map { it.toInt() })
        assertEquals(listOf(79, 0, 9), disc.sector(0, 79, 9)!!.data.take(3).map { it.toInt() })
    }

    @Test
    fun sectorsLieInOrderAroundTheTrack() {
        val sectors = Disc.parse(numbered(1, 1), 1).track(0, 0)!!.sectors
        assertEquals((0 until 10).toList(), sectors.map { it.number })
        val positions = sectors.map { it.position }
        assertEquals(positions.sorted(), positions)
        // The last sector's data and CRC fit before the index hole.
        assertTrue(positions.last() + Disc.DATA_OFFSET + 256 + 2 < Disc.BYTES_PER_TRACK)
    }

    @Test
    fun aShortImageIsPaddedToItsCatalogueSize() {
        // Two sectors: a catalogue saying 400 sectors (40 tracks).
        val image = ByteArray(512)
        image[256 + 7] = (400 and 0xFF).toByte()
        image[256 + 6] = (400 shr 8).toByte()
        val disc = Disc.parse(image, 1)
        assertEquals(10, disc.track(0, 39)!!.sectors.size)
        assertTrue(disc.sector(0, 39, 9)!!.data.all { it.toInt() == 0 })
        assertTrue(disc.track(0, 40)!!.sectors.isEmpty(), "beyond the catalogue the disc is unformatted")
        assertNull(disc.sector(0, 40, 0))
    }

    @Test
    fun anImageWritesBackUnchanged() {
        for (sides in 1..2) {
            val image = numbered(sides, 80)
            assertContentEquals(image, Disc.parse(image, sides).toImage())
        }
    }

    @Test
    fun aBlankDiscHasAnEmptyCatalogueAndTheFormatFiller() {
        val disc = Disc.blank(2, 80, title = "GAMES")
        for (side in 0..1) {
            val sector0 = disc.sector(side, 0, 0)!!.data
            val sector1 = disc.sector(side, 0, 1)!!.data
            assertEquals("GAMES", sector0.take(5).map { it.toInt().toChar() }.joinToString(""))
            assertEquals(0, sector0[5].toInt())
            assertEquals(0, sector1[5].toInt(), "no files")
            assertEquals(800, (sector1[6].toInt() and 3 shl 8) or (sector1[7].toInt() and 0xFF))
            assertTrue(disc.sector(side, 0, 2)!!.data.all { it == Disc.FORMAT_FILL })
        }
        assertEquals(80 * 2 * Disc.TRACK_BYTES, disc.toImage().size)
    }

    @Test
    fun aBadImageIsRejected() {
        assertFailsWith<IllegalArgumentException> { Disc.parse(ByteArray(100), 1) }
        assertFailsWith<IllegalArgumentException> { Disc.parse(ByteArray(80 * Disc.TRACK_BYTES + 1), 1) }
    }
}
