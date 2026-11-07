// SPDX-FileCopyrightText: 2026 The Elkslop contributors
// SPDX-FileCopyrightText: 2026 The Beebslop contributors
// SPDX-License-Identifier: BSD-3-Clause

package beebslop.bbc

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse

/**
 * Loads programs from the real Welcome cassette images through MOS's own tape
 * filing system, comparing RAM against the file as stored on the tape. Skips
 * without the ROMs. Adapted from Elkslop's `TapeLoadTest`.
 */
class TapeLoadTest {
    private val romDirectory = File(System.getProperty("beebslop.romDirectory") ?: "roms")
    private val tapeDirectory = File(System.getProperty("beebslop.tapeDirectory") ?: "tapes")

    @Test
    fun loadsFirstProgramFromEnglishTape() = assertLoads("welcome-english.uef", "INTRO")

    /** INTRO comes after ten copies of WELCOME, the volume-setting tone, which LOAD skips. */
    @Test
    fun loadsIntroFromGermanTape() = assertLoads("welcome-german.uef", "INTRO")

    private fun assertLoads(tapeName: String, program: String) {
        val os = File(romDirectory, "os.rom")
        val basic = File(romDirectory, "basic.rom")
        if (!os.isFile || !basic.isFile) return println("Skipping: no ROMs in ${romDirectory.absolutePath}")
        val image = GZIPInputStream(File(tapeDirectory, tapeName).inputStream()).use { it.readBytes() }
        val expected = fileFromTape(image, program)

        val machine = BbcMachine(os.readBytes(), basic.readBytes())
        machine.serialUla.tape = Tape.parse(image)
        machine.runForSeconds(2.0)
        val typist = Typist()
        typist.enqueue("LOAD \"$program\"\n")
        while (typist.busy) {
            typist.onFrame(machine)
            machine.runForTicks(BbcClock.TICKS_PER_SECOND / 50L)
        }
        var seconds = 0
        while (machine.serialUla.motorOn) {
            machine.runForSeconds(1.0)
            check(++seconds < 300) { "the motor kept running" }
        }
        assertFalse(machine.serialUla.motorOn, "MOS stops the motor after loading")
        val page = (machine.ram[PAGE_HIGH].toInt() and 0xFF) shl 8
        assertContentEquals(expected, machine.ram.copyOfRange(page, page + expected.size))
    }

    /** Reads a file's bytes straight out of the tape's blocks, without the emulator. */
    private fun fileFromTape(image: ByteArray, name: String): ByteArray {
        val stream = ByteArrayOutputStream()
        var offset = 12
        while (offset < image.size) {
            val id = u16(image, offset)
            val size = u16(image, offset + 2) or (u16(image, offset + 4) shl 16)
            if (id == 0x0100) stream.write(image, offset + 6, size)
            offset += 6 + size
        }
        val data = stream.toByteArray()
        // Block number to contents; a file recorded more than once keeps its first copy.
        val blocks = sortedMapOf<Int, ByteArray>()
        var p = 0
        while (p < data.size) {
            if (data[p].toInt() != 0x2A) { p++; continue }
            val end = (p + 1 until data.size).first { data[it].toInt() == 0 }
            val blockName = String(data, p + 1, end - p - 1, Charsets.ISO_8859_1)
            val length = u16(data, end + 11)
            val start = end + 20 // Name terminator + 19 bytes of header and CRC.
            if (blockName == name) blocks.putIfAbsent(u16(data, end + 9), data.copyOfRange(start, start + length))
            p = start + length + if (length > 0) 2 else 0
        }
        check(blocks.isNotEmpty()) { "$name is not on the tape" }
        return blocks.values.reduce(ByteArray::plus)
    }

    private fun u16(bytes: ByteArray, at: Int) =
        (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

    private companion object {
        /** The high byte of BASIC's PAGE, where programs load. */
        const val PAGE_HIGH = 0x18
    }
}
