// SPDX-FileCopyrightText: 2026 The Beebslop contributors
// SPDX-License-Identifier: BSD-3-Clause

package beebslop.bbc

import kotlin.math.pow

/**
 * The SN76489A sound generator: three square-wave tones and a noise channel.
 * It is written from the slow data bus when addressable latch B0 goes low
 * (*Advanced User Guide*, 23.3), and clocked at 4 MHz, which it divides by 16.
 * Time here is in 2 MHz machine ticks, so the counters step every 8 ticks:
 * 250 kHz.
 *
 * A byte with bit 7 set selects a register (bits 4-6) and sets its low bits; a
 * byte with bit 7 clear sets the high 6 bits of the selected tone register, or
 * replaces a volume or noise register. Channels 0-2 are tones, 3 is noise.
 *
 * - A tone's output flips every N counts, giving 250 kHz / 2N; N = 0 acts as 1024.
 * - Noise shifts a 16-bit register every 32, 64 or 128 counts, or at half the
 *   rate of tone 2's flips. Bit 2 of the noise register chooses white noise
 *   (taps 2 and 3) or periodic noise; writing the register resets the shifter.
 * - Each channel's attenuation is 2 dB per step, and 15 is off. At power-on all
 *   four are at full volume with tones at the lowest pitch, until MOS silences
 *   them: the BBC's familiar start-up beep.
 *
 * This follows MAME's `sn76496.cpp` (the SN76489A variant, as in `bbcb.cpp`).
 * The output is the sum of the four channels, 0 to 1; [startSampling] resamples
 * it for the host, removing the DC offset as the BBC's output capacitor does.
 */
class Sn76489 {
    /** 10-bit tone periods for channels 0-2. */
    val tone = IntArray(3)

    /** 4-bit attenuation for channels 0-3; 15 is silent. */
    val attenuation = IntArray(4)

    /** The noise control register, 3 bits. */
    var noise: Int = 0
        private set

    private var selected = 0
    private val count = IntArray(4)
    private val period = intArrayOf(0x400, 0x400, 0x400, 0)
    private val output = IntArray(4)
    private var shifter = FEEDBACK
    private var divider = DIVIDER - 1

    /** The current output, 0 to 1: the four channels' levels added. */
    var level: Float = 0f
        private set

    var writes: Long = 0
        private set

    init {
        output[3] = shifter and 1
        updateLevel()
    }

    fun write(value: Int) {
        writes++
        val data = value and 0xFF
        if (data and 0x80 != 0) selected = (data shr 4) and 0x07
        val channel = selected shr 1
        val isVolume = selected and 1 != 0
        when {
            isVolume -> attenuation[channel] = data and 0x0F
            channel == 3 -> {
                noise = data and 0x07
                shifter = FEEDBACK
            }
            data and 0x80 != 0 -> tone[channel] = (tone[channel] and 0x3F0) or (data and 0x0F)
            else -> tone[channel] = (tone[channel] and 0x00F) or ((data and 0x3F) shl 4)
        }
        if (channel < 3 && !isVolume) period[channel] = if (tone[channel] == 0) 0x400 else tone[channel]
        period[3] = if (noise and 3 == 3) period[2] shl 1 else 1 shl (5 + (noise and 3))
        updateLevel()
    }

    /** One 2 MHz machine tick. */
    fun tick() {
        if (divider > 0) {
            divider--
        } else {
            divider = DIVIDER - 1
            step()
        }
        if (sampleRate != 0) sample()
    }

    private fun step() {
        for (i in 0 until 3) {
            if (--count[i] <= 0) {
                output[i] = output[i] xor 1
                count[i] = period[i]
            }
        }
        if (--count[3] <= 0) {
            val white = noise and 0x04 != 0
            val feedback = (shifter and TAP1 != 0) != (white && shifter and TAP2 != 0)
            shifter = (shifter shr 1) or (if (feedback) FEEDBACK else 0)
            output[3] = shifter and 1
            count[3] = period[3]
        }
        updateLevel()
    }

    private fun updateLevel() {
        var sum = 0f
        for (i in 0 until 4) if (output[i] != 0) sum += VOLUME[attenuation[i]]
        level = sum
    }

    // Host-rate resampling. Each sample is the mean level over the ticks it
    // covers, a simple anti-alias filter, then a DC-blocking high-pass filter.
    private var sampleRate = 0
    private var samples = FloatArray(0)
    private var sampleCount = 0
    private var phase = 0
    private var levelSum = 0f
    private var levelTicks = 0
    private var lastInput = 0f
    private var lastOutput = 0f

    /** Samples available to [drainSamples]. */
    val bufferedSamples: Int get() = sampleCount

    /**
     * Start producing samples, about -1 to 1, at [rate] Hz. Up to [capacity]
     * samples are buffered; if the host does not drain them, newer ones are dropped.
     */
    fun startSampling(rate: Int, capacity: Int = rate / 5) {
        require(rate in 1..BbcClock.TICKS_PER_SECOND && capacity > 0)
        sampleRate = rate
        samples = FloatArray(capacity)
        sampleCount = 0
        phase = 0
        levelSum = 0f
        levelTicks = 0
        lastInput = level
        lastOutput = 0f
    }

    /** Move buffered samples into [destination]; returns how many were copied. */
    fun drainSamples(destination: FloatArray): Int {
        val n = minOf(sampleCount, destination.size)
        samples.copyInto(destination, 0, 0, n)
        samples.copyInto(samples, 0, n, sampleCount)
        sampleCount -= n
        return n
    }

    private fun sample() {
        levelSum += level
        levelTicks++
        phase += sampleRate
        if (phase < BbcClock.TICKS_PER_SECOND) return
        phase -= BbcClock.TICKS_PER_SECOND
        val input = levelSum / levelTicks
        lastOutput = input - lastInput + DC_BLOCK * lastOutput
        lastInput = input
        if (sampleCount < samples.size) samples[sampleCount++] = lastOutput
        levelSum = 0f
        levelTicks = 0
    }

    companion object {
        /** Machine ticks per counter step: 2 MHz / 250 kHz. */
        const val DIVIDER = 8

        private const val FEEDBACK = 0x10000
        private const val TAP1 = 0x04
        private const val TAP2 = 0x08

        /** Pole of the DC-blocking filter; about 40 Hz at 48 kHz. */
        private const val DC_BLOCK = 0.995f

        /** Channel level by attenuation: a quarter of full scale, less 2 dB a step. */
        val VOLUME = FloatArray(16) { i ->
            if (i == 15) 0f else (0.25 * 10.0.pow(-i / 10.0)).toFloat()
        }
    }
}
