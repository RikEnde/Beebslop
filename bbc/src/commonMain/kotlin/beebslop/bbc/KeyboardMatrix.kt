// SPDX-FileCopyrightText: 2026 The Beebslop contributors
// SPDX-License-Identifier: BSD-3-Clause

package beebslop.bbc

/**
 * The Model B keyboard: 10 columns by 8 rows, and the eight start-up option
 * links in row 0 of columns 2-9. BREAK is wired to reset, not to the matrix.
 *
 * The keyboard is read through the system VIA's port A, on the slow data bus.
 * When the OS polls it (addressable latch B3 low), port A bits 0-3 choose a
 * column and bits 4-6 a row, and bit 7 reads back 1 if that key is down. The
 * rest of the time a 4-bit counter steps through the columns at 1 MHz. Either
 * way, CA2 is high while the current column has a key down in rows 1-7.
 *
 * This follows MAME's `bbc_kbd.cpp`; the key positions are listed in [Key].
 */
class KeyboardMatrix {
    private val columns = IntArray(16)
    private var column = 0

    /** Start-up option links, by link number 1-8; see [link]. */
    private var links = 0

    /** Level for the system VIA's CA2. */
    var keyInColumn: Boolean = false
        private set

    fun press(key: Key) { columns[key.column] = columns[key.column] or (1 shl key.row) }
    fun release(key: Key) { columns[key.column] = columns[key.column] and (1 shl key.row).inv() }
    fun releaseAll() {
        columns.fill(0)
        for (number in 1..8) if (isLinkFitted(number)) columns[number + 1] = 0x01
    }

    fun isPressed(key: Key): Boolean = columns[key.column] and (1 shl key.row) != 0

    /**
     * Fit or remove start-up option link [number], 1-8. A fitted link reads as a
     * pressed key at row 0 of column `number + 1`. MAME names them: 1 default
     * filing system, 3 and 4 disc timings, 5 boot on BREAK, and 6-8 the screen
     * mode bits 4, 2 and 1. With no links fitted, the machine starts in MODE 7.
     */
    fun link(number: Int, fitted: Boolean) {
        require(number in 1..8) { "Links are numbered 1-8" }
        val bit = 1 shl number
        links = if (fitted) links or bit else links and bit.inv()
        val column = number + 1
        columns[column] = if (fitted) columns[column] or 0x01 else columns[column] and 0x01.inv()
    }

    fun isLinkFitted(number: Int): Boolean = links and (1 shl number) != 0

    /** A polled read: the slow data bus value with bit 7 replaced by the key state. */
    fun poll(dataBus: Int): Int {
        column = dataBus and 0x0F
        val row = (dataBus shr 4) and 0x07
        updateInterrupt()
        val down = columns[column] and (1 shl row) != 0
        return (dataBus and 0x7F) or (if (down) 0x80 else 0)
    }

    /** One 1 MHz cycle with the keyboard scanning itself. */
    fun scan() {
        column = (column + 1) and 0x0F
        updateInterrupt()
    }

    private fun updateInterrupt() {
        keyInColumn = columns[column] and 0xFE != 0
    }

    /** Key positions, from MAME's `bbc_kbd.cpp`: column 0-9, row 0-7. */
    enum class Key(val column: Int, val row: Int) {
        SHIFT(0, 0), Q(0, 1), F0(0, 2), DIGIT_1(0, 3), CAPS_LOCK(0, 4), SHIFT_LOCK(0, 5), TAB(0, 6), ESCAPE(0, 7),
        CTRL(1, 0), DIGIT_3(1, 1), W(1, 2), DIGIT_2(1, 3), A(1, 4), S(1, 5), Z(1, 6), F1(1, 7),
        DIGIT_4(2, 1), E(2, 2), D(2, 3), X(2, 4), C(2, 5), SPACE(2, 6), F2(2, 7),
        DIGIT_5(3, 1), T(3, 2), R(3, 3), F(3, 4), G(3, 5), V(3, 6), F3(3, 7),
        F4(4, 1), DIGIT_7(4, 2), DIGIT_6(4, 3), Y(4, 4), H(4, 5), B(4, 6), F5(4, 7),
        DIGIT_8(5, 1), I(5, 2), U(5, 3), J(5, 4), N(5, 5), M(5, 6), F6(5, 7),
        F7(6, 1), DIGIT_9(6, 2), O(6, 3), K(6, 4), L(6, 5), COMMA(6, 6), F8(6, 7),
        MINUS(7, 1), DIGIT_0(7, 2), P(7, 3), AT(7, 4), SEMICOLON(7, 5), FULL_STOP(7, 6), F9(7, 7),
        CARET(8, 1), UNDERSCORE(8, 2), LEFT_BRACKET(8, 3), COLON(8, 4), RIGHT_BRACKET(8, 5), SLASH(8, 6), BACKSLASH(8, 7),
        LEFT(9, 1), DOWN(9, 2), UP(9, 3), RETURN(9, 4), DELETE(9, 5), COPY(9, 6), RIGHT(9, 7),
    }
}
