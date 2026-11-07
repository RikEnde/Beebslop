// SPDX-FileCopyrightText: 2026 The Beebslop contributors
// SPDX-License-Identifier: BSD-3-Clause

package beebslop.bbc

import beebslop.cpu.MemoryBus

/**
 * Model B address decoding (*Advanced User Guide*, chapter 17):
 *
 * | Address | Contents |
 * |---|---|
 * | `&0000-&7FFF` | RAM, 32 KiB |
 * | `&8000-&BFFF` | Paged ROM, selected by ROMSEL |
 * | `&C000-&FBFF` | MOS ROM |
 * | `&FC00-&FCFF` | FRED, 1 MHz bus |
 * | `&FD00-&FDFF` | JIM, 1 MHz bus |
 * | `&FE00-&FEFF` | SHEILA, the I/O devices in [SheilaRegion] |
 * | `&FF00-&FFFF` | MOS ROM |
 *
 * Each bus call advances [clock] by one bus cycle, stretched for 1 MHz devices,
 * and then performs the access. Reads from absent hardware follow MAME: `&FE`
 * in SHEILA, but `&FF` with no disc controller fitted, and `&FF` from FRED and
 * JIM with nothing on the 1 MHz bus. Real hardware leaves the data bus floating
 * there; other emulators differ (b2 returns 0).
 */
class BbcMemoryBus(
    val ram: ByteArray,
    osRom: ByteArray,
    val roms: PagedRoms,
    val clock: BbcClock,
) : MemoryBus {
    private val os = osRom.copyOf()
    private val devices = arrayOfNulls<IoDevice>(SheilaRegion.entries.size)

    init {
        require(ram.size == 0x8000) { "RAM must be 32 KiB" }
        require(os.size == 0x4000) { "MOS ROM must be 16 KiB" }
    }

    /** Connect a device to its SHEILA region; ROMSEL is part of the bus. */
    fun attach(region: SheilaRegion, device: IoDevice?) {
        require(region != SheilaRegion.ROMSEL) { "ROMSEL is built into the bus" }
        devices[region.ordinal] = device
    }

    override fun read(address: Int): Int {
        val location = address and 0xFFFF
        advanceForAccess(location)
        return readMapped(location, sideEffects = true)
    }

    override fun write(address: Int, value: Int) {
        val location = address and 0xFFFF
        advanceForAccess(location)
        when (location) {
            in 0x0000..0x7FFF -> ram[location] = value.toByte()
            in 0xFE00..0xFEFF -> writeSheila(location, value and 0xFF)
            // ROM, FRED and JIM with nothing fitted ignore writes.
        }
    }

    /** Debugger read without changing time or device state. */
    fun peek(address: Int): Int = readMapped(address and 0xFFFF, sideEffects = false)

    private fun readMapped(address: Int, sideEffects: Boolean): Int = when (address) {
        in 0x0000..0x7FFF -> ram[address].toInt() and 0xFF
        in 0x8000..0xBFFF -> roms.read(address)
        in 0xFC00..0xFDFF -> EMPTY_ONE_MHZ_BUS
        in 0xFE00..0xFEFF -> readSheila(address, sideEffects)
        else -> os[address and 0x3FFF].toInt() and 0xFF
    }

    private fun readSheila(address: Int, sideEffects: Boolean): Int {
        val region = SheilaRegion.of(address)
        if (region == SheilaRegion.ROMSEL) return UNMAPPED_SHEILA
        val device = devices[region.ordinal]
            ?: return if (region == SheilaRegion.FDC) EMPTY_DISC_SLOT else UNMAPPED_SHEILA
        val offset = (address and 0xFF) - region.first
        return if (sideEffects) device.read(offset) else device.peek(offset)
    }

    private fun writeSheila(address: Int, value: Int) {
        val region = SheilaRegion.of(address)
        if (region == SheilaRegion.ROMSEL) {
            roms.select(value)
            return
        }
        devices[region.ordinal]?.write((address and 0xFF) - region.first, value)
    }

    private fun advanceForAccess(address: Int) {
        val oneMhz = when (address) {
            in 0xFC00..0xFDFF -> true
            in 0xFE00..0xFEFF -> SheilaRegion.of(address).oneMhz
            else -> false
        }
        if (oneMhz) clock.stretchedCycle() else clock.cycle()
    }

    companion object {
        const val UNMAPPED_SHEILA = 0xFE
        const val EMPTY_ONE_MHZ_BUS = 0xFF
        const val EMPTY_DISC_SLOT = 0xFF
    }
}
