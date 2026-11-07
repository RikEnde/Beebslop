// SPDX-FileCopyrightText: 2026 The Beebslop contributors
// SPDX-License-Identifier: BSD-3-Clause

package beebslop.bbc

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Draws pictures through the real MOS VDU drivers, from roms/, and checks their
 * pixels. Each test also writes a PNG to build/test-frames. The tests skip, with
 * a message, if the ROMs are missing.
 */
class VideoRomTest {
    private val romDirectory = File(System.getProperty("beebslop.romDirectory") ?: "roms")
    private val os = File(romDirectory, "os.rom")
    private val basic = File(romDirectory, "basic.rom")
    private val frames = File("build/test-frames")

    // Picture geometry for the MOS's standard CRTC settings: see Video. In every
    // mode the MOS sets R7 so that the display starts 32 lines after the start
    // of vertical sync: in modes 0-2 and 4-5, 39 rows of 8 lines with sync at row
    // 35; in modes 3, 6 and 7, 31 rows of 10 lines plus 2, with sync at row 28.
    private val top = (32 - Video.TOP_LINE) * 2

    // MODE 7: the 6845's display starts 13 µs after horizontal sync (R0+1-R2 =
    // 64-51 characters), and the teletext path delays the picture by 2 more.
    // Characters are 16 pixels wide and rows 20 lines high.
    private fun mode7X(column: Int) = (15 - Video.LEFT_US) * 16 + column * 16 + 8
    private fun mode7Y(row: Int) = top + row * 20 + 10

    // MODE 2: 15 µs after horizontal sync ((128-98)/2). Characters are 32
    // pixels wide and rows 16 lines high.
    private fun mode2X(column: Int) = (15 - Video.LEFT_US) * 16 + column * 32 + 16
    private fun mode2Y(row: Int) = top + row * 16 + 8

    @Test
    fun bootScreenIsWhiteTextOnBlack() = withMachine { machine ->
        machine.runForSeconds(2.0)
        machine.savePicture(File(frames, "boot-mode7.png"))
        assertEquals(setOf(0x000000, 0xFFFFFF), machine.video.pixels.toSet())
        // "BBC Computer 32K" is on row 1: its first column has white dots, row 0 has none.
        val row1 = (mode7Y(1) - 8..mode7Y(1) + 8).flatMap { y -> (mode7X(0) - 8..mode7X(15) + 8).map { x -> machine.pixel(x, y) } }
        assertTrue(row1.count { it == 0xFFFFFF } > 100, "banner drawn")
        val row0 = (mode7Y(0) - 10 until mode7Y(0) + 10).flatMap { y -> (0 until Video.WIDTH).map { x -> machine.pixel(x, y) } }
        assertTrue(row0.all { it == 0 }, "row 0 is blank")
    }

    @Test
    fun mode7GraphicsColourBars() = withMachine { machine ->
        machine.runForSeconds(2.0)
        // CLS, then rows of solid graphics blocks (&FF) in each graphics colour.
        machine.vdu(12)
        for (colour in 1..7) {
            // A graphics colour code, 38 blocks, and a space in the 40th column.
            machine.vdu(0x90 + colour, *IntArray(38) { 0xFF }, 32)
        }
        machine.runForSeconds(0.1)
        machine.savePicture(File(frames, "mode7-colours.png"))
        for (colour in 1..7) {
            val row = colour - 1
            assertEquals(Video.COLOURS[colour], machine.pixel(mode7X(20), mode7Y(row)), "row $row")
            assertEquals(0, machine.pixel(mode7X(0), mode7Y(row)), "control code shows as a space, row $row")
        }
    }

    @Test
    fun mode7DoubleHeightText() = withMachine { machine ->
        machine.runForSeconds(2.0)
        // CLS; then a double-height "HI" twice, on rows 0 and 1.
        machine.vdu(12, 141, 'H'.code, 'I'.code, 13, 10, 141, 'H'.code, 'I'.code)
        machine.runForSeconds(0.1)
        machine.savePicture(File(frames, "mode7-double-height.png"))
        // H's left stem runs through both rows; the single-height H would leave gaps.
        val stemX = (mode7X(1) - 8 until mode7X(1) + 8).first { x -> machine.pixel(x, mode7Y(0)) != 0 }
        val stem = (mode7Y(0)..mode7Y(1)).map { y -> machine.pixel(stemX, y) }
        assertTrue(stem.all { it == 0xFFFFFF }, stem.toString())
    }

    @Test
    fun mode7CursorLinesUpWithItsCharacter() = withMachine { machine ->
        machine.runForSeconds(2.0)
        // The cursor follows the prompt on row 5: column 1, on raster lines 18-19
        // of the cell (R10 = &72, R11 = &13). It blinks, so look over a second.
        val cursorY = top + 5 * 20 + 18
        val lit = mutableSetOf<Int>()
        repeat(50) {
            machine.runForSeconds(0.02)
            for (x in 0 until Video.WIDTH) if (machine.pixel(x, cursorY) == 0xFFFFFF) lit += x
        }
        assertEquals(mode7X(1) - 8, lit.minOrNull(), "starts at column 1")
        assertEquals(mode7X(1) + 7, lit.maxOrNull(), "one character wide")
    }

    @Test
    fun mode2ColourBars() = withMachine { machine ->
        machine.runForSeconds(2.0)
        val bytes = mutableListOf(22, 2)
        for (colour in 0..7) {
            bytes += listOf(17, 128 + colour)
            repeat(20) { bytes += 32 }
        }
        machine.vdu(*bytes.toIntArray())
        machine.runForSeconds(0.1)
        machine.savePicture(File(frames, "mode2-colours.png"))
        for (colour in 0..7) {
            for (y in listOf(mode2Y(colour), mode2Y(colour) + 1)) {
                assertEquals(Video.COLOURS[colour], machine.pixel(mode2X(10), y), "row $colour, line $y")
            }
        }
    }

    @Test
    fun mode0TextAndMode6Gaps() = withMachine { machine ->
        machine.runForSeconds(2.0)
        machine.vdu(22, 0, *"MODE 0".map { it.code }.toIntArray())
        machine.runForSeconds(0.1)
        machine.savePicture(File(frames, "mode0-text.png"))
        val mode0 = machine.video.pixels.count { it == 0xFFFFFF }
        assertTrue(mode0 > 100, "white text pixels in MODE 0")

        // MODE 6 has 10-line rows with two blank lines: fill the screen with
        // inverse spaces and look for the gaps.
        machine.vdu(22, 6, 17, 129, *IntArray(40) { 32 })
        machine.runForSeconds(0.1)
        machine.savePicture(File(frames, "mode6-gaps.png"))
        // MODE 6 rows are 10 lines; the display starts 15 µs after horizontal
        // sync ((64-49) characters).
        val x = (15 - Video.LEFT_US) * 16 + 100
        val firstRow = (0 until 20).map { line -> machine.pixel(x, top + line) != 0 }
        assertEquals(List(16) { true } + List(4) { false }, firstRow)
    }

    private fun withMachine(block: (BbcMachine) -> Unit) {
        if (!os.isFile || !basic.isFile) {
            println("Skipping: BBC ROMs not found in $romDirectory")
            return
        }
        block(BbcMachine(os.readBytes(), basic.readBytes()))
    }
}
