// SPDX-FileCopyrightText: 2026 The Beebslop contributors
// SPDX-License-Identifier: BSD-3-Clause

package beebslop.bbc

/**
 * The Mullard SAA5050 teletext character generator behind MODE 7, with the
 * English character set. This follows MAME's `saa5050.cpp`; the character
 * shapes come from Bedstead ([TeletextFont]).
 *
 * Each character cell is 12 dots by 20 lines, drawn as two interlaced fields of
 * 10 lines. A glyph is 5 dots by 9 rows in a 6 by 10 cell; every dot is doubled
 * in width and height, and *character rounding* adds half dots where a
 * diagonal crosses between rows, using the row above or below depending on the
 * field.
 *
 * Control codes `&00-&1F` change colours and modes, and display as spaces (or
 * as the held graphic). Colour changes take effect from the next character;
 * background changes from this one.
 *
 * Inputs from the BBC: [verticalSync] (DEW) starts a field and steps the flash
 * timer; [lineStart] (LOSE) starts a displayed line, resetting the per-line
 * attributes; [field] (CRS, the 6845's RA0) chooses the half of each doubled
 * row; and [character] takes each code.
 */
class Saa5050 {
    /** 12 dots, bit 11 leftmost, for the character just processed. */
    var dots: Int = 0; private set

    /** Colours for the character just processed, 0-7 as RGB bits 0-2. */
    var foreground: Int = 7; private set
    var background: Int = 0; private set

    private var lineCounter = 9
    private var frameCount = 0

    private var fg = 7
    private var graphics = false
    private var separated = false
    private var flash = false
    private var hold = false
    private var heldCharacter = 0x20
    private var heldSeparated = false
    private var heldGraphics = false
    private var nextGraphics = false
    private var nextSeparated = false
    private var doubleHeight = false
    private var doubleHeightOnRow = false
    private var bottomHalf = false

    /** CRS: 0 or 1, which line of each doubled row this field shows. */
    var field: Int = 0

    /** True while flashing characters are hidden. */
    val flashHidden: Boolean get() = frameCount and 0x30 == 0

    fun verticalSync() {
        lineCounter = 9
        frameCount = (frameCount + 1) and 0x3F
    }

    fun lineStart() {
        lineCounter = (lineCounter + 1) % 10
        if (lineCounter == 0) bottomHalf = if (bottomHalf) false else doubleHeightOnRow
        fg = 7
        background = 0
        graphics = false
        separated = false
        flash = false
        hold = false
        heldCharacter = 0x20
        heldSeparated = false
        heldGraphics = false
        nextGraphics = false
        nextSeparated = false
        doubleHeight = false
        doubleHeightOnRow = false
    }

    /** Process one 7-bit character code. */
    fun character(code: Int) {
        var data = code and 0x7F
        val doubleHeightBefore = doubleHeight
        val flashBefore = flash
        val graphicsBefore = graphics
        val holdBefore = hold
        foreground = fg
        var useGraphics = nextGraphics
        var useSeparated = nextSeparated

        if (data < 0x20) {
            control(data)
            if (graphicsBefore && (holdBefore || hold) && doubleHeight == doubleHeightBefore) {
                data = heldCharacter
                if (data in 0x40..0x5F) data = 0x20
                useGraphics = heldGraphics
                useSeparated = heldSeparated
            } else {
                heldCharacter = 0x20
                data = 0x20
            }
        } else if (graphics) {
            if (data and 0x20 != 0) {
                heldCharacter = data
                heldGraphics = useGraphics
                heldSeparated = useSeparated
            }
        } else {
            heldCharacter = 0x20
        }

        var line = lineCounter * 2 + field
        if (doubleHeightBefore) {
            line = line shr 1
            if (bottomHalf) line += 10
        }
        if (flashBefore && flashHidden) data = 0x20
        if (bottomHalf && !doubleHeight) data = 0x20

        dots = if (!useGraphics || data and 0x20 == 0) glyphLine(data, line) else mosaicLine(data, line, useSeparated)
    }

    private fun control(code: Int) {
        when (code) {
            in 0x01..0x07 -> { graphics = false; fg = code; chooseNext() }
            0x08 -> flash = true
            0x09 -> flash = false
            0x0C, 0x0D -> {
                doubleHeight = code == 0x0D
                if (doubleHeight) doubleHeightOnRow = true
            }
            in 0x11..0x17 -> { graphics = true; fg = code and 0x07; chooseNext() }
            0x18 -> { fg = background; foreground = background } // conceal
            0x19 -> { separated = false; chooseNext() }
            0x1A -> { separated = true; chooseNext() }
            0x1C -> background = 0
            0x1D -> background = fg
            0x1E -> hold = true
            0x1F -> hold = false
            // &00, &0A, &0B (boxing), &0E, &0F, &10 and &1B do nothing on the BBC.
        }
    }

    private fun chooseNext() {
        nextGraphics = graphics
        nextSeparated = separated
    }

    companion object {
        /** Character-rounded glyph lines: 96 codes by 20 lines. */
        private val rounded = IntArray(96 * 20).also { table ->
            for (code in 0 until 96) {
                for (line in 0 until 20) {
                    val neighbour = if (line and 1 != 0) line + 1 else line - 1
                    table[code * 20 + line] = round(romLine(code, line), romLine(code, neighbour))
                }
            }
        }

        /** A glyph row widened to 10 dots: each of the 5 dots doubled. Row 0 of the cell is blank. */
        private fun romLine(code: Int, line: Int): Int {
            if (line !in 0 until 20) return 0
            val cellRow = line shr 1
            if (cellRow == 0) return 0
            val bits = TeletextFont.rows[code * 9 + cellRow - 1]
            var wide = 0
            for (bit in 0 until 5) if (bits and (1 shl bit) != 0) wide = wide or (3 shl (bit * 2))
            return wide
        }

        /** Add half dots where a diagonal meets the neighbouring row (MAME `character_rounding`). */
        private fun round(a: Int, b: Int): Int =
            a or ((a shr 1) and b and (b shr 1).inv()) or ((a shl 1) and b and (b shl 1).inv())

        private fun glyphLine(code: Int, line: Int): Int = if (code < 0x20) 0 else rounded[(code - 0x20) * 20 + line]

        /**
         * Mosaic graphics: a 2 by 3 block per character, from bits 0, 1 (top), 2, 3
         * (middle) and 4, 6 (bottom). Separated graphics leave gaps between blocks.
         */
        private fun mosaicLine(code: Int, line: Int, separated: Boolean): Int {
            val (left, right) = when (line shr 1) {
                0, 1, 2 -> 0x01 to 0x02
                3, 4, 5, 6 -> 0x04 to 0x08
                else -> 0x10 to 0x40
            }
            val gap = line shr 1 == 2 || line shr 1 == 6 || line shr 1 == 9
            if (separated && gap) return 0
            var dots = (if (code and left != 0) 0xFC0 else 0) or (if (code and right != 0) 0x03F else 0)
            if (separated) dots = dots and 0x3CF
            return dots
        }
    }
}
