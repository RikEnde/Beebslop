// SPDX-FileCopyrightText: 2026 The Beebslop contributors
// SPDX-License-Identifier: BSD-3-Clause

package beebslop.bbc

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

private const val VDU_CODE = 0x2000
private const val VDU_DATA = 0x2100

/**
 * Sends [bytes] through the MOS's OSWRCH, as a program would. A small routine is
 * placed at &2000 and the CPU jumps to it once the OS is idle at the BASIC
 * prompt; it ends in a loop, leaving interrupts running.
 */
fun BbcMachine.vdu(vararg bytes: Int) {
    require(bytes.size in 1..255)
    val code = intArrayOf(
        0xA2, 0x00, // LDX #0
        0xBD, VDU_DATA and 0xFF, VDU_DATA shr 8, // LDA data,X
        0x20, 0xEE, 0xFF, // JSR OSWRCH
        0xE8, // INX
        0xE0, bytes.size, // CPX #n
        0xD0, 0xF5, // BNE back to LDA
        0x4C, (VDU_CODE + 13) and 0xFF, (VDU_CODE + 13) shr 8, // JMP to itself
    )
    code.forEachIndexed { i, b -> ram[VDU_CODE + i] = b.toByte() }
    bytes.forEachIndexed { i, b -> ram[VDU_DATA + i] = b.toByte() }
    // Wait for an instruction boundary with interrupts enabled, either in the OS
    // or in this routine's final loop from an earlier call.
    val limit0 = clock.ticks + BbcClock.TICKS_PER_SECOND
    while (true) {
        val state = cpu.state()
        val idle = state.programCounter >= 0xC000 || state.programCounter == VDU_CODE + 13
        if (idle && state.status and 0x04 == 0) break
        check(clock.ticks < limit0) { "The OS did not become idle" }
        step()
    }
    cpu.loadState(cpu.state().copy(programCounter = VDU_CODE))
    val limit = clock.ticks + 4 * BbcClock.TICKS_PER_SECOND
    while (cpu.state().programCounter != VDU_CODE + 13) {
        check(clock.ticks < limit) { "VDU output did not finish" }
        step()
    }
}

/** Writes the current picture as a PNG. */
fun BbcMachine.savePicture(file: File) {
    val image = BufferedImage(Video.WIDTH, Video.HEIGHT, BufferedImage.TYPE_INT_RGB)
    image.setRGB(0, 0, Video.WIDTH, Video.HEIGHT, video.pixels, 0, Video.WIDTH)
    file.parentFile.mkdirs()
    ImageIO.write(image, "png", file)
}

fun BbcMachine.pixel(x: Int, y: Int): Int = video.pixels[y * Video.WIDTH + x]
