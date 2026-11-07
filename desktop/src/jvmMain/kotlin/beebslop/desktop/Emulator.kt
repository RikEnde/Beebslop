// SPDX-FileCopyrightText: 2026 The Beebslop contributors
// SPDX-License-Identifier: BSD-3-Clause

package beebslop.desktop

import beebslop.bbc.BbcClock
import beebslop.bbc.BbcMachine
import beebslop.bbc.KeyboardMatrix.Key
import beebslop.bbc.KeyboardText
import beebslop.bbc.Typist
import beebslop.bbc.Video
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Runs one BBC Micro on a dedicated thread in real time, one field (a 50th of a
 * second) at a time.
 *
 * Only the emulation thread touches the machine. Other threads talk to it
 * through [post] and read the picture through [copyFrame]. The thread sleeps to
 * match emulated time to wall time.
 */
class Emulator(
    private val createMachine: () -> BbcMachine,
    private val listener: Listener,
) {
    interface Listener {
        /** Called on the emulation thread after each completed field. */
        fun frameReady(frameCount: Long)

        /** Called on the emulation thread when the machine stops on an error. */
        fun stopped(error: Throwable)

        /** Called on the emulation thread when the CAPS LOCK or SHIFT LOCK LED changes. */
        fun ledsChanged(capsLock: Boolean, shiftLock: Boolean) {}
    }

    private val commands = ConcurrentLinkedQueue<(BbcMachine) -> Unit>()
    private val frameLock = Any()
    private val published = IntArray(Video.WIDTH * Video.HEIGHT)
    private val typist = Typist()
    private val natural = NaturalKeyboard()
    private var machine = createMachine()
    private var thread: Thread? = null
    private var leds: Pair<Boolean, Boolean>? = null

    @Volatile private var running = false
    @Volatile var paused = false
    @Volatile var frameCount = 0L
        private set

    fun start() {
        check(thread == null)
        running = true
        thread = Thread(::run, "bbc-emulation").apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun stop() {
        running = false
        thread?.join(1000)
        thread = null
    }

    /** Run [command] on the emulation thread before the next field. */
    fun post(command: (BbcMachine) -> Unit) { commands += command }

    fun press(key: Key) = post { it.keyboard.press(key) }
    fun release(key: Key) = post { it.keyboard.release(key) }

    /** Let go of every key, for when key-up events may have been lost. */
    fun releaseAll() = post {
        it.keyboard.releaseAll()
        natural.reset()
    }

    fun naturalModifier(key: Key, down: Boolean) = post { natural.modifier(key, down) }
    fun naturalKeyDown(hostKey: Int, stroke: KeyMapping.NaturalStroke) = post { natural.keyDown(hostKey, stroke) }
    fun naturalKeyUp(hostKey: Int) = post { natural.keyUp(hostKey) }

    /** BREAK: resets the CPU and user VIA; RAM survives, so BASIC programs can be OLDed. */
    fun pressBreak() = post { it.reset() }

    /**
     * BREAK with [modifier] held, as the OS reads it while restarting:
     * CTRL gives a cold start (memory cleared), SHIFT an autoboot.
     */
    fun pressBreakWith(modifier: Key) = post {
        typist.enqueueChord(listOf(modifier), holdFrames = MODIFIER_BREAK_FRAMES) { machine -> machine.reset() }
    }

    /** Press keys together briefly, for keys a menu click can't hold down. */
    fun tap(vararg keys: Key) = post { typist.enqueueChord(keys.toList()) }

    /** Type [text]; characters the BBC cannot type are skipped and returned. */
    fun type(text: String, delayFrames: Int = 0): Set<Char> {
        val skipped = text.filter { it != '\t' && it != '\r' && !KeyboardText.canType(it) }.toSet()
        post { typist.enqueue(text, delayFrames) }
        return skipped
    }

    fun cancelTyping() = post { typist.cancel(it) }

    /** Switch off and on: fresh RAM and chips, and a cold start. */
    fun powerCycle() = post {
        typist.cancel(it)
        natural.reset()
        machine = createMachine()
        paused = false
    }

    fun copyFrame(destination: IntArray) {
        synchronized(frameLock) { published.copyInto(destination) }
    }

    private fun run() {
        var paceStart = System.nanoTime()
        var paceTicks = 0L
        while (running) {
            while (true) commands.poll()?.invoke(machine) ?: break
            if (paused) {
                Thread.sleep(10)
                paceStart = System.nanoTime()
                paceTicks = 0
                continue
            }
            val elapsed = try {
                runField()
            } catch (e: Exception) {
                paused = true
                listener.stopped(e)
                continue
            }
            paceTicks += elapsed
            val ahead = paceStart + paceTicks * NANOS_PER_TICK - System.nanoTime()
            if (ahead > 0) {
                Thread.sleep(ahead / 1_000_000, (ahead % 1_000_000).toInt())
            } else if (ahead < -100_000_000) {
                // Fell far behind (host stall): don't try to catch up.
                paceStart = System.nanoTime()
                paceTicks = 0
            }
        }
    }

    /** Runs one frame and publishes the picture; returns the elapsed machine ticks. */
    private fun runField(): Long {
        val machine = machine
        typist.onFrame(machine)
        natural.onFrame(machine.keyboard)
        val elapsed = runFrame(machine)
        synchronized(frameLock) { machine.video.pixels.copyInto(published) }
        listener.frameReady(++frameCount)
        val lit = machine.latch.capsLockLed to machine.latch.shiftLockLed
        if (lit != leds) {
            leds = lit
            listener.ledsChanged(lit.first, lit.second)
        }
        if (machine.cpu.halted) {
            // Like the real machine, a jammed CPU leaves the display running and
            // only BREAK recovers it. Pause so the user knows why it froze.
            paused = true
            listener.stopped(IllegalStateException("The 6502 executed a JAM opcode and halted."))
        }
        return elapsed
    }

    companion object {
        private const val MODIFIER_BREAK_FRAMES = 25
        private const val NANOS_PER_TICK = 1_000_000_000L / BbcClock.TICKS_PER_SECOND
        private const val MIN_FRAME_TICKS = 35_000L // 17.5 ms
        private const val MAX_FRAME_TICKS = 80_000L // 40 ms

        /**
         * Runs one frame: at least 17.5 ms of machine time, then on to the next
         * vertical sync, so at the normal 50 Hz each frame is one whole field.
         * A 6845 that is not yet programmed (at power-on) or syncs at an odd rate
         * still gives frames of 17.5-40 ms. Returns the elapsed machine ticks.
         */
        fun runFrame(machine: BbcMachine): Long {
            val start = machine.clock.ticks
            while (machine.clock.ticks - start < MIN_FRAME_TICKS) machine.step()
            val field = machine.video.fields
            while (machine.video.fields == field && machine.clock.ticks - start < MAX_FRAME_TICKS) machine.step()
            return machine.clock.ticks - start
        }
    }
}
