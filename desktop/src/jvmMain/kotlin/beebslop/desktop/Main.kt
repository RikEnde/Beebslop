// SPDX-FileCopyrightText: 2026 The Beebslop contributors
// SPDX-License-Identifier: BSD-3-Clause

package beebslop.desktop

import beebslop.bbc.BbcMachine
import beebslop.bbc.KeyboardMatrix.Key
import beebslop.bbc.Typist
import beebslop.bbc.Video
import java.awt.GraphicsEnvironment
import java.awt.image.BufferedImage
import java.io.File
import java.io.IOException
import javax.imageio.ImageIO
import javax.swing.JOptionPane
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

/** Frames to wait after power-on before typing: the OS needs about 2 s to boot. */
const val BOOT_FRAMES = 100

/** How long SHIFT is held for SHIFT+BREAK: the OS reads it while it restarts. */
const val SHIFT_BREAK_FRAMES = 25

const val USAGE = """Usage: beebslop [options]
  --roms DIR          Directory with os.rom, basic.rom and dnfs.rom (default: roms)
  --scale 1|2         Window size; 1 is 704 x 528 (default: 1)
  --screenshot FILE   Run without a window, save a PNG of the picture, then exit
  --frames N          50ths of a second to run before the screenshot (default: 150)
  --tape FILE         Put a UEF cassette image in the recorder
  --disc FILE         Put an SSD or DSD disc image in drive 0
  --disc1 FILE        Put an SSD or DSD disc image in drive 1
  --boot              Press SHIFT+BREAK at power-on, to boot the disc in drive 0
  --no-dfs            Leave out the disc interface, even if dnfs.rom is there
  --type TEXT         Type TEXT after boot; \n starts a new line
  --no-sound          Run without sound
  --log-keys          Print each host key event and the BBC key it maps to"""

data class Options(
    val romDirectory: File = File("roms"),
    val scale: Int = 1,
    val screenshot: File? = null,
    val screenshotFrames: Int = 150,
    val logKeys: Boolean = false,
    val sound: Boolean = true,
    val tape: File? = null,
    val discs: List<File?> = listOf(null, null),
    val boot: Boolean = false,
    val dfs: Boolean = true,
    val typeText: String? = null,
) {
    companion object {
        fun parse(args: Array<String>): Options {
            var options = Options()
            var i = 0
            fun value(): String = args.getOrNull(++i) ?: throw IllegalArgumentException("${args[i - 1]} needs a value")
            while (i < args.size) {
                options = when (val arg = args[i]) {
                    "--roms" -> options.copy(romDirectory = File(value()))
                    "--scale" -> options.copy(scale = value().toIntOrNull()?.takeIf { it in 1..2 }
                        ?: throw IllegalArgumentException("--scale must be 1 or 2"))
                    "--screenshot" -> options.copy(screenshot = File(value()))
                    "--frames" -> options.copy(screenshotFrames = value().toIntOrNull()?.takeIf { it > 0 }
                        ?: throw IllegalArgumentException("--frames must be a positive number"))
                    "--log-keys" -> options.copy(logKeys = true)
                    "--no-sound" -> options.copy(sound = false)
                    "--tape" -> options.copy(tape = File(value()))
                    "--disc" -> options.copy(discs = listOf(File(value()), options.discs[1]))
                    "--disc1" -> options.copy(discs = listOf(options.discs[0], File(value())))
                    "--boot" -> options.copy(boot = true)
                    "--no-dfs" -> options.copy(dfs = false)
                    "--type" -> options.copy(typeText = value().replace("\\n", "\n"))
                    "--help", "-h" -> {
                        println(USAGE)
                        exitProcess(0)
                    }
                    else -> throw IllegalArgumentException("Unknown option: $arg")
                }
                i++
            }
            return options
        }
    }
}

/**
 * The ROMs, from roms/ by default (© Acorn): the two a standard Model B needs,
 * and the disc filing system, which fits the disc interface if present.
 */
class Roms(val os: ByteArray, val basic: ByteArray, val dfs: ByteArray?) {
    fun newMachine() = BbcMachine(os, basic, dfs)

    companion object {
        const val DFS = "dnfs.rom"

        /** Loads the ROMs; without [dfs], or without dnfs.rom, there is no disc interface. */
        fun load(directory: File, dfs: Boolean = true): Roms {
            fun read(name: String): ByteArray {
                val file = File(directory, name)
                if (!file.isFile) throw IOException("Missing ${file.absolutePath}")
                val bytes = file.readBytes()
                if (bytes.size != 0x4000) throw IOException("${file.absolutePath} must be 16 KiB, was ${bytes.size} bytes")
                return bytes
            }
            val disc = if (dfs && File(directory, DFS).isFile) read(DFS) else null
            return Roms(read("os.rom"), read("basic.rom"), disc)
        }
    }
}

/**
 * Runs [Options.screenshotFrames] frames (50ths of a second) as fast as possible,
 * with the tape, discs, boot and text from [options], and writes the picture to
 * [file]. Disc changes are not saved.
 */
fun saveScreenshot(roms: Roms, options: Options, file: File) {
    val machine = roms.newMachine()
    options.tape?.let { machine.serialUla.tape = TapeFile.load(it) }
    for ((drive, disc) in options.discs.withIndex()) {
        if (disc != null) machine.fdc?.drives?.get(drive)?.disc = DiscFile.load(disc)
    }
    val typist = Typist()
    if (options.boot) typist.enqueueChord(listOf(Key.SHIFT), holdFrames = SHIFT_BREAK_FRAMES) { it.reset() }
    options.typeText?.let { typist.enqueue(it, delayFrames = BOOT_FRAMES) }
    repeat(options.screenshotFrames) {
        typist.onFrame(machine)
        Emulator.runFrame(machine)
    }
    val view = ScreenPanel.VIEW
    val image = BufferedImage(view.width, view.height, BufferedImage.TYPE_INT_RGB)
    image.setRGB(0, 0, view.width, view.height, machine.video.pixels, view.y * Video.WIDTH + view.x, Video.WIDTH)
    file.absoluteFile.parentFile?.mkdirs()
    ImageIO.write(image, "png", file)
}

fun main(args: Array<String>) {
    System.setProperty("apple.awt.application.name", "Beebslop")
    System.setProperty("apple.laf.useScreenMenuBar", "true")

    val options = try {
        Options.parse(args)
    } catch (e: IllegalArgumentException) {
        System.err.println(e.message)
        System.err.println(USAGE)
        exitProcess(2)
    }
    val roms = try {
        Roms.load(options.romDirectory, options.dfs)
    } catch (e: IOException) {
        val message = "${e.message}\n\nPut the BBC Micro os.rom (MOS 1.20) and basic.rom (BASIC II) in that directory, " +
            "and dnfs.rom (DNFS 1.20) for discs, or pass --roms DIR."
        System.err.println(message)
        if (options.screenshot == null && !GraphicsEnvironment.isHeadless()) {
            JOptionPane.showMessageDialog(null, message, "Beebslop", JOptionPane.ERROR_MESSAGE)
        }
        exitProcess(1)
    }
    if (roms.dfs == null && options.discs.any { it != null }) {
        System.err.println("--disc needs the disc interface: put dnfs.rom in ${options.romDirectory.path}, and don't pass --no-dfs.")
        exitProcess(2)
    }
    options.screenshot?.let {
        try {
            saveScreenshot(roms, options, it)
        } catch (e: IOException) {
            System.err.println("Couldn't load a tape or disc: ${e.message}")
            exitProcess(1)
        }
        println("Saved ${it.path}")
        return
    }
    SwingUtilities.invokeLater { EmulatorWindow(roms, options).show() }
}
