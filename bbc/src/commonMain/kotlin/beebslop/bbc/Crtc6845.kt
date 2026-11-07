// SPDX-FileCopyrightText: 2026 The Beebslop contributors
// SPDX-License-Identifier: BSD-3-Clause

package beebslop.bbc

/** Receives the 6845's outputs, one call per character time. */
interface CrtcOutput {
    /**
     * One character time: memory address [ma], raster address [ra], display
     * enable [displayEnabled] and the cursor signal [cursor].
     */
    fun character(ma: Int, ra: Int, displayEnabled: Boolean, cursor: Boolean)

    /** Horizontal sync starts, before this character time's [character] call. */
    fun horizontalSync()

    /** Vertical sync starts or ends. */
    fun verticalSync(active: Boolean)
}

/**
 * The Hitachi HD6845S CRT controller, at `&FE00` (address) and `&FE01` (data),
 * repeated through `&FE07` (*Advanced User Guide*, chapter 18; MAME `bbcb.cpp`).
 * Each [tick] is one character, at 1 or 2 MHz as the video ULA selects. Counter
 * behavior follows MAME's `mc6845.cpp` (`hd6845s_device`).
 *
 * - A scan line is R0+1 characters. Horizontal sync starts at character R2.
 *   Characters 0 to R1-1 are displayed.
 * - A character row ends when the raster counter reaches R9. A frame is R4+1
 *   rows, then R5 extra scan lines. Rows 0 to R6-1 are displayed.
 * - The memory address starts each frame at R12/R13 and each row at the previous
 *   row's start plus R1.
 * - In interlace sync and video mode (R8 bits 0-1 both set), used by MODE 7, the
 *   raster counter steps by 2 and starts on the field number, and a row ends
 *   when it matches R9 ignoring bit 0. Each field of a 20-line teletext row is
 *   then 10 lines. In interlace modes the field alternates every frame. The
 *   extra half line of an interlaced field is not yet produced.
 * - Vertical sync starts at the start of row R7 and lasts R3 bits 4-7 scan
 *   lines, where 0 means 16.
 * - The cursor is shown at address R14/R15 on rasters R10 bits 0-4 to R11. R10
 *   bits 5-6 choose steady, off, or blinking every 16 or 32 fields.
 * - R8 bits 4-5 set to 3 turn the display off. Other display and cursor skew
 *   settings are not yet emulated.
 * - R12-R17 can be read back; the other registers read 0.
 */
class Crtc6845 : IoDevice {
    private val registers = IntArray(18)
    private var address = 0

    var character = 0; private set
    var raster = 0; private set
    var row = 0; private set
    private var inAdjust = false
    private var adjustLine = 0
    var field = 0; private set
    private var vsyncCounter = 0
    private var rowAddress = 0
    private var verticalDisplay = true
    private var cursorBlinkCount = 0
    private var cursorBlinkOn = true

    var vsync: Boolean = false
        private set

    /** Completed frames (fields, when interlaced). */
    var frames: Long = 0
        private set

    var output: CrtcOutput? = null

    fun register(index: Int): Int = registers[index]

    /** The display start address, R12 and R13. */
    val startAddress: Int get() = (registers[12] shl 8) or registers[13]

    /** The cursor address, R14 and R15. */
    val cursorAddress: Int get() = (registers[14] shl 8) or registers[15]

    /** The memory address of the current character. */
    val memoryAddress: Int get() = (rowAddress + character) and 0x3FFF

    override fun read(offset: Int): Int = peek(offset)

    override fun peek(offset: Int): Int =
        if (offset and 1 == 0) 0 else if (address in 12..17) registers[address] else 0

    override fun write(offset: Int, value: Int) {
        if (offset and 1 == 0) {
            address = value and 0x1F
        } else if (address < registers.size) {
            registers[address] = value and WRITE_MASKS[address]
        }
    }

    private val interlaceVideo: Boolean get() = registers[8] and 0x03 == 0x03

    /** One character time. */
    fun tick() {
        output?.let { out ->
            if (character == registers[2]) out.horizontalSync()
            val displayEnabled = character < registers[1] && verticalDisplay && !inAdjust &&
                registers[8] and 0x30 != 0x30
            out.character(memoryAddress, raster, displayEnabled, displayEnabled && cursorHere())
        }
        if (character == registers[0]) {
            character = 0
            endScanLine()
        } else {
            character = (character + 1) and 0xFF
        }
    }

    private fun cursorHere(): Boolean {
        if (memoryAddress != cursorAddress || !cursorBlinkOn) return false
        val start = registers[10] and 0x1F
        return raster in start..registers[11]
    }

    private fun endScanLine() {
        if (vsync) {
            vsyncCounter = (vsyncCounter + 1) and 0x0F
            if (vsyncCounter == (registers[3] shr 4)) setVsync(false)
        }
        if (inAdjust) {
            adjustLine++
            if (adjustLine >= registers[5]) startFrame()
            return
        }
        val rowEnds = if (interlaceVideo) raster shr 1 == registers[9] shr 1 else raster == registers[9]
        if (!rowEnds) {
            raster = (raster + if (interlaceVideo) 2 else 1) and 0x1F
            return
        }
        if (row == registers[4]) {
            if (registers[5] == 0) startFrame() else { inAdjust = true; adjustLine = 0 }
            return
        }
        row = (row + 1) and 0x7F
        rowAddress = (rowAddress + registers[1]) and 0x3FFF
        if (row == registers[6]) verticalDisplay = false
        raster = rowStartRaster()
        checkVsyncStart()
    }

    private fun startFrame() {
        frames++
        field = if (registers[8] and 0x01 != 0) field xor 1 else 0
        inAdjust = false
        row = 0
        rowAddress = startAddress
        verticalDisplay = registers[6] != 0
        raster = rowStartRaster()
        updateCursorBlink()
        checkVsyncStart()
    }

    private fun updateCursorBlink() {
        val before = cursorBlinkCount
        cursorBlinkCount = (cursorBlinkCount + 1) and 0xFF
        cursorBlinkOn = when (registers[10] and 0x60) {
            0x00 -> true
            0x20 -> false
            0x40 -> if ((before xor cursorBlinkCount) and 0x10 != 0) !cursorBlinkOn else cursorBlinkOn
            else -> if ((before xor cursorBlinkCount) and 0x20 != 0) !cursorBlinkOn else cursorBlinkOn
        }
    }

    private fun rowStartRaster() = if (interlaceVideo) field else 0

    private fun checkVsyncStart() {
        if (!vsync && row == registers[7]) {
            vsyncCounter = 0
            setVsync(true)
        }
    }

    private fun setVsync(level: Boolean) {
        vsync = level
        output?.verticalSync(level)
    }

    companion object {
        /** Bits implemented in R0-R17. */
        private val WRITE_MASKS = intArrayOf(
            0xFF, 0xFF, 0xFF, 0xFF, 0x7F, 0x1F, 0x7F, 0x7F, 0xF3,
            0x1F, 0x7F, 0x1F, 0x3F, 0xFF, 0x3F, 0xFF, 0x3F, 0xFF,
        )
    }
}
