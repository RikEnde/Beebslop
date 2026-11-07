// SPDX-FileCopyrightText: 2026 The Beebslop contributors
// SPDX-License-Identifier: BSD-3-Clause

package beebslop.bbc

/**
 * The SN76489 sound generator's registers (*Advanced User Guide*, 23.3). It is
 * written from the slow data bus while addressable latch B0 is low.
 *
 * A byte with bit 7 set selects a register (bits 4-6) and sets its low bits; a
 * byte with bit 7 clear sets the high 6 bits of the selected tone register, or
 * replaces a volume or noise register. Channels 0-2 are tones, 3 is noise.
 *
 * Sound output comes later; this records what the OS asks for.
 */
class Sn76489 {
    /** 10-bit tone periods for channels 0-2. */
    val tone = IntArray(3)

    /** 4-bit attenuation for channels 0-3; 15 is silent. */
    val attenuation = IntArray(4) { 15 }

    /** The noise control register, 3 bits. */
    var noise: Int = 0
        private set

    private var selected = 0

    var writes: Long = 0
        private set

    fun write(value: Int) {
        writes++
        val data = value and 0xFF
        if (data and 0x80 != 0) selected = (data shr 4) and 0x07
        val channel = selected shr 1
        val isVolume = selected and 1 != 0
        when {
            isVolume -> attenuation[channel] = data and 0x0F
            channel == 3 -> noise = data and 0x07
            data and 0x80 != 0 -> tone[channel] = (tone[channel] and 0x3F0) or (data and 0x0F)
            else -> tone[channel] = (tone[channel] and 0x00F) or ((data and 0x3F) shl 4)
        }
    }
}
