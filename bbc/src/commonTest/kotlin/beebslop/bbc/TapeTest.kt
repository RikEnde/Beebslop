// SPDX-FileCopyrightText: 2026 The Elkslop contributors
// SPDX-FileCopyrightText: 2026 The Beebslop contributors
// SPDX-License-Identifier: BSD-3-Clause

package beebslop.bbc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** UEF reading is adapted from Elkslop's `UefTapeTest`; recording and writing are new. */
class TapeTest {
    private val header = "UEF File!".encodeToByteArray() + byteArrayOf(0, 10, 0)

    private fun chunk(id: Int, vararg payload: Int): ByteArray =
        byteArrayOf(id.toByte(), (id shr 8).toByte(), payload.size.toByte(), 0, 0, 0) +
            ByteArray(payload.size) { payload[it].toByte() }

    private fun tape(vararg chunks: ByteArray): Tape = Tape.parse(chunks.fold(header) { image, next -> image + next })

    private fun Tape.cells(): String = (0 until length).joinToString("") {
        when (this[it]) {
            TapeBit.ZERO -> "0"
            TapeBit.ONE -> "1"
            TapeBit.SILENCE -> "."
        }
    }

    private fun Tape.record(cells: String) = cells.forEach {
        writeBit(when (it) { '0' -> TapeBit.ZERO; '1' -> TapeBit.ONE; else -> TapeBit.SILENCE })
    }

    @Test
    fun dataChunkFramesBytesLsbFirst() = assertEquals("0" + "10100000" + "1", tape(chunk(0x0100, 0x05)).cells())

    @Test
    fun carrierIsTwoCyclesPerCell() = assertEquals("1111", tape(chunk(0x0110, 8, 0)).cells())

    @Test
    fun carrierWithDummyByteWrapsAA() =
        assertEquals("11" + "0" + "01010101" + "1" + "1", tape(chunk(0x0111, 4, 0, 2, 0)).cells())

    @Test
    fun integerGapCountsHalfCells() = assertEquals("...", tape(chunk(0x0112, 6, 0)).cells())

    @Test
    fun floatGapIsInSeconds() {
        val half = 0.5f.toRawBits()
        val gap = chunk(0x0116, half and 0xFF, (half shr 8) and 0xFF, (half shr 16) and 0xFF, (half shr 24) and 0xFF)
        assertEquals(".".repeat(600), tape(gap).cells())
    }

    @Test
    fun explicitBitsAreRawWithUnusedBitsDropped() = assertEquals("10100", tape(chunk(0x0102, 3, 0x05)).cells())

    @Test
    fun definedFormatAddsParityAndStopBits() =
        assertEquals("0" + "1110000" + "1" + "11", tape(chunk(0x0104, 7, 'E'.code, 2, 0x07)).cells())

    @Test
    fun at300BaudEachBitIsFourCells() {
        val cells = tape(chunk(0x0117, 0x2C, 0x01), chunk(0x0100, 0x01), chunk(0x0110, 4, 0)).cells()
        assertEquals("0000" + "1111" + "0000".repeat(7) + "1111" + "11", cells)
    }

    @Test
    fun unknownChunksAreSkipped() =
        assertEquals("11", tape(chunk(0x0000, 'x'.code), chunk(0x0114, 1, 2, 3), chunk(0x0110, 4, 0)).cells())

    @Test
    fun rejectsBadImages() {
        assertFailsWith<IllegalArgumentException> { Tape.parse(ByteArray(40)) }
        assertFailsWith<IllegalArgumentException> { Tape.parse(header + byteArrayOf(0, 1, 9, 0, 0, 0, 1)) }
        assertFailsWith<IllegalArgumentException> { tape(chunk(0x0110, 1)) }
    }

    @Test
    fun playsSilencePastTheEnd() {
        val tape = tape(chunk(0x0110, 2, 0))
        assertEquals(TapeBit.ONE, tape.readBit())
        assertTrue(tape.atEnd)
        assertEquals(TapeBit.SILENCE, tape.readBit())
        tape.rewind()
        assertEquals(TapeBit.ONE, tape.readBit())
    }

    @Test
    fun recordingOverwritesAndLengthens() {
        val tape = Tape.blank()
        tape.record("1111")
        tape.rewind()
        tape.readBit()
        tape.record("00")
        assertEquals("1001", tape.cells())
        tape.record("0110")
        assertEquals("100" + "0110", tape.cells().substring(0, 7))
        assertEquals(7, tape.length)
    }

    @Test
    fun writesUefThatReadsBackTheSameCells() {
        val tape = Tape.blank()
        // Carrier, a byte, bits that aren't a byte, silence, more bytes and carrier.
        val cells = "1".repeat(300) + "0" + "01010101" + "1" + "001" + ".".repeat(50) +
            "0" + "11110000" + "1" + "0" + "00000000" + "1" + "1".repeat(9)
        tape.record(cells)
        val copy = Tape.parse(tape.toUef())
        assertEquals(cells, copy.cells())
        val chunkIds = buildList {
            val image = tape.toUef()
            var offset = 12
            while (offset < image.size) {
                add((image[offset].toInt() and 0xFF) or ((image[offset + 1].toInt() and 0xFF) shl 8))
                offset += 6 + (image[offset + 2].toInt() and 0xFF) + ((image[offset + 3].toInt() and 0xFF) shl 8)
            }
        }
        assertEquals(listOf(0x0110, 0x0100, 0x0102, 0x0110, 0x0112, 0x0100, 0x0110), chunkIds)
    }
}
