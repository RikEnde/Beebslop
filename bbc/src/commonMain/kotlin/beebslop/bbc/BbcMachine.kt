// SPDX-FileCopyrightText: 2026 The Beebslop contributors
// SPDX-License-Identifier: BSD-3-Clause

package beebslop.bbc

import beebslop.cpu.Cpu6502

/**
 * One BBC Micro Model B, as just powered on. UI, file loading and real-time
 * pacing live outside.
 *
 * BASIC goes in ROM 15, the highest-priority socket, as on a standard machine.
 *
 * Wiring, from MAME's `bbcb.cpp` and the *Advanced User Guide*, chapter 23:
 * - System VIA port A is the slow data bus to the keyboard and the SN76489;
 *   port B bits 0-3 drive the addressable latch, and bits 4-7 read 1 (no
 *   joystick buttons pressed, no speech chip).
 * - CA1 is the 6845's vertical sync; CA2 is the keyboard interrupt.
 * - IRQ is the OR of both VIAs and the ACIA.
 *
 * The ACIA and serial ULA have registers only. The disc and Econet controllers,
 * ADC and Tube are not yet emulated, and read as absent.
 */
class BbcMachine(osRom: ByteArray, basicRom: ByteArray) {
    val ram = ByteArray(0x8000)
    val clock = BbcClock()
    val roms = PagedRoms().apply { install(15, basicRom) }
    val bus = BbcMemoryBus(ram, osRom, roms, clock)
    val cpu = Cpu6502(bus)

    val keyboard = KeyboardMatrix()
    val latch = AddressableLatch()
    val sound = Sn76489()
    val crtc = Crtc6845()
    val videoUla = VideoUla()
    val acia = Acia6850()
    val serialUla = SerialUla()

    /** The slow data bus between system VIA port A, the keyboard and the sound chip. */
    private var slowDataBus = 0xFF
    private var soundWriting = false

    val systemVia = Via6522(object : ViaPorts {
        override fun readA(driven: Int): Int {
            slowDataBus = driven
            updateSlowDataBus()
            return slowDataBus
        }

        override fun writeA(driven: Int) {
            slowDataBus = driven
            updateSlowDataBus()
        }

        override fun writeB(driven: Int) {
            latch.write(driven)
            updateSlowDataBus()
        }
    })

    val userVia = Via6522()

    val video = Video(ram, latch, videoUla, crtc, clock) { systemVia.setCa1(it) }

    init {
        bus.attach(SheilaRegion.CRTC, crtc)
        bus.attach(SheilaRegion.ACIA, acia)
        bus.attach(SheilaRegion.SERIAL_ULA, serialUla)
        bus.attach(SheilaRegion.VIDEO_ULA, videoUla)
        bus.attach(SheilaRegion.SYSTEM_VIA, systemVia)
        bus.attach(SheilaRegion.USER_VIA, userVia)
        crtc.output = video
        clock.onTick = ::tick
        systemVia.reset()
        userVia.reset()
        cpu.reset()
    }

    /**
     * Press BREAK. It resets the CPU and the user VIA, but not the system VIA, so
     * the OS can tell BREAK from power-on (MAME `bbcb.cpp`, `trigger_reset`).
     */
    fun reset() {
        userVia.reset()
        cpu.reset()
    }

    /** Execute one CPU instruction or interrupt; return the elapsed machine ticks. */
    fun step(): Int {
        val before = clock.ticks
        cpu.irqAsserted = systemVia.irq || userVia.irq || acia.irq
        cpu.step()
        return (clock.ticks - before).toInt()
    }

    /** Run until at least [count] ticks have elapsed; stops at an instruction boundary. */
    fun runForTicks(count: Long) {
        require(count >= 0)
        val start = clock.ticks
        while (clock.ticks - start < count) step()
    }

    fun runForSeconds(seconds: Double) = runForTicks((seconds * BbcClock.TICKS_PER_SECOND).toLong())

    /**
     * The MODE 7 screen as text: 25 rows of R1 characters from the 6845 start
     * address. Codes below 32 (teletext control codes) show as spaces.
     */
    fun teletextLines(): List<String> {
        val width = crtc.register(1)
        return (0 until 25).map { row ->
            (0 until width).map { column ->
                val ma = (crtc.startAddress + row * width + column) and 0x3FFF
                val code = ram[ScreenAddress.of(ma, 0, latch.screenWrap) and 0x7FFF].toInt() and 0x7F
                if (code < 0x20) ' ' else code.toChar()
            }.joinToString("").trimEnd()
        }
    }

    private fun tick(ticks: Long) {
        sound.tick()
        val oneMhzEdge = ticks and 1L == 0L
        if (oneMhzEdge) {
            systemVia.tick()
            userVia.tick()
            if (!latch.keyboardPolled) {
                keyboard.scan()
                systemVia.setCa2(keyboard.keyInColumn)
            }
        }
        if (oneMhzEdge || videoUla.fastCharacterClock) crtc.tick()
    }

    private fun updateSlowDataBus() {
        // The SN76489 takes the byte as its write enable (latch B0) goes low.
        if (latch.soundWriteEnabled && !soundWriting) sound.write(slowDataBus)
        soundWriting = latch.soundWriteEnabled
        if (latch.keyboardPolled) {
            slowDataBus = keyboard.poll(slowDataBus)
            systemVia.setCa2(keyboard.keyInColumn)
        }
    }
}
