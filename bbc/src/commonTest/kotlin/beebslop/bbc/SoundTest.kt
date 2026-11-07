// SPDX-FileCopyrightText: 2026 The Beebslop contributors
// SPDX-License-Identifier: BSD-3-Clause

package beebslop.bbc

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SoundTest {
    /** A chip with only [channel] audible, at full volume. */
    private fun solo(channel: Int) = Sn76489().apply {
        for (c in 0..3) write(0x90 or (c shl 5) or (if (c == channel) 0 else 15))
    }

    /** Rising edges of the output over [ticks] machine ticks. */
    private fun Sn76489.risingEdges(ticks: Int): Int {
        var edges = 0
        var previous = level
        repeat(ticks) {
            tick()
            if (level > previous) edges++
            previous = level
        }
        return edges
    }

    @Test
    fun toneFrequencyIs250KhzOverTwiceThePeriod() {
        val sound = solo(0)
        sound.write(0x80 or 0x04) // tone 0, low bits 4
        sound.write(0x06) // high bits: period &64 = 100
        // 250 kHz / 200 = 1250 Hz.
        assertEquals(1250.0, sound.risingEdges(BbcClock.TICKS_PER_SECOND).toDouble(), 1.0)
    }

    @Test
    fun periodZeroActsAs1024() {
        val sound = solo(1)
        sound.write(0xA0) // tone 1, period 0
        sound.write(0x00)
        // 250 kHz / 2048 = 122 Hz.
        assertEquals(122.0, sound.risingEdges(BbcClock.TICKS_PER_SECOND).toDouble(), 1.0)
    }

    @Test
    fun attenuationIs2DbPerStepAnd15IsOff() {
        assertEquals(0.25f, Sn76489.VOLUME[0])
        for (i in 1..14) assertEquals(0.794, (Sn76489.VOLUME[i] / Sn76489.VOLUME[i - 1]).toDouble(), 0.001)
        assertEquals(0f, Sn76489.VOLUME[15])
        val sound = solo(0)
        sound.write(0x80 or 0x04)
        sound.write(0x06)
        sound.write(0x90 or 15)
        assertEquals(0, sound.risingEdges(100_000))
    }

    @Test
    fun periodicNoiseRepeatsAndWhiteNoiseDoesNot() {
        fun noiseBits(control: Int): List<Int> {
            val sound = solo(3)
            sound.write(0xE0 or control) // shift every 32 counts: 256 ticks
            return List(400) {
                repeat(256) { sound.tick() }
                if (sound.level > 0f) 1 else 0
            }
        }
        val periodic = noiseBits(0).drop(20)
        val repeat = (1..40).first { p -> periodic.indices.drop(p).all { periodic[it] == periodic[it - p] } }
        assertTrue(repeat <= 17, "periodic noise repeats every $repeat shifts")
        val white = noiseBits(4).drop(20)
        assertTrue((1..40).none { p -> white.indices.drop(p).all { white[it] == white[it - p] } }, "white noise")
        assertTrue(white.count { it == 1 } in 100..280, "white noise is about half on")
    }

    @Test
    fun samplesHaveNoDcOffset() {
        val sound = solo(0)
        sound.write(0x80 or 0x04)
        sound.write(0x06)
        sound.startSampling(48_000, 48_000)
        repeat(BbcClock.TICKS_PER_SECOND / 2) { sound.tick() }
        val samples = FloatArray(48_000)
        val count = sound.drainSamples(samples)
        assertEquals(24_000.0, count.toDouble(), 1.0)
        val settled = samples.copyOfRange(4_800, count)
        assertTrue(abs(settled.average()) < 0.005, "mean ${settled.average()}")
        assertTrue(settled.max() > 0.1f && settled.min() < -0.1f, "a square wave about 0.25 high")
    }
}
