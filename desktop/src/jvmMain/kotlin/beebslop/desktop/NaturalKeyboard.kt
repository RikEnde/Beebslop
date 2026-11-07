// SPDX-FileCopyrightText: 2026 The Elkslop contributors
// SPDX-FileCopyrightText: 2026 The Beebslop contributors
// SPDX-License-Identifier: BSD-3-Clause

package beebslop.desktop

import beebslop.bbc.KeyboardMatrix
import beebslop.bbc.KeyboardMatrix.Key

/**
 * The Natural keyboard layout: each host key presses whatever the BBC needs to
 * type the character the host produced. Runs on the emulation thread.
 * Adapted from Elkslop's `NaturalKeyboard`.
 *
 * The two keyboards disagree about SHIFT: on a US keyboard = is unshifted but
 * needs SHIFT on the BBC, and : is the reverse. So a key can override the
 * modifiers the host is holding for as long as it is down, which only works for
 * one key at a time: a new key replaces the one before it. Events wait in a
 * queue because the OS misses a key that is replaced or released too soon.
 */
class NaturalKeyboard {
    private sealed interface Event {
        data class Modifier(val key: Key, val down: Boolean) : Event
        data class KeyDown(val hostKey: Int, val stroke: KeyMapping.NaturalStroke) : Event
        data class KeyUp(val hostKey: Int) : Event
    }

    private val events = ArrayDeque<Event>()
    private val hostModifiers = mutableSetOf<Key>()
    private val appliedModifiers = mutableSetOf<Key>()
    private var activeHostKey: Int? = null
    private var active: KeyMapping.NaturalStroke? = null
    private var waitFrames = 0

    fun modifier(key: Key, down: Boolean) { events += Event.Modifier(key, down) }
    fun keyDown(hostKey: Int, stroke: KeyMapping.NaturalStroke) { events += Event.KeyDown(hostKey, stroke) }
    fun keyUp(hostKey: Int) { events += Event.KeyUp(hostKey) }

    /** Forget everything; the caller releases the matrix keys. */
    fun reset() {
        events.clear()
        hostModifiers.clear()
        appliedModifiers.clear()
        activeHostKey = null
        active = null
        waitFrames = 0
    }

    /** Call once per emulated frame. */
    fun onFrame(keyboard: KeyboardMatrix) {
        if (waitFrames > 0 && --waitFrames > 0) return
        while (waitFrames == 0) {
            when (val event = events.firstOrNull() ?: return) {
                is Event.Modifier -> {
                    if (event.down) hostModifiers += event.key else hostModifiers -= event.key
                    applyModifiers(keyboard)
                    events.removeFirst()
                }
                is Event.KeyDown -> keyDown(keyboard, event)
                is Event.KeyUp -> {
                    events.removeFirst()
                    if (event.hostKey == activeHostKey) {
                        releaseActive(keyboard)
                        waitFrames = RELEASE_FRAMES
                    }
                }
            }
        }
    }

    private fun keyDown(keyboard: KeyboardMatrix, event: Event.KeyDown) {
        // The host repeats a key while it is held. The OS has its own auto-repeat.
        if (event.hostKey == activeHostKey) {
            events.removeFirst()
            return
        }
        // Two host keys can share a BBC key, such as - and =. The OS must see it
        // go up between them.
        if (active?.key == event.stroke.key) {
            releaseActive(keyboard)
            waitFrames = RELEASE_FRAMES
            return
        }
        events.removeFirst()
        active?.let { keyboard.release(it.key) }
        activeHostKey = event.hostKey
        active = event.stroke
        applyModifiers(keyboard)
        keyboard.press(event.stroke.key)
        waitFrames = HOLD_FRAMES
    }

    private fun releaseActive(keyboard: KeyboardMatrix) {
        active?.let { keyboard.release(it.key) }
        activeHostKey = null
        active = null
        applyModifiers(keyboard)
    }

    /** Writes only what changed, so keys pressed by the typist are left alone. */
    private fun applyModifiers(keyboard: KeyboardMatrix) {
        val stroke = active
        val wanted = buildSet {
            if (stroke?.shift ?: (Key.SHIFT in hostModifiers)) add(Key.SHIFT)
            if (stroke?.control ?: (Key.CTRL in hostModifiers)) add(Key.CTRL)
        }
        (appliedModifiers - wanted).forEach(keyboard::release)
        (wanted - appliedModifiers).forEach(keyboard::press)
        appliedModifiers.clear()
        appliedModifiers += wanted
    }

    private companion object {
        const val HOLD_FRAMES = 2
        const val RELEASE_FRAMES = 2
    }
}
