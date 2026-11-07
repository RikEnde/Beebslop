// SPDX-FileCopyrightText: 2026 The Beebslop contributors
// SPDX-License-Identifier: BSD-3-Clause

package beebslop.bbc

import beebslop.bbc.KeyboardMatrix.Key

/**
 * Which BBC key types a character, as MOS 1.20 reads the keyboard. Checked by
 * pressing every key with and without SHIFT on the real ROM, and it agrees with
 * MAME's `bbc_kbd.cpp`.
 *
 * Letters follow CAPS LOCK: while it is on, a letter is a capital with or
 * without SHIFT, so lower case needs CAPS LOCK off. SHIFT+0 and SHIFT+@ type the
 * same as the plain key. The BBC's character &60 is the pound sign, so there is
 * no backtick.
 */
object KeyboardText {
    /**
     * A key to press for a character. [shift] is the SHIFT needed with CAPS LOCK
     * in the state passed to [forCharacter]; [capsLockOff] means the character
     * can only be typed with CAPS LOCK off.
     */
    data class Stroke(val key: Key, val shift: Boolean, val capsLockOff: Boolean = false)

    private val unshifted: Map<Char, Key> = mapOf(
        ' ' to Key.SPACE, '\n' to Key.RETURN, '\r' to Key.RETURN,
        '-' to Key.MINUS, '^' to Key.CARET, '\\' to Key.BACKSLASH, '@' to Key.AT,
        '[' to Key.LEFT_BRACKET, '_' to Key.UNDERSCORE, ';' to Key.SEMICOLON, ':' to Key.COLON,
        ']' to Key.RIGHT_BRACKET, ',' to Key.COMMA, '.' to Key.FULL_STOP, '/' to Key.SLASH,
    )

    private val shifted: Map<Char, Key> = mapOf(
        '!' to Key.DIGIT_1, '"' to Key.DIGIT_2, '#' to Key.DIGIT_3, '$' to Key.DIGIT_4,
        '%' to Key.DIGIT_5, '&' to Key.DIGIT_6, '\'' to Key.DIGIT_7, '(' to Key.DIGIT_8,
        ')' to Key.DIGIT_9, '=' to Key.MINUS, '~' to Key.CARET, '|' to Key.BACKSLASH,
        '{' to Key.LEFT_BRACKET, '£' to Key.UNDERSCORE, '+' to Key.SEMICOLON, '*' to Key.COLON,
        '}' to Key.RIGHT_BRACKET, '<' to Key.COMMA, '>' to Key.FULL_STOP, '?' to Key.SLASH,
    )

    /** The key for [char] with CAPS LOCK [capsLock], or null if the BBC cannot type it. */
    fun forCharacter(char: Char, capsLock: Boolean): Stroke? {
        if (char in 'A'..'Z') return Stroke(letter(char), shift = !capsLock)
        if (char in 'a'..'z') return Stroke(letter(char), shift = false, capsLockOff = true)
        if (char in '0'..'9') return Stroke(digit(char), shift = false)
        unshifted[char]?.let { return Stroke(it, shift = false) }
        shifted[char]?.let { return Stroke(it, shift = true) }
        return null
    }

    fun canType(char: Char): Boolean = forCharacter(char, capsLock = true) != null

    fun letter(char: Char): Key = Key.valueOf(char.uppercaseChar().toString())

    fun digit(char: Char): Key = Key.valueOf("DIGIT_$char")

    /** f0-f9. */
    fun functionKey(number: Int): Key {
        require(number in 0..9)
        return Key.valueOf("F$number")
    }
}
