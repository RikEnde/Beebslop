// SPDX-FileCopyrightText: 2026 The Beebslop contributors
// SPDX-License-Identifier: BSD-3-Clause

package beebslop.desktop

import beebslop.bbc.BbcClock
import beebslop.bbc.BbcMachine
import beebslop.bbc.Tape
import beebslop.bbc.TapeBit
import beebslop.bbc.Typist
import java.io.File
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TapeTest {
    private val romDirectory = File(System.getProperty("beebslop.romDirectory") ?: "../roms")
    private val os = File(romDirectory, "os.rom")
    private val basic = File(romDirectory, "basic.rom")

    @Test
    fun savesGzippedUefAndLoadsItBack() {
        val tape = Tape.blank()
        repeat(500) { tape.writeBit(if (it % 7 == 0) TapeBit.ZERO else TapeBit.ONE) }
        val dir = File("build/test-tapes").apply { mkdirs() }
        val file = File(dir, "test.uef")
        TapeFile.save(tape.toUef(), file)
        assertEquals(0x1F, file.readBytes()[0].toInt() and 0xFF, "gzipped")
        val loaded = TapeFile.load(file)
        assertEquals((0 until 500).map { tape[it] }, (0 until loaded.length).map { loaded[it] })
        val plain = File(dir, "plain.uef").apply { writeBytes(tape.toUef()) }
        assertEquals(500, TapeFile.load(plain).length)
        assertFailsWith<IOException> { TapeFile.load(File(dir, "bad.uef").apply { writeText("not a tape") }) }
        dir.deleteRecursively()
    }

    @Test
    fun fastLoadingRunsFasterThanRealTime() {
        if (!os.isFile || !basic.isFile) return println("Skipping: no ROMs in ${romDirectory.absolutePath}")
        val tape = savedProgram()
        val motorOn = CountDownLatch(1)
        val motorOff = CountDownLatch(1)
        val emulator = Emulator(
            createMachine = { BbcMachine(os.readBytes(), basic.readBytes()) },
            soundEnabled = false,
            listener = object : Emulator.Listener {
                override fun frameReady(frameCount: Long) {}
                override fun stopped(error: Throwable) = throw AssertionError(error)
                override fun motorChanged(on: Boolean) = (if (on) motorOn else motorOff).countDown()
            },
        )
        emulator.fastTape = true
        emulator.insertTape(tape)
        emulator.type("LOAD \"PROG\"\n", delayFrames = BOOT_FRAMES)
        emulator.start()
        try {
            assertTrue(motorOn.await(30, TimeUnit.SECONDS), "MOS starts the motor")
            val startFrame = emulator.frameCount
            val start = System.nanoTime()
            assertTrue(motorOff.await(30, TimeUnit.SECONDS), "MOS stops the motor after loading")
            val seconds = (System.nanoTime() - start) / 1e9
            val speed = (emulator.frameCount - startFrame) / 50.0 / seconds
            assertTrue(speed > 3, "ran at ${speed}x real time")
            val screen = CompletableFuture<List<String>>()
            emulator.post { screen.complete(it.teletextLines().filter { line -> line.isNotEmpty() }) }
            assertEquals("PROG       00 0025", screen.get(5, TimeUnit.SECONDS).last { it.startsWith("PROG") })
        } finally {
            emulator.stop()
        }
    }

    /** A tape with one BASIC program on it, saved by MOS. */
    private fun savedProgram(): Tape {
        val machine = BbcMachine(os.readBytes(), basic.readBytes())
        val tape = Tape.blank()
        machine.serialUla.tape = tape
        val typist = Typist()
        typist.enqueue("10 PRINT \"HELLO FROM TAPE\"\n20 GOTO 10\nSAVE \"PROG\"\n\n", delayFrames = BOOT_FRAMES)
        var frames = 0
        while (typist.busy || machine.serialUla.motorOn || frames < BOOT_FRAMES + 50) {
            typist.onFrame(machine)
            machine.runForTicks(BbcClock.TICKS_PER_SECOND / 50L)
            check(++frames < 50 * 60) { "SAVE did not finish" }
        }
        tape.rewind()
        return tape
    }
}
