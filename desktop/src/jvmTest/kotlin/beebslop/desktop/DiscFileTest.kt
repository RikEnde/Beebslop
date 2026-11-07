// SPDX-FileCopyrightText: 2026 The Beebslop contributors
// SPDX-License-Identifier: BSD-3-Clause

package beebslop.desktop

import beebslop.bbc.BbcMachine
import beebslop.bbc.Disc
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiscFileTest {
    private val romDirectory = File(System.getProperty("beebslop.romDirectory") ?: "../roms")
    private val dir = File("build/test-discs").apply { mkdirs() }

    @AfterTest
    fun cleanUp() {
        dir.setWritable(true)
        dir.listFiles()?.forEach { it.setWritable(true) }
        dir.deleteRecursively()
    }

    @Test
    fun savesAndLoadsSingleAndDoubleSidedImages() {
        for ((sides, name) in listOf(1 to "a.ssd", 2 to "b.dsd")) {
            val disc = Disc.blank(sides, title = "T$sides")
            val file = File(dir, name)
            DiscFile.save(disc, file)
            assertEquals(sides * 80 * Disc.TRACK_BYTES, file.length().toInt())
            val loaded = DiscFile.load(file)
            assertEquals(sides, loaded.sides)
            assertContentEquals(disc.toImage(), loaded.toImage())
            assertFalse(loaded.writeProtected)
        }
    }

    @Test
    fun theExtensionMustMatchTheSides() {
        assertFailsWith<IOException> { DiscFile.save(Disc.blank(2), File(dir, "wrong.ssd")) }
        assertFailsWith<IOException> { DiscFile.save(Disc.blank(1), File(dir, "wrong.dsd")) }
        assertFailsWith<IOException> { DiscFile.load(File(dir, "tiny.ssd").apply { writeBytes(ByteArray(10)) }) }
    }

    @Test
    fun aReadOnlyFileIsInsertedWriteProtected() {
        val file = File(dir, "locked.ssd")
        DiscFile.save(Disc.blank(1), file)
        file.setWritable(false)
        assertTrue(DiscFile.load(file).writeProtected)
    }

    @Test
    fun aChangedDiscIsSavedBackWhenTheDriveStops() {
        val os = File(romDirectory, "os.rom")
        val basic = File(romDirectory, "basic.rom")
        val dfs = File(romDirectory, "dnfs.rom")
        if (!os.isFile || !basic.isFile || !dfs.isFile) return println("Skipping: no ROMs in ${romDirectory.absolutePath}")
        val file = File(dir, "saved.ssd")
        DiscFile.save(Disc.blank(1), file)
        val before = file.readBytes()
        val stopped = CountDownLatch(1)
        val emulator = Emulator(
            createMachine = { BbcMachine(os.readBytes(), basic.readBytes(), dfs.readBytes()) },
            soundEnabled = false,
            listener = object : Emulator.Listener {
                override fun frameReady(frameCount: Long) {}
                override fun stopped(error: Throwable) = throw AssertionError(error)
                override fun discMotorChanged(on: Boolean) {
                    if (!on) stopped.countDown()
                }
                override fun discSaveFailed(drive: Int, file: File, error: IOException) = throw AssertionError(error)
            },
        )
        emulator.insertDisc(0, DiscFile.load(file), file)
        emulator.type("10 PRINT\nSAVE \"P\"\n", delayFrames = BOOT_FRAMES)
        emulator.start()
        try {
            // The head unloads about 2.4 s after the SAVE; the file is written then.
            assertTrue(stopped.await(30, TimeUnit.SECONDS), "the drive stops")
            var catalogue = 0
            for (i in 0 until 60) {
                catalogue = file.readBytes()[Disc.SECTOR_SIZE + 5].toInt()
                if (catalogue != 0) break
                Thread.sleep(500)
            }
            assertEquals(8, catalogue, "one file in the saved catalogue")
            assertFalse(before.contentEquals(file.readBytes()))
        } finally {
            emulator.stop()
        }
    }
}
