// SPDX-FileCopyrightText: 2026 The Beebslop contributors
// SPDX-License-Identifier: BSD-3-Clause

package beebslop.desktop

import beebslop.bbc.BbcClock
import beebslop.bbc.BbcMachine
import beebslop.bbc.KeyboardMatrix.Key
import java.awt.event.KeyEvent
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KeyMappingTest {
    private val romDirectory = File(System.getProperty("beebslop.romDirectory") ?: "../roms")

    @Test
    fun physicalLayoutFollowsMame() {
        assertEquals(Key.CARET, KeyMapping.forKeyCode(KeyEvent.VK_EQUALS))
        assertEquals(Key.COLON, KeyMapping.forKeyCode(KeyEvent.VK_QUOTE))
        assertEquals(Key.UNDERSCORE, KeyMapping.forKeyCode(KeyEvent.VK_BACK_QUOTE))
        assertEquals(Key.AT, KeyMapping.forKeyCode(KeyEvent.VK_BACK_SLASH))
        assertEquals(Key.COPY, KeyMapping.forKeyCode(KeyEvent.VK_END))
        assertEquals(Key.F0, KeyMapping.forKeyCode(KeyEvent.VK_F10))
        assertEquals(Key.F9, KeyMapping.forKeyCode(KeyEvent.VK_F9))
        assertNull(KeyMapping.forKeyCode(KeyMapping.BREAK_KEY), "BREAK is not in the matrix")
    }

    @Test
    fun symbolsOverrideTheHostModifiers() {
        assertEquals(KeyMapping.NaturalStroke(Key.MINUS, shift = true), stroke(KeyEvent.VK_EQUALS, '='))
        assertEquals(KeyMapping.NaturalStroke(Key.COLON, shift = false), stroke(KeyEvent.VK_SEMICOLON, ':'))
        assertEquals(KeyMapping.NaturalStroke(Key.AT, shift = false), stroke(KeyEvent.VK_2, '@'))
        assertEquals(KeyMapping.NaturalStroke(Key.DIGIT_3, shift = true), stroke(KeyEvent.VK_3, '#'))
        assertEquals(KeyMapping.NaturalStroke(Key.UNDERSCORE, shift = true), stroke(KeyEvent.VK_3, '£'))
    }

    @Test
    fun lettersAndControlKeysFollowTheHostModifiers() {
        assertEquals(KeyMapping.NaturalStroke(Key.A), stroke(KeyEvent.VK_A, 'a'))
        assertEquals(KeyMapping.NaturalStroke(Key.A), stroke(KeyEvent.VK_A, 'A'))
        assertEquals(KeyMapping.NaturalStroke(Key.G), stroke(KeyEvent.VK_G, '\u0007', control = true))
        assertEquals(KeyMapping.NaturalStroke(Key.RETURN), stroke(KeyEvent.VK_ENTER, '\n'))
        assertEquals(KeyMapping.NaturalStroke(Key.F1), stroke(KeyEvent.VK_F1, KeyEvent.CHAR_UNDEFINED))
    }

    @Test
    fun optionComposedSymbolsDropCtrl() {
        // German Mac layout: Option+5 is [. US Mac layout: Option+A is å.
        assertEquals(KeyMapping.NaturalStroke(Key.LEFT_BRACKET, shift = false, control = false), stroke(KeyEvent.VK_5, '[', alt = true))
        assertEquals(KeyMapping.NaturalStroke(Key.A), stroke(KeyEvent.VK_A, 'å', alt = true))
    }

    @Test
    fun charactersTheBbcLacksDoNothing() {
        assertNull(stroke(KeyEvent.VK_2, 'é'))
        assertNull(stroke(KeyEvent.VK_BACK_QUOTE, '`'))
        assertNull(stroke(KeyEvent.VK_ALT, KeyEvent.CHAR_UNDEFINED), "Option is for composing")
    }

    @Test
    fun deadKeysTypeTheirSymbol() {
        assertEquals(KeyMapping.NaturalStroke(Key.CARET, shift = false), stroke(KeyEvent.VK_DEAD_CIRCUMFLEX, KeyEvent.CHAR_UNDEFINED))
        assertEquals(KeyMapping.NaturalStroke(Key.E), stroke(KeyEvent.VK_E, 'é'))
    }

    @Test
    fun naturalLayoutTypesOnTheRealRom() {
        val os = File(romDirectory, "os.rom")
        val basic = File(romDirectory, "basic.rom")
        if (!os.isFile || !basic.isFile) return println("Skipping: no ROMs in ${romDirectory.absolutePath}")
        val machine = BbcMachine(os.readBytes(), basic.readBytes())
        val natural = NaturalKeyboard()
        machine.runForSeconds(2.0)
        fun frames(count: Int) = repeat(count) {
            natural.onFrame(machine.keyboard)
            machine.runForTicks(BbcClock.TICKS_PER_SECOND / 50L)
        }
        fun tap(keyCode: Int, char: Char, shift: Boolean = false) {
            if (shift) natural.modifier(Key.SHIFT, true)
            natural.keyDown(keyCode, KeyMapping.naturalStroke(keyCode, char, control = false, alt = false)!!)
            frames(3)
            natural.keyUp(keyCode)
            if (shift) natural.modifier(Key.SHIFT, false)
            frames(3)
        }
        tap(KeyEvent.VK_EQUALS, '=')
        tap(KeyEvent.VK_SEMICOLON, ':', shift = true)
        tap(KeyEvent.VK_2, '@', shift = true)
        tap(KeyEvent.VK_QUOTE, '"', shift = true)
        tap(KeyEvent.VK_OPEN_BRACKET, '{', shift = true)
        tap(KeyEvent.VK_CLOSE_BRACKET, ']')
        tap(KeyEvent.VK_A, 'a') // CAPS LOCK is on at power-on.
        tap(KeyEvent.VK_A, 'A', shift = true)
        assertEquals(">=:@\"{]AA", machine.teletextLines()[5])
    }

    private fun stroke(keyCode: Int, char: Char, control: Boolean = false, alt: Boolean = false) =
        KeyMapping.naturalStroke(keyCode, char, control, alt)
}
