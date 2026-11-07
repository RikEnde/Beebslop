// SPDX-FileCopyrightText: 2026 The Beebslop contributors
// SPDX-License-Identifier: BSD-3-Clause

package beebslop.bbc

/**
 * The serial ULA (*Advanced User Guide*, 20.9): the cassette interface, the
 * cassette motor relay, and the baud rate clocks for the [acia]. Its control
 * register at `&FE10-&FE17` is write-only:
 *
 * - bit 7: motor on; bit 6: RS423 (1) or cassette (0);
 * - bits 3-5 and 0-2: receive and transmit rates, 16 MHz / 13 divided by 1, 16,
 *   4, 128, 2, 64, 8 or 256, for the ACIA to divide again.
 *
 * A read returns 0 and has the same effect as writing `&FE`, the high byte of
 * the address left on the data bus. It starts as `&64`.
 *
 * The cassette works in tape cells of 1/1200 s ([TapeBit]), 1664 machine
 * ticks: 16 MHz / 13 / 1024. While the motor runs, each cell either plays from
 * the [tape] or, while the ACIA holds RTS low with the cassette selected,
 * records the ACIA's transmit data. Playing, a ZERO or ONE cell puts that bit
 * on the ACIA's receive data and gives it 16 receive clocks, so the ACIA set to
 * divide by 16 reads 1200 baud and by 64 reads 300. More than 100 ONEs in a row,
 * a high tone, raise DCD; a ZERO or silence lowers it. This follows MAME's
 * `acorn_serproc.cpp`, at the level of decoded cells instead of waveforms.
 *
 * Nothing is connected to the RS423 port: its input idles and CTS stays low.
 */
class SerialUla(private val acia: Acia6850) : IoDevice {
    var control: Int = 0x64
        private set

    /** The cassette in the recorder, or none. */
    var tape: CassettePort? = null

    val motorOn: Boolean get() = control and 0x80 != 0
    val rs423Selected: Boolean get() = control and 0x40 != 0

    /** The motor runs and the ACIA is listening to the tape, not recording. */
    val playing: Boolean get() = motorOn && !rs423Selected && acia.rts

    /** The motor runs and the ACIA is recording to tape. */
    val recording: Boolean get() = motorOn && !rs423Selected && !acia.rts

    private var cellTicks = 0
    private var highTones = 0
    private var carrier = false
    private var cassetteData = true
    private var transmitPhase = 0

    init {
        updateAcia()
    }

    override fun read(offset: Int): Int {
        write(offset, 0xFE)
        return 0
    }

    override fun peek(offset: Int): Int = 0

    override fun write(offset: Int, value: Int) {
        val wasOn = motorOn
        control = value and 0xFF
        if (motorOn != wasOn) cellTicks = 0
        updateAcia()
    }

    /** One 2 MHz machine tick. */
    fun tick() {
        // TxC: 16 MHz / 13 / n is one clock every 13n/8 machine ticks.
        transmitPhase += 8
        val period = 13 * CLOCK_DIVIDERS[control and 0x07]
        if (transmitPhase >= period) {
            transmitPhase -= period
            acia.transmitClock()
        }
        if (!motorOn) return
        if (++cellTicks < CELL_TICKS) return
        cellTicks = 0
        val port = tape
        when {
            recording -> port?.writeBit(if (acia.txd) TapeBit.ONE else TapeBit.ZERO)
            rs423Selected -> port?.readBit() // The tape moves; nothing listens.
            else -> receive(port?.readBit() ?: TapeBit.SILENCE)
        }
    }

    private fun receive(cell: TapeBit) {
        when (cell) {
            TapeBit.SILENCE -> {
                highTones = 0
                carrier = false
                updateAcia()
                return
            }
            TapeBit.ZERO -> {
                highTones = 0
                carrier = false
                cassetteData = false
            }
            TapeBit.ONE -> {
                if (++highTones > HIGH_TONE_CELLS) carrier = true
                cassetteData = true
            }
        }
        updateAcia()
        repeat(16) { acia.receiveClock() }
    }

    private fun updateAcia() {
        acia.dcd = !rs423Selected && carrier
        acia.rxd = rs423Selected || cassetteData
        if (!rs423Selected) acia.cts = false
    }

    companion object {
        /** Machine ticks per tape cell. */
        const val CELL_TICKS = 1664

        /** ONE cells before the serial ULA reports a high tone. */
        private const val HIGH_TONE_CELLS = 100

        private val CLOCK_DIVIDERS = intArrayOf(1, 16, 4, 128, 2, 64, 8, 256)
    }
}
