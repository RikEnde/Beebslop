// SPDX-FileCopyrightText: 2026 The Beebslop contributors
// SPDX-License-Identifier: BSD-3-Clause

package beebslop.bbc

/**
 * Machine time in 0.5-microsecond (2 MHz) ticks.
 *
 * The CPU normally takes one tick per bus cycle: the DRAM runs at 4 MHz and
 * interleaves CPU and video accesses, so the display never holds the CPU up.
 *
 * Accesses to 1 MHz devices are stretched instead. The cycle is lengthened until
 * its end lines up with the 1 MHz clock, making it "twice or three times its
 * normal length" depending on the phase it started in
 * ([BeebWiki: Cycle stretching](https://beebwiki.mdfs.net/Cycle_stretching)).
 * Here the 1 MHz clock's edges fall on even tick counts, so a stretched cycle
 * that starts on an even tick takes 2 ticks, and one that starts on an odd tick
 * takes 3.
 */
class BbcClock {
    var ticks: Long = 0
        private set

    /** Called after each tick with the new time; the machine clocks its chips here. */
    var onTick: ((Long) -> Unit)? = null

    /** True when the current time is on a 1 MHz clock edge. */
    val onOneMhzEdge: Boolean get() = ticks and 1L == 0L

    /** Advance time without the CPU (also useful in tests). */
    fun advance(count: Int) {
        require(count >= 0)
        val listener = onTick
        if (listener == null) {
            ticks += count
            return
        }
        repeat(count) {
            ticks++
            listener(ticks)
        }
    }

    /** One ordinary 2 MHz bus cycle. */
    internal fun cycle() = advance(1)

    /** One bus cycle to a 1 MHz device: ends on the first 1 MHz edge at least 2 ticks away. */
    internal fun stretchedCycle() = advance(if (onOneMhzEdge) 2 else 3)

    companion object {
        const val TICKS_PER_SECOND = 2_000_000
    }
}
