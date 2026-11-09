// SPDX-FileCopyrightText: 2026 The Beebslop contributors
// SPDX-License-Identifier: BSD-3-Clause

package beebslop.desktop

import beebslop.bbc.BbcMachine
import beebslop.cpu.Disassembler
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
  --roms DIR          Directory with os.rom, basic.rom and dnfs.rom (default: built in)
  --scale 1|2         Window size; 1 is 704 x 528 (default: 1)
  --screenshot FILE   Run without a window, save a PNG of the picture, then exit
  --disassemble A-B   Run without a window, then print the machine code from hex address
                      A to B, as in --disassemble FFE3-FFF9; with --screenshot, the
                      picture comes from the same run
  --origin ADDR       Number the listing from hex ADDR, for code that is copied there to run
  --frames N          50ths of a second to run before the screenshot or listing (default: 150)
  --tape FILE         Put a UEF cassette image in the recorder
  --disc FILE         Put an SSD or DSD disc image in drive 0
  --disc1 FILE        Put an SSD or DSD disc image in drive 1
  --boot              Press SHIFT+BREAK at power-on, to boot the disc in drive 0
  --no-dfs            Leave out the disc interface, even if dnfs.rom is there
  --type TEXT         Type TEXT after boot; \n starts a new line
  --no-sound          Run without sound
  --log-keys          Print each host key event and the BBC key it maps to"""

data class Options(
    /** Null means the ROMs built into the jar. */
    val romDirectory: File? = null,
    val scale: Int = 1,
    val screenshot: File? = null,
    /** Frames to run before the screenshot or the listing. */
    val screenshotFrames: Int = 150,
    /** Addresses to disassemble after running headless, or null. */
    val disassemble: IntRange? = null,
    /** Where the code in [disassemble] runs from, if it is copied there first; null for where it is. */
    val origin: Int? = null,
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
                    "--disassemble" -> options.copy(disassemble = parseRange(value()))
                    "--origin" -> options.copy(origin = address(value())
                        ?: throw IllegalArgumentException("--origin needs a hex address, as in 0E00"))
                    "--help", "-h" -> {
                        println(USAGE)
                        exitProcess(0)
                    }
                    else -> throw IllegalArgumentException("Unknown option: $arg")
                }
                i++
            }
            if (options.origin != null && options.disassemble == null) {
                throw IllegalArgumentException("--origin only goes with --disassemble")
            }
            return options
        }

        /** A hex address such as `FFEE`, `$FFEE`, `&FFEE` or `0xFFEE`, or null. */
        private fun address(text: String): Int? =
            text.trim().removePrefix("$").removePrefix("&").removePrefix("0x")
                .toIntOrNull(16)?.takeIf { it in 0..0xFFFF }

        /** `FFE3-FFF9`, or `$FFE3-$FFF9`, in hex; a single address gives one instruction. */
        private fun parseRange(text: String): IntRange {
            val parts = text.split("-")
            val start = address(parts[0])
            val end = if (parts.size == 2) address(parts[1]) else start.takeIf { parts.size == 1 }
            if (start == null || end == null || end < start) {
                throw IllegalArgumentException("--disassemble needs hex addresses START-END, as in FFE3-FFF9")
            }
            return start..end
        }
    }
}

/**
 * The ROMs (© Acorn): the two a standard Model B needs, and the disc filing
 * system, which fits the disc interface if present. The build copies
 * roms/os.rom, roms/basic.rom and roms/dnfs.rom into the jar, so it runs from
 * anywhere; --roms DIR reads them from a directory instead.
 */
class Roms(val os: ByteArray, val basic: ByteArray, val dfs: ByteArray?) {
    fun newMachine() = BbcMachine(os, basic, dfs)

    companion object {
        const val DFS = "dnfs.rom"
        private const val RESOURCES = "/beebslop/roms/"

        /**
         * Loads the ROMs from [directory], or the built-in ones if it is null.
         * Without [dfs], or without dnfs.rom, there is no disc interface.
         */
        fun load(directory: File?, dfs: Boolean = true): Roms {
            fun builtIn(name: String) = Roms::class.java.getResourceAsStream(RESOURCES + name)
            fun read(name: String): ByteArray {
                val (where, bytes) = if (directory == null) {
                    val stream = builtIn(name) ?: throw IOException("The built-in $name is missing from the jar")
                    "Built-in $name" to stream.use { it.readBytes() }
                } else {
                    val file = File(directory, name)
                    if (!file.isFile) throw IOException("Missing ${file.absolutePath}")
                    file.absolutePath to file.readBytes()
                }
                if (bytes.size != 0x4000) throw IOException("$where must be 16 KiB, was ${bytes.size} bytes")
                return bytes
            }
            val hasDfs = if (directory == null) builtIn(DFS)?.use { true } == true else File(directory, DFS).isFile
            val disc = if (dfs && hasDfs) read(DFS) else null
            return Roms(read("os.rom"), read("basic.rom"), disc)
        }
    }
}

/** The directory the running jar is in; in the packaged app, Beebslop.app/Contents/app. */
fun jarDirectory(): File? =
    runCatching { File(Roms::class.java.protectionDomain.codeSource.location.toURI()).parentFile }.getOrNull()

/** [name] in the current directory if it is there, else [name] beside the jar, where the packaged app keeps its media. */
private fun mediaDirectory(name: String): File {
    val here = File(name)
    if (here.isDirectory) return here.absoluteFile
    val beside = jarDirectory()?.let { File(it, name) }
    return if (beside?.isDirectory == true) beside else here.absoluteFile
}

/** Where Insert Tape starts browsing. */
fun defaultTapeDirectory(): File = mediaDirectory("tapes")

/** Where Insert Disc starts browsing. */
fun defaultDiscDirectory(): File = mediaDirectory("discs")

/** Where Save As starts: [directory], unless it is inside the app; then ~/Documents, or home. */
fun saveDirectory(directory: File): File {
    if (!isBundled(directory)) return directory
    val home = File(System.getProperty("user.home"))
    return File(home, "Documents").takeIf { it.isDirectory } ?: home
}

/**
 * True if [file] is inside the jar's directory, which only holds media in the
 * packaged app. Writing to a disc there would change the signed bundle (macOS
 * then calls the app damaged), and fails anyway in /Applications, so such
 * discs are inserted without a backing file.
 */
fun isBundled(file: File): Boolean {
    val app = jarDirectory()?.canonicalFile ?: return false
    return generateSequence(file.canonicalFile) { it.parentFile }.any { it == app }
}

/**
 * Builds a machine and runs [Options.screenshotFrames] frames (50ths of a
 * second) as fast as possible, with the tape, discs, boot and text from
 * [options], and on until the text is typed. The tape plays only if the text
 * starts it. Disc changes are not saved.
 */
fun runHeadless(roms: Roms, options: Options): BbcMachine {
    val machine = roms.newMachine()
    options.tape?.let { machine.serialUla.tape = TapeFile.load(it) }
    for ((drive, disc) in options.discs.withIndex()) {
        if (disc != null) machine.fdc?.drives?.get(drive)?.disc = DiscFile.load(disc)
    }
    val typist = Typist()
    if (options.boot) typist.enqueueChord(listOf(Key.SHIFT), holdFrames = SHIFT_BREAK_FRAMES) { it.reset() }
    options.typeText?.let { typist.enqueue(it, delayFrames = BOOT_FRAMES) }
    var frames = 0
    while (frames < options.screenshotFrames || typist.busy) {
        typist.onFrame(machine)
        Emulator.runFrame(machine)
        frames++
    }
    return machine
}

/**
 * A listing of [range] as the CPU sees memory after [runHeadless]: RAM, the
 * paged ROM or the OS, as the paging is then. Reading for it changes nothing.
 * The listing numbers the code from [origin], where it will run once copied.
 */
fun disassemble(machine: BbcMachine, range: IntRange, origin: Int = range.first): String =
    Disassembler(machine.bus::peek).listing(range.first, range.last, origin)

/** Writes [machine]'s picture to [file] as a PNG, as the window shows it at scale 1. */
fun saveScreenshot(machine: BbcMachine, file: File) {
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
        val message = if (options.romDirectory == null) {
            "${e.message}\n\nThis build is incomplete. Pass --roms DIR with the BBC Micro os.rom (MOS 1.20), " +
                "basic.rom (BASIC II) and, for discs, dnfs.rom (DNFS 1.20)."
        } else {
            "${e.message}\n\nPut the BBC Micro os.rom (MOS 1.20) and basic.rom (BASIC II) in that directory, " +
                "and dnfs.rom (DNFS 1.20) for discs, or leave out --roms to use the built-in ones."
        }
        System.err.println(message)
        if (options.screenshot == null && options.disassemble == null && !GraphicsEnvironment.isHeadless()) {
            JOptionPane.showMessageDialog(null, message, "Beebslop", JOptionPane.ERROR_MESSAGE)
        }
        exitProcess(1)
    }
    if (roms.dfs == null && options.discs.any { it != null }) {
        val where = options.romDirectory?.let { "put dnfs.rom in ${it.path}" } ?: "this build has no dnfs.rom"
        System.err.println("--disc needs the disc interface: $where, and don't pass --no-dfs.")
        exitProcess(2)
    }
    if (options.screenshot != null || options.disassemble != null) {
        val machine = try {
            runHeadless(roms, options)
        } catch (e: IOException) {
            System.err.println("Couldn't load a tape or disc: ${e.message}")
            exitProcess(1)
        }
        options.disassemble?.let { println(disassemble(machine, it, options.origin ?: it.first)) }
        options.screenshot?.let {
            saveScreenshot(machine, it)
            println("Saved ${it.path}")
        }
        return
    }
    SwingUtilities.invokeLater { EmulatorWindow(roms, options).show() }
}
