// SPDX-FileCopyrightText: 2026 The Elkslop contributors
// SPDX-FileCopyrightText: 2026 The Beebslop contributors
// SPDX-License-Identifier: BSD-3-Clause

package beebslop.desktop

import beebslop.bbc.Tape
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** UEF files on disk. Adapted from Elkslop's `TapeFile`. */
object TapeFile {
    /**
     * Reads a UEF image, gzipped (as they usually are) or plain.
     * @throws IOException if the file can't be read or isn't a UEF image.
     */
    fun load(file: File): Tape {
        val bytes = file.readBytes()
        val gzipped = bytes.size >= 2 && bytes[0] == 0x1F.toByte() && bytes[1] == 0x8B.toByte()
        val image = if (gzipped) GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() } else bytes
        return try {
            Tape.parse(image)
        } catch (e: IllegalArgumentException) {
            throw IOException(e.message, e)
        }
    }

    /** Writes [image], a UEF image, gzipped like most UEF files. */
    fun save(image: ByteArray, file: File) {
        file.absoluteFile.parentFile?.mkdirs()
        GZIPOutputStream(file.outputStream()).use { it.write(image) }
    }
}
