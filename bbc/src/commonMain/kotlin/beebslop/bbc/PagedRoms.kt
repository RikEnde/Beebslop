// SPDX-FileCopyrightText: 2026 The Beebslop contributors
// SPDX-License-Identifier: BSD-3-Clause

package beebslop.bbc

/**
 * The four paged ROM sockets on the Model B board, at `&8000-&BFFF`, and the
 * paged ROM select register (ROMSEL, a 74LS161 at `&FE30`).
 *
 * The *Advanced User Guide* (chapter 21) numbers the sockets as ROMs 12, 13, 14
 * and 15 (IC52, IC88, IC100 and IC101), with 15 the highest priority. ROMSEL is
 * write-only. Without a sideways ROM expansion board, only its two low bits
 * select a socket, so every ROM appears under four ROM numbers: ROM 15 also
 * answers as 3, 7 and 11 (MAME `bbcb.cpp`, `romsel_w`).
 */
class PagedRoms {
    private val sockets = arrayOfNulls<ByteArray>(SOCKETS)

    /** The value last written to ROMSEL, low 4 bits. */
    var romsel: Int = 0
        private set

    /** The board socket selected, 0-3, where 3 is ROM 15. */
    val selectedSocket: Int get() = romsel and (SOCKETS - 1)

    /** Fit a 16 KiB ROM in the socket for [romNumber], 12-15. */
    fun install(romNumber: Int, image: ByteArray) {
        require(romNumber in 12..15) { "The Model B board has sockets for ROMs 12-15" }
        require(image.size == ROM_SIZE) { "Paged ROM must be 16 KiB" }
        sockets[romNumber and (SOCKETS - 1)] = image.copyOf()
    }

    fun remove(romNumber: Int) {
        require(romNumber in 12..15) { "The Model B board has sockets for ROMs 12-15" }
        sockets[romNumber and (SOCKETS - 1)] = null
    }

    fun select(value: Int) {
        romsel = value and 0x0F
    }

    /** An empty socket reads `&FF`, as in MAME. */
    fun read(address: Int): Int =
        sockets[selectedSocket]?.get(address and (ROM_SIZE - 1))?.toInt()?.and(0xFF) ?: EMPTY_SOCKET

    companion object {
        const val SOCKETS = 4
        const val ROM_SIZE = 0x4000
        const val EMPTY_SOCKET = 0xFF
    }
}
