// SPDX-FileCopyrightText: 2026 The Beebslop contributors
// SPDX-License-Identifier: BSD-3-Clause

package beebslop.bbc

/**
 * The 6850 ACIA at `&FE08` (control and status) and `&FE09` (data), repeated
 * through `&FE0F`. It serves the cassette and RS423 (*Advanced User Guide*,
 * 20.3). The receiver, transmitter, DCD and interrupt logic follow MAME's
 * `6850acia.cpp`.
 *
 * It must be present for the OS to run: the OS's interrupt handler checks the
 * ACIA's status first, and an absent device reading `&FE` would look like an
 * ACIA interrupt that never clears.
 *
 * - The control register picks a clock divide (1, 16, 64, or master reset),
 *   the word format, RTS and the transmit interrupt, and the receive interrupt.
 * - [receiveClock] and [transmitClock] are the RxC and TxC pins; the serial ULA
 *   drives them. A bit lasts `divide` clocks. The receiver samples [rxd]; the
 *   transmitter drives [txd], in whole bit times even when idle, and sends
 *   waiting bytes back to back.
 * - While DCD is high the receiver is held at its start; DCD going high sets
 *   the status bit and interrupts, until the status and then the data register
 *   have been read. The BBC's serial ULA raises DCD on a long high tone.
 */
class Acia6850 : IoDevice {
    var control: Int = 0
        private set

    private var status = TDRE
    private var transmitData = 0
    private var receiveData = 0
    private var firstMasterReset = true
    private var overrunPending = false
    private var dcdInterrupt = DCD_IRQ_NONE

    private var divide = 0
    private var bits = 0
    private var parity = PARITY_NONE
    private var stopBits = 0
    private var brk = false
    private var transmitInterruptEnabled = false
    private var receiveInterruptEnabled = false

    /** Request To Send output, high after reset; low lets the serial ULA record to tape. */
    var rts: Boolean = true
        private set

    /** Transmit data output, idling at mark (1). */
    var txd: Boolean = true
        private set

    /** Receive data input. */
    var rxd: Boolean = true

    /** Data Carrier Detect input. */
    var dcd: Boolean = false

    /** Clear To Send input; high stops transmission. */
    var cts: Boolean = false
        set(value) {
            field = value
            status = if (value) status or CTS else status and CTS.inv()
        }

    private var rxState = STATE_START
    private var rxCounter = 0
    private var rxWaitMark = false
    private var rxShift = 0
    private var rxParity = 0
    private var rxBits = 0

    private var txState = STATE_START
    private var txCounter = 0
    private var txShift = 0
    private var txParity = 0
    private var txBits = 0

    val inMasterReset: Boolean get() = divide == 0

    val irq: Boolean
        get() = divide != 0 && (
            (transmitInterruptEnabled && status and TDRE != 0 && status and CTS == 0) ||
                (receiveInterruptEnabled && (status and RDRF != 0 || dcdInterrupt != DCD_IRQ_NONE))
            )

    override fun read(offset: Int): Int {
        if (offset and 1 == 0) {
            val value = peek(offset)
            if (dcdInterrupt == DCD_IRQ_READ_STATUS) dcdInterrupt = DCD_IRQ_READ_DATA
            return value
        }
        if (overrunPending) {
            status = status or OVRN
            overrunPending = false
        } else {
            status = status and (OVRN or RDRF).inv()
        }
        if (dcdInterrupt == DCD_IRQ_READ_DATA) dcdInterrupt = DCD_IRQ_NONE
        return receiveData
    }

    override fun peek(offset: Int): Int {
        if (offset and 1 != 0) return receiveData
        var value = status
        if (divide == 0 || value and CTS != 0) value = value and TDRE.inv()
        if (irq) value = value or IRQ
        return value
    }

    override fun write(offset: Int, value: Int) {
        if (offset and 1 != 0) {
            // Data written in master reset is ignored.
            if (divide != 0) {
                transmitData = value and 0xFF
                status = status and TDRE.inv()
            }
            return
        }
        control = value and 0xFF
        divide = DIVIDES[control and 0x03]
        val format = WORD_FORMATS[(control shr 2) and 0x07]
        bits = format[0]
        parity = format[1]
        stopBits = format[2]
        val transmitter = (control shr 5) and 0x03
        var newRts = transmitter == 2
        brk = transmitter == 3
        transmitInterruptEnabled = transmitter == 1
        receiveInterruptEnabled = control and 0x80 != 0
        if (divide == 0) {
            if (firstMasterReset) {
                newRts = true
                firstMasterReset = false
            }
            dcdInterrupt = DCD_IRQ_NONE
            overrunPending = false
            rxState = STATE_START
            rxCounter = 0
            rxWaitMark = false
            txState = STATE_START
            txd = true
            // TDRE reads 0 in reset but is set when the ACIA leaves it.
            status = (status or TDRE) and (CTS or TDRE)
            if (dcd) status = status or DCD
        }
        rts = newRts
    }

    /** One rising edge on the receive clock. */
    fun receiveClock() {
        if (divide == 0) return
        if (dcd) {
            if (status and DCD == 0) {
                status = status or DCD
                dcdInterrupt = DCD_IRQ_READ_STATUS
            }
            rxState = STATE_START
            rxCounter = 0
            return
        }
        if (dcdInterrupt == DCD_IRQ_NONE) status = status and DCD.inv()
        rxCounter++
        when (rxState) {
            STATE_START -> when {
                rxWaitMark -> {
                    // After a framing error, wait for the line to return to mark.
                    if (rxd) rxWaitMark = false
                    rxCounter = 0
                }
                !rxd -> if (rxCounter >= divide / 2) {
                    rxState = STATE_DATA
                    rxCounter = 0
                    rxShift = 0
                    rxParity = 0
                    rxBits = 0
                }
                else -> rxCounter = 0
            }
            STATE_DATA -> if (rxCounter == divide) {
                rxCounter = 0
                if (rxd) rxShift = rxShift or (1 shl rxBits)
                rxBits++
                rxParity = rxParity xor (if (rxd) 1 else 0)
                val complete = if (parity == PARITY_NONE) rxBits == bits else rxBits == bits + 1
                if (complete) {
                    if (status and RDRF != 0) {
                        overrunPending = true
                    } else {
                        if (parity == PARITY_ODD) rxParity = rxParity xor 1
                        status = if (parity != PARITY_NONE && rxParity != 0) status or PE else status and PE.inv()
                        receiveData = if (bits == 7 && parity != PARITY_NONE) rxShift and 0x7F else rxShift
                        status = status or RDRF
                    }
                    rxState = STATE_STOP
                }
            }
            else -> if (rxCounter == divide) {
                rxCounter = 0
                if (!rxd) {
                    status = status or FE
                    rxWaitMark = true
                } else {
                    status = status and FE.inv()
                }
                rxState = STATE_START
            }
        }
    }

    /** One falling edge on the transmit clock. */
    fun transmitClock() {
        if (divide == 0) return
        txCounter++
        when (txState) {
            // An idle transmitter still works in bit times: a byte written to it
            // starts at the next bit boundary. MOS's cassette output depends on
            // this gap. (MAME starts it on the next clock.)
            STATE_START -> if (txCounter >= divide) startFrame()
            STATE_DATA -> if (txCounter == divide) {
                txCounter = 0
                when {
                    txBits < bits -> {
                        txd = (txShift shr txBits) and 1 != 0
                        txBits++
                        txParity = txParity xor (if (txd) 1 else 0)
                    }
                    txBits == bits && parity != PARITY_NONE -> {
                        txBits++
                        if (parity == PARITY_ODD) txParity = txParity xor 1
                        txd = txParity != 0
                    }
                    else -> {
                        txState = STATE_STOP
                        txBits = 0
                        txd = true
                    }
                }
            }
            else -> if (txCounter == divide) {
                txCounter = 0
                txBits++
                if (txBits == stopBits) {
                    // A waiting byte follows at once, as on the real chip. (MAME
                    // waits one more clock, which drifts against the tape cells.)
                    txState = STATE_START
                    startFrame()
                }
            }
        }
    }

    private fun startFrame() {
        txCounter = 0
        if (status and TDRE == 0 && status and CTS == 0) {
            txState = STATE_DATA
            txShift = transmitData
            txBits = 0
            txParity = 0
            status = status or TDRE
            txd = false
        } else {
            txd = !brk
        }
    }

    companion object {
        const val RDRF = 0x01
        const val TDRE = 0x02
        const val DCD = 0x04
        const val CTS = 0x08
        const val FE = 0x10
        const val OVRN = 0x20
        const val PE = 0x40
        const val IRQ = 0x80

        private const val PARITY_NONE = 0
        private const val PARITY_ODD = 1
        private const val PARITY_EVEN = 2

        private const val STATE_START = 0
        private const val STATE_DATA = 1
        private const val STATE_STOP = 2

        private const val DCD_IRQ_NONE = 0
        private const val DCD_IRQ_READ_STATUS = 1
        private const val DCD_IRQ_READ_DATA = 2

        private val DIVIDES = intArrayOf(1, 16, 64, 0)

        /** Data bits, parity and stop bits for control bits 2-4. */
        private val WORD_FORMATS = arrayOf(
            intArrayOf(7, PARITY_EVEN, 2), intArrayOf(7, PARITY_ODD, 2),
            intArrayOf(7, PARITY_EVEN, 1), intArrayOf(7, PARITY_ODD, 1),
            intArrayOf(8, PARITY_NONE, 2), intArrayOf(8, PARITY_NONE, 1),
            intArrayOf(8, PARITY_EVEN, 1), intArrayOf(8, PARITY_ODD, 1),
        )
    }
}
