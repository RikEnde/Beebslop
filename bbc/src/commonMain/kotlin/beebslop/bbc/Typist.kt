// SPDX-FileCopyrightText: 2026 The Elkslop contributors
// SPDX-FileCopyrightText: 2026 The Beebslop contributors
// SPDX-License-Identifier: BSD-3-Clause

package beebslop.bbc

import beebslop.bbc.KeyboardMatrix.Key

/**
 * Presses keys frame by frame, like a very steady typist: pasted text, or key
 * chords from a menu. Call [onFrame] once per field (a 50th of a second). The
 * OS scans the keyboard from its 100 Hz interrupt, so each key is held, then
 * released, for a few frames; RETURN gets extra time so BASIC can tokenise the
 * line before the next one arrives.
 *
 * In a chord, the modifiers go down a frame before the last key, as a person
 * would press them. For lower case the typist first turns CAPS LOCK off, and it
 * turns SHIFT LOCK off before anything else, reading both from the LEDs.
 *
 * Adapted from Elkslop's `TextTyper`.
 */
class Typist {
    private sealed interface Item {
        data class Text(val char: Char) : Item

        /** Keys held together; [onPress] runs once they are all down (e.g. BREAK). */
        data class Chord(val keys: List<Key>, val holdFrames: Int, val onPress: ((BbcMachine) -> Unit)?) : Item
    }

    private val pending = ArrayDeque<Item>()
    private var waitFrames = 0
    private var held: List<Key> = emptyList()
    private var finalKey: Key? = null
    private var finalHoldFrames = 0
    private var finalAction: ((BbcMachine) -> Unit)? = null

    val busy: Boolean get() = pending.isNotEmpty() || held.isNotEmpty() || waitFrames > 0

    /** Queue [text]; characters the BBC cannot type are skipped and returned. */
    fun enqueue(text: String, delayFrames: Int = 0): Set<Char> {
        val skipped = mutableSetOf<Char>()
        val normalised = text.replace("\r\n", "\n").replace('\t', ' ')
        for (char in normalised) {
            if (KeyboardText.canType(char)) pending += Item.Text(char) else skipped += char
        }
        waitFrames = maxOf(waitFrames, delayFrames)
        return skipped
    }

    /** Press [keys] together for [holdFrames] frames, then release them. */
    fun enqueueChord(keys: List<Key>, holdFrames: Int = PRESS_FRAMES, onPress: ((BbcMachine) -> Unit)? = null) {
        require(keys.isNotEmpty())
        pending += Item.Chord(keys, holdFrames, onPress)
    }

    fun cancel(machine: BbcMachine) {
        pending.clear()
        waitFrames = 0
        finalKey = null
        finalAction = null
        releaseHeld(machine)
    }

    fun onFrame(machine: BbcMachine) {
        if (waitFrames > 0) {
            waitFrames--
            return
        }
        finalKey?.let { key ->
            machine.keyboard.press(key)
            finalKey = null
            waitFrames = finalHoldFrames
            finalAction?.invoke(machine)
            finalAction = null
            return
        }
        if (held.isNotEmpty()) {
            waitFrames = if (Key.RETURN in held) RETURN_FRAMES else RELEASE_FRAMES
            releaseHeld(machine)
            return
        }
        when (val item = pending.removeFirstOrNull() ?: return) {
            is Item.Text -> {
                val latch = machine.latch
                val stroke = KeyboardText.forCharacter(item.char, latch.capsLockLed) ?: return
                val toggle = when {
                    latch.shiftLockLed -> Key.SHIFT_LOCK
                    stroke.capsLockOff && latch.capsLockLed -> Key.CAPS_LOCK
                    else -> null
                }
                if (toggle != null) {
                    pending.addFirst(item)
                    press(machine, listOf(toggle), PRESS_FRAMES, null)
                } else {
                    press(machine, listOfNotNull(Key.SHIFT.takeIf { stroke.shift }, stroke.key), PRESS_FRAMES, null)
                }
            }
            is Item.Chord -> press(machine, item.keys, item.holdFrames, item.onPress)
        }
    }

    private fun press(machine: BbcMachine, keys: List<Key>, holdFrames: Int, onPress: ((BbcMachine) -> Unit)?) {
        held = keys
        keys.dropLast(1).forEach(machine.keyboard::press)
        // The last key goes down on a later frame (at once for a single key).
        finalKey = keys.last()
        finalHoldFrames = holdFrames
        finalAction = onPress
        waitFrames = if (keys.size > 1) MODIFIER_LEAD_FRAMES else 0
        if (keys.size == 1) onFrame(machine)
    }

    private fun releaseHeld(machine: BbcMachine) {
        held.forEach(machine.keyboard::release)
        held = emptyList()
    }

    private companion object {
        const val PRESS_FRAMES = 2
        const val RELEASE_FRAMES = 2
        const val RETURN_FRAMES = 12
        const val MODIFIER_LEAD_FRAMES = 1
    }
}
