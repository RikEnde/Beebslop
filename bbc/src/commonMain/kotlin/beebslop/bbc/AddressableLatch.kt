// SPDX-FileCopyrightText: 2026 The Beebslop contributors
// SPDX-License-Identifier: BSD-3-Clause

package beebslop.bbc

/**
 * IC32, the 8-bit addressable latch (a 74LS259) on system VIA port B.
 * PB0-PB2 choose a bit and PB3 is its new value (*Advanced User Guide*, 23.2).
 */
class AddressableLatch {
    var value: Int = 0
        private set

    /** Apply port B's low nibble. */
    fun write(portB: Int) {
        val bit = 1 shl (portB and 0x07)
        value = if (portB and 0x08 != 0) value or bit else value and bit.inv()
    }

    operator fun get(bit: Int): Boolean = value and (1 shl bit) != 0

    /** B0, active low: the SN76489 takes the slow data bus. */
    val soundWriteEnabled: Boolean get() = !this[SOUND_WRITE_ENABLE]

    /** B3, active low: the keyboard is read through port A instead of scanning itself. */
    val keyboardPolled: Boolean get() = !this[KEYBOARD_WRITE_ENABLE]

    /**
     * B4 and B5: the screen size for hardware scroll wrap-around, as B5 B4 (0-3).
     * MOS 1.20 writes 2 for modes 0-2, 0 for mode 3, 3 for modes 4-5 and 1 for
     * mode 6. The table in the online copy of the *Advanced User Guide* (23.2)
     * gives 3 for modes 0-2 and 2 for modes 4-5; the ROM and MAME's address
     * logic agree with each other, not with that table.
     */
    val screenWrap: Int get() = (value shr 4) and 0x03

    /**
     * B6 and B7 light the CAPS LOCK and SHIFT LOCK LEDs when low. MOS 1.20 starts
     * with CAPS LOCK on: B6 low.
     */
    val capsLockLed: Boolean get() = !this[CAPS_LOCK_LED]
    val shiftLockLed: Boolean get() = !this[SHIFT_LOCK_LED]

    companion object {
        const val SOUND_WRITE_ENABLE = 0
        const val SPEECH_READ = 1
        const val SPEECH_WRITE = 2
        const val KEYBOARD_WRITE_ENABLE = 3
        const val SCREEN_WRAP_C0 = 4
        const val SCREEN_WRAP_C1 = 5
        const val CAPS_LOCK_LED = 6
        const val SHIFT_LOCK_LED = 7
    }
}
