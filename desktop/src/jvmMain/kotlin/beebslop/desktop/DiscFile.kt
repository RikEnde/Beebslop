// SPDX-FileCopyrightText: 2026 The Beebslop contributors
// SPDX-License-Identifier: BSD-3-Clause

package beebslop.desktop

import beebslop.bbc.Disc
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** SSD and DSD disc images on disk. A `.dsd` file is double-sided; anything else single-sided. */
object DiscFile {
    fun sides(file: File): Int = if (file.extension.equals("dsd", ignoreCase = true)) 2 else 1

    /**
     * Reads an image. A file the host won't let us write is inserted write-protected.
     * @throws IOException if the file can't be read or isn't a disc image.
     */
    fun load(file: File): Disc {
        val disc = try {
            Disc.parse(file.readBytes(), sides(file))
        } catch (e: IllegalArgumentException) {
            throw IOException(e.message, e)
        }
        disc.writeProtected = !file.canWrite()
        return disc
    }

    /**
     * Writes [disc] to [file], replacing the file in one step.
     * @throws IOException if the file's extension doesn't match the disc's sides.
     */
    fun save(disc: Disc, file: File) {
        if (sides(file) != disc.sides) {
            throw IOException(if (disc.sides == 2) "A double-sided disc must be saved as .dsd" else "A single-sided disc can't be saved as .dsd")
        }
        val target = file.absoluteFile
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, ".${target.name}.tmp")
        temporary.writeBytes(disc.toImage())
        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
