// SPDX-FileCopyrightText: 2026 The Elkslop contributors
// SPDX-FileCopyrightText: 2026 The Beebslop contributors
// SPDX-License-Identifier: BSD-3-Clause

package beebslop.bbc

/**
 * Demodulated cassette signal, one value per tape cell: a 1/1200 s stretch
 * holding one cycle of 1200 Hz (ZERO) or two of 2400 Hz (ONE). SILENCE has
 * no tone at all.
 */
enum class TapeBit { ZERO, ONE, SILENCE }

/**
 * What the serial ULA's cassette socket is connected to. Called once per tape
 * cell while the motor runs: [readBit] when playing, [writeBit] when recording.
 * It must not re-enter the machine. Waveforms and their timing tolerances are
 * deliberately outside this boundary.
 */
interface CassettePort {
    fun readBit(): TapeBit = TapeBit.SILENCE
    fun writeBit(bit: TapeBit) {}
}

/**
 * A cassette: tape cells in order, with a play/record position. It moves only
 * while the motor runs. Reading past the end gives silence; recording
 * overwrites from the position and lengthens the tape as needed.
 *
 * [parse] reads a UEF image: data (&0100, &0102, &0104), carrier (&0110,
 * &0111), gap (&0112, &0116) and baud rate (&0117) chunks. At 300 baud each
 * data bit lasts four cells. Other chunks, such as security cycles (&0114),
 * don't change the signal this model can represent and are skipped. [toUef]
 * writes the tape back out as a UEF image. Images are usually gzipped; the host
 * unpacks and packs them.
 *
 * Adapted from Elkslop's `UefTape`.
 */
class Tape private constructor(private var cells: ByteArray, size: Int) : CassettePort {
    /** Cells recorded, from the start of the tape. */
    var length: Int = size
        private set

    /** Tape position in cells: 1/1200 of a second each. */
    var position: Int = 0
        private set

    val atEnd: Boolean get() = position >= length

    /** Seconds of tape, at 1200 cells a second. */
    val seconds: Double get() = length / CELLS_PER_SECOND.toDouble()

    fun rewind() { position = 0 }

    override fun readBit(): TapeBit {
        if (atEnd) {
            position++
            return TapeBit.SILENCE
        }
        return when (cells[position++]) {
            ZERO -> TapeBit.ZERO
            ONE -> TapeBit.ONE
            else -> TapeBit.SILENCE
        }
    }

    override fun writeBit(bit: TapeBit) {
        if (position >= cells.size) cells = cells.copyOf(maxOf(cells.size * 2, position + 1, 1 shl 16))
        // Recording past the end of what's there leaves silence in any gap.
        if (position > length) cells.fill(SILENCE, length, position)
        cells[position++] = when (bit) {
            TapeBit.ZERO -> ZERO
            TapeBit.ONE -> ONE
            TapeBit.SILENCE -> SILENCE
        }
        if (position > length) length = position
    }

    /** The cell at [index], for tests and export. */
    operator fun get(index: Int): TapeBit = when (cells[index]) {
        ZERO -> TapeBit.ZERO
        ONE -> TapeBit.ONE
        else -> TapeBit.SILENCE
    }

    /**
     * The tape as a UEF image. Runs of standard 8N1 bytes become &0100 chunks,
     * runs of carrier &0110, silence &0112, and anything else explicit bits
     * (&0102), so [parse] gives back the same cells.
     */
    fun toUef(): ByteArray {
        val out = UefWriter()
        var i = 0
        while (i < length) {
            when (cells[i]) {
                SILENCE -> {
                    val end = runEnd(i, SILENCE)
                    var bits = end - i
                    while (bits > 0) {
                        val chunk = minOf(bits, MAX_GAP_CELLS)
                        out.chunk(0x0112, u16(chunk * 2))
                        bits -= chunk
                    }
                    i = end
                }
                ONE -> {
                    val end = runEnd(i, ONE)
                    var bits = end - i
                    while (bits > 0) {
                        val chunk = minOf(bits, MAX_CARRIER_CELLS)
                        out.chunk(0x0110, u16(chunk * 2))
                        bits -= chunk
                    }
                    i = end
                }
                else -> {
                    val bytes = mutableListOf<Int>()
                    var j = i
                    while (true) {
                        val byte = frameAt(j) ?: break
                        bytes += byte
                        j += 10
                    }
                    if (bytes.isNotEmpty()) {
                        out.chunk(0x0100, bytes.map { it.toByte() }.toByteArray())
                        i = j
                    } else {
                        // Not a byte: copy cells up to the next carrier or silence.
                        var end = i + 1
                        while (end < length && cells[end] == ZERO) end++
                        val count = end - i
                        val packed = ByteArray(1 + (count + 7) / 8)
                        packed[0] = ((8 - count % 8) % 8).toByte()
                        for (k in 0 until count) {
                            if (cells[i + k] == ONE) packed[1 + k / 8] = (packed[1 + k / 8].toInt() or (1 shl (k % 8))).toByte()
                        }
                        out.chunk(0x0102, packed)
                        i = end
                    }
                }
            }
        }
        return out.bytes()
    }

    private fun runEnd(start: Int, value: Byte): Int {
        var end = start
        while (end < length && cells[end] == value) end++
        return end
    }

    /** The 8N1 byte framed at [start], or null. */
    private fun frameAt(start: Int): Int? {
        if (start + 10 > length || cells[start] != ZERO || cells[start + 9] != ONE) return null
        var value = 0
        for (bit in 0 until 8) {
            when (cells[start + 1 + bit]) {
                ONE -> value = value or (1 shl bit)
                ZERO -> {}
                else -> return null
            }
        }
        return value
    }

    private class UefWriter {
        private val data = ArrayList<Byte>()

        init {
            MAGIC.forEach { data += it.code.toByte() }
            data += 10 // UEF version 0.10
            data += 0
        }

        fun chunk(id: Int, body: ByteArray) {
            for (b in u16(id)) data += b
            val size = body.size
            for (shift in listOf(0, 8, 16, 24)) data += (size ushr shift).toByte()
            for (b in body) data += b
        }

        fun bytes() = data.toByteArray()
    }

    private class Recorder {
        var cells = ByteArray(1 shl 16)
        var size = 0

        /** Cells per data bit: 1 at 1200 baud, 4 at 300. */
        var cellsPerBit = 1

        fun add(cell: Byte, count: Int = 1) {
            if (size + count > cells.size) cells = cells.copyOf(maxOf(cells.size * 2, size + count))
            cells.fill(cell, size, size + count)
            size += count
        }

        fun addBit(cell: Byte) = add(cell, cellsPerBit)

        fun addByte(value: Int, dataBits: Int = 8, parity: Char = 'N', stopBits: Int = 1) {
            addBit(ZERO)
            var ones = 0
            for (i in 0 until dataBits) {
                val bit = (value ushr i) and 1
                ones += bit
                addBit(if (bit == 1) ONE else ZERO)
            }
            if (parity == 'E' || parity == 'O') {
                val wantOdd = parity == 'O'
                addBit(if ((ones % 2 == 1) == wantOdd) ZERO else ONE)
            }
            repeat(stopBits) { addBit(ONE) }
        }

        /** A carrier of [cycles] 2400 Hz cycles: a cell holds two. */
        fun addCarrier(cycles: Int) = add(ONE, (cycles + 1) / 2)
    }

    companion object {
        private const val ZERO: Byte = 0
        private const val ONE: Byte = 1
        private const val SILENCE: Byte = 2
        const val CELLS_PER_SECOND = 1200
        private const val MAX_GAP_SECONDS = 3600f
        private const val MAX_GAP_CELLS = 0x7FFF
        private const val MAX_CARRIER_CELLS = 0x7FFF
        private const val HEADER_SIZE = 12
        private const val CHUNK_HEADER_SIZE = 6
        private const val MAGIC = "UEF File!\u0000"

        /** An empty tape, for recording. */
        fun blank(): Tape = Tape(ByteArray(1 shl 16), 0)

        /** @throws IllegalArgumentException if [image] is not a well-formed UEF file. */
        fun parse(image: ByteArray): Tape {
            require(image.size >= HEADER_SIZE && MAGIC.indices.all { image[it].toInt() == MAGIC[it].code }) {
                "Not a UEF tape image"
            }
            val recorder = Recorder()
            var offset = HEADER_SIZE
            while (offset < image.size) {
                require(image.size - offset >= CHUNK_HEADER_SIZE) { "UEF image is truncated" }
                val id = image.u16(offset)
                val size = image.u16(offset + 2).toLong() or (image.u16(offset + 4).toLong() shl 16)
                val start = offset + CHUNK_HEADER_SIZE
                require(size <= image.size - start) { "UEF chunk ${chunkName(id)} is truncated" }
                recorder.addChunk(id, image, start, size.toInt())
                offset = start + size.toInt()
            }
            return Tape(recorder.cells, recorder.size)
        }

        private fun Recorder.addChunk(id: Int, image: ByteArray, start: Int, size: Int) {
            val end = start + size
            fun need(bytes: Int) = require(size >= bytes) { "UEF chunk ${chunkName(id)} is too short" }
            when (id) {
                0x0100 -> for (i in start until end) addByte(image.u8(i))
                0x0102 -> {
                    need(1)
                    val count = (size - 1) * 8 - image.u8(start)
                    require(count >= 0) { "UEF chunk &0102 has a bad bit count" }
                    for (i in 0 until count) {
                        addBit(if ((image.u8(start + 1 + i / 8) ushr (i % 8)) and 1 == 1) ONE else ZERO)
                    }
                }
                0x0104 -> {
                    need(3)
                    val dataBits = image.u8(start)
                    val parity = image.u8(start + 1).toChar()
                    // A negative stop count asks for an extra short wave: not modelled.
                    val stopBits = kotlin.math.abs(image[start + 2].toInt())
                    require(dataBits in 1..8 && parity in "NEO" && stopBits in 0..2) {
                        "UEF chunk &0104 has an unknown format"
                    }
                    for (i in start + 3 until end) addByte(image.u8(i), dataBits, parity, stopBits)
                }
                0x0110 -> {
                    need(2)
                    addCarrier(image.u16(start))
                }
                0x0111 -> {
                    need(4)
                    addCarrier(image.u16(start))
                    addByte(0xAA)
                    addCarrier(image.u16(start + 2))
                }
                // Gap length counts half periods of the 1200 Hz base frequency.
                0x0112 -> {
                    need(2)
                    add(SILENCE, (image.u16(start) + 1) / 2)
                }
                0x0116 -> {
                    need(4)
                    val bits = image.u16(start).toLong() or (image.u16(start + 2).toLong() shl 16)
                    val seconds = Float.fromBits(bits.toInt())
                    require(seconds in 0f..MAX_GAP_SECONDS) { "UEF chunk &0116 has a bad gap length" }
                    add(SILENCE, (seconds * CELLS_PER_SECOND).toInt())
                }
                0x0117 -> {
                    need(2)
                    cellsPerBit = if (image.u16(start) == 300) 4 else 1
                }
            }
        }

        private fun ByteArray.u8(offset: Int) = this[offset].toInt() and 0xFF
        private fun ByteArray.u16(offset: Int) = u8(offset) or (u8(offset + 1) shl 8)
        private fun u16(value: Int) = byteArrayOf(value.toByte(), (value ushr 8).toByte())

        private fun chunkName(id: Int) = "&" + id.toString(16).uppercase().padStart(4, '0')
    }
}
