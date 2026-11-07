// SPDX-FileCopyrightText: 2026 The Beebslop contributors
// SPDX-License-Identifier: BSD-3-Clause

package beebslop.bbc

/**
 * The devices in SHEILA, page `&FE`, on the Model B.
 *
 * Address ranges are from the *Advanced User Guide*, chapter 17, and agree with
 * MAME's `bbcb.cpp`. A device answers throughout its range; the device itself
 * decides how its registers repeat within it.
 *
 * [oneMhz] marks the devices that are accessed at 1 MHz, so the CPU cycle is
 * stretched: the CRTC, ACIA, serial ULA, STATID, both VIAs and the ADC
 * ([BeebWiki: Cycle stretching](https://beebwiki.mdfs.net/Cycle_stretching)).
 * The video ULA, ROMSEL, disc controller, Econet ADLC and Tube run at 2 MHz.
 */
enum class SheilaRegion(val first: Int, val last: Int, val oneMhz: Boolean) {
    CRTC(0x00, 0x07, oneMhz = true),
    ACIA(0x08, 0x0F, oneMhz = true),
    SERIAL_ULA(0x10, 0x17, oneMhz = true),
    STATID(0x18, 0x1F, oneMhz = true),
    VIDEO_ULA(0x20, 0x2F, oneMhz = false),
    ROMSEL(0x30, 0x3F, oneMhz = false),
    SYSTEM_VIA(0x40, 0x5F, oneMhz = true),
    USER_VIA(0x60, 0x7F, oneMhz = true),
    FDC(0x80, 0x9F, oneMhz = false),
    ADLC(0xA0, 0xBF, oneMhz = false),
    ADC(0xC0, 0xDF, oneMhz = true),
    TUBE(0xE0, 0xFF, oneMhz = false),
    ;

    val size: Int get() = last - first + 1

    companion object {
        private val byOffset = Array(256) { offset -> entries.single { offset in it.first..it.last } }

        /** The region for an address in SHEILA; only the low byte is used. */
        fun of(address: Int): SheilaRegion = byOffset[address and 0xFF]
    }
}

/** A device in SHEILA. Offsets are relative to the start of its [SheilaRegion]. */
interface IoDevice {
    fun read(offset: Int): Int
    fun write(offset: Int, value: Int)

    /** Read for debuggers and tests, without side effects such as clearing flags. */
    fun peek(offset: Int): Int
}
