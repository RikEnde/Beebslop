// SPDX-FileCopyrightText: 2026 The Beebslop contributors
// SPDX-License-Identifier: BSD-3-Clause

package beebslop.bbc

/**
 * The 6850 ACIA at `&FE08` (control and status) and `&FE09` (data), repeated
 * through `&FE0F`. It serves the cassette and RS423 (*Advanced User Guide*,
 * 20.3). Register and interrupt behavior follows MAME's `6850acia.cpp`.
 *
 * It must be present for the OS to run: the OS's interrupt handler checks the
 * ACIA's status first, and an absent device reading `&FE` would look like an
 * ACIA interrupt that never clears.
 *
 * Only registers and interrupts so far: no bits are sent or received.
 * Transmitted bytes are dropped, and the transmit register is empty again at
 * once. CTS and DCD are inputs that stay low until the serial ULA drives them.
 */
class Acia6850 : IoDevice {
    var control: Int = MASTER_RESET
        private set

    private var status = TDRE
    private var receiveData = 0

    /** Clear To Send input; high stops transmission. */
    var cts: Boolean = false
        set(value) { field = value; updateStatus() }

    /** Data Carrier Detect input. */
    var dcd: Boolean = false
        set(value) { field = value; updateStatus() }

    val inMasterReset: Boolean get() = control and 0x03 == MASTER_RESET
    private val transmitInterruptEnabled get() = (control shr 5) and 0x03 == 1
    private val receiveInterruptEnabled get() = control and 0x80 != 0

    val irq: Boolean
        get() = !inMasterReset && (
            (transmitInterruptEnabled && status and TDRE != 0 && !cts) ||
                (receiveInterruptEnabled && status and RDRF != 0)
            )

    override fun read(offset: Int): Int =
        if (offset and 1 == 0) peek(offset) else { status = status and (RDRF or OVRN).inv(); receiveData }

    override fun peek(offset: Int): Int {
        if (offset and 1 != 0) return receiveData
        var value = status
        if (inMasterReset || cts) value = value and TDRE.inv()
        if (irq) value = value or IRQ
        return value
    }

    override fun write(offset: Int, value: Int) {
        if (offset and 1 == 0) {
            control = value and 0xFF
            if (inMasterReset) status = TDRE
            updateStatus()
        }
        // Transmit data: no serial line yet, so the byte goes nowhere.
    }

    private fun updateStatus() {
        status = (status and (CTS or DCD).inv()) or (if (cts) CTS else 0) or (if (dcd) DCD else 0)
    }

    companion object {
        const val RDRF = 0x01
        const val TDRE = 0x02
        const val DCD = 0x04
        const val CTS = 0x08
        const val OVRN = 0x20
        const val IRQ = 0x80
        private const val MASTER_RESET = 0x03
    }
}

/**
 * The serial ULA's write-only control register at `&FE10-&FE17` (*Advanced User
 * Guide*, 20.9): baud rates, cassette or RS423, and the cassette motor relay.
 *
 * A read returns 0 and has the same effect as writing `&FE`, the high byte of the
 * address left on the data bus (MAME `acorn_serproc.cpp`).
 *
 * Only the register so far; the cassette and RS423 come later.
 */
class SerialUla : IoDevice {
    var control: Int = 0
        private set

    /** Bit 7: the cassette motor relay. */
    val motorOn: Boolean get() = control and 0x80 != 0

    /** Bit 6: RS423 rather than cassette. */
    val rs423Selected: Boolean get() = control and 0x40 != 0

    override fun read(offset: Int): Int {
        control = 0xFE
        return 0
    }

    override fun peek(offset: Int): Int = 0

    override fun write(offset: Int, value: Int) {
        control = value and 0xFF
    }
}
