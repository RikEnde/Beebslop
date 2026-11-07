// SPDX-FileCopyrightText: 2026 The Elkslop contributors
// SPDX-FileCopyrightText: 2026 The Beebslop contributors
// SPDX-License-Identifier: BSD-3-Clause

package beebslop.desktop

import beebslop.bbc.KeyboardMatrix.Key
import beebslop.bbc.KeyboardText
import java.awt.event.KeyEvent

/**
 * Host keyboard to BBC keyboard. Adapted from Elkslop's `KeyMapping`.
 *
 * In the Physical layout keys map by position, following MAME's `bbc_kbd.cpp`,
 * so shifted symbols are the BBC's own: Shift+2 is ", Shift+' is * (the : key),
 * and = is Shift+-. The BBC has two more symbol keys than a US keyboard: @ is
 * on \ (as in MAME), and \ | is on the extra key ISO keyboards have next to
 * Shift, or in the Keys menu. On macOS, Java derives letter and digit key codes
 * from the host layout and punctuation from the physical key.
 *
 * The Natural layout types the character the host produced, through
 * [naturalStroke] and [NaturalKeyboard].
 */
object KeyMapping {
    private val physical: Map<Int, Key> = buildMap {
        for (c in 'A'..'Z') put(KeyEvent.VK_A + (c - 'A'), KeyboardText.letter(c))
        for (d in '0'..'9') put(KeyEvent.VK_0 + (d - '0'), KeyboardText.digit(d))
        for (n in 1..9) put(KeyEvent.VK_F1 + n - 1, KeyboardText.functionKey(n))
        put(KeyEvent.VK_F10, Key.F0)
        put(KeyEvent.VK_SPACE, Key.SPACE)
        put(KeyEvent.VK_ENTER, Key.RETURN)
        put(KeyEvent.VK_BACK_SPACE, Key.DELETE)
        put(KeyEvent.VK_DELETE, Key.DELETE)
        put(KeyEvent.VK_ESCAPE, Key.ESCAPE)
        put(KeyEvent.VK_TAB, Key.TAB)
        put(KeyEvent.VK_SHIFT, Key.SHIFT)
        put(KeyEvent.VK_CONTROL, Key.CTRL)
        put(KeyEvent.VK_ALT, Key.CAPS_LOCK)
        put(KeyEvent.VK_END, Key.COPY)
        put(KeyEvent.VK_LEFT, Key.LEFT)
        put(KeyEvent.VK_RIGHT, Key.RIGHT)
        put(KeyEvent.VK_UP, Key.UP)
        put(KeyEvent.VK_DOWN, Key.DOWN)
        put(KeyEvent.VK_MINUS, Key.MINUS)
        put(KeyEvent.VK_EQUALS, Key.CARET)
        put(KeyEvent.VK_BACK_QUOTE, Key.UNDERSCORE)
        put(KeyEvent.VK_OPEN_BRACKET, Key.LEFT_BRACKET)
        put(KeyEvent.VK_CLOSE_BRACKET, Key.RIGHT_BRACKET)
        put(KeyEvent.VK_BACK_SLASH, Key.AT)
        put(KeyEvent.VK_LESS, Key.BACKSLASH)
        put(KeyEvent.VK_SEMICOLON, Key.SEMICOLON)
        put(KeyEvent.VK_QUOTE, Key.COLON)
        put(KeyEvent.VK_COMMA, Key.COMMA)
        put(KeyEvent.VK_PERIOD, Key.FULL_STOP)
        put(KeyEvent.VK_SLASH, Key.SLASH)
    }

    /** Key that triggers BREAK (reset) rather than a matrix key. */
    const val BREAK_KEY = KeyEvent.VK_F12

    fun forKeyCode(keyCode: Int): Key? = physical[keyCode]

    /**
     * A key press in the Natural layout. A null [shift] follows what the host
     * is holding; true or false overrides it while the key is down.
     */
    data class NaturalStroke(val key: Key, val shift: Boolean? = null, val control: Boolean? = null)

    /** The BBC modifier a host key stands for, in the Natural layout. */
    fun naturalModifier(keyCode: Int): Key? = when (keyCode) {
        KeyEvent.VK_SHIFT -> Key.SHIFT
        KeyEvent.VK_CONTROL -> Key.CTRL
        else -> null
    }

    /**
     * Stroke for a host key that produced [char], or null if it should do nothing.
     * With [alt] (Option or AltGr) the host may have composed a symbol; then the
     * symbol is typed, and CTRL must not reach the BBC.
     */
    fun naturalStroke(keyCode: Int, char: Char, control: Boolean, alt: Boolean): NaturalStroke? {
        physical[keyCode]?.takeIf { keyCode in positionalInNatural }?.let { return NaturalStroke(it) }
        deadKeys[keyCode]?.let { return symbolStroke(it, alt) }
        // CTRL combines with the key itself, whatever character the host makes.
        if (control && !alt) return byPosition(keyCode)?.let { NaturalStroke(it) }
        // Letters keep the host's SHIFT, so CAPS LOCK works as on the BBC.
        if (char in 'a'..'z' || char in 'A'..'Z') return NaturalStroke(KeyboardText.letter(char))
        symbolStroke(char, alt)?.let { return it }
        // An accented letter is what follows a dead key: type its letter.
        if (keyCode in KeyEvent.VK_A..KeyEvent.VK_Z) return byPosition(keyCode)?.let { NaturalStroke(it) }
        return null
    }

    private fun symbolStroke(char: Char, alt: Boolean): NaturalStroke? {
        val stroke = KeyboardText.forCharacter(char, capsLock = true) ?: return null
        return NaturalStroke(stroke.key, stroke.shift, if (alt) false else null)
    }

    /** Letters and digits by position; symbols differ between the keyboards. */
    private fun byPosition(keyCode: Int): Key? =
        physical[keyCode]?.takeIf { keyCode in KeyEvent.VK_A..KeyEvent.VK_Z || keyCode in KeyEvent.VK_0..KeyEvent.VK_9 }

    /** Keys that don't type a symbol, so the host's SHIFT and CTRL apply as they are. */
    private val positionalInNatural = setOf(
        KeyEvent.VK_SPACE, KeyEvent.VK_ENTER, KeyEvent.VK_BACK_SPACE, KeyEvent.VK_DELETE, KeyEvent.VK_ESCAPE,
        KeyEvent.VK_TAB, KeyEvent.VK_END, KeyEvent.VK_LEFT, KeyEvent.VK_RIGHT, KeyEvent.VK_UP, KeyEvent.VK_DOWN,
    ) + (KeyEvent.VK_F1..KeyEvent.VK_F10)

    /** Dead keys wait for the next key on the host. Here they type at once. */
    private val deadKeys = mapOf(
        KeyEvent.VK_DEAD_CIRCUMFLEX to '^', KeyEvent.VK_DEAD_TILDE to '~',
        KeyEvent.VK_DEAD_ACUTE to '\'', KeyEvent.VK_DEAD_DIAERESIS to '"',
    )
}
