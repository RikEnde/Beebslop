// SPDX-FileCopyrightText: 2026 The Beebslop contributors
// SPDX-License-Identifier: BSD-3-Clause

package beebslop.desktop

import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

class AppIconTest {
    @Test
    fun rendersCrispOwlAtAnySize() {
        val icon = AppIcon.render(512)
        assertEquals(512, icon.width)
        assertEquals(0, icon.getRGB(0, 0) ushr 24, "corners are transparent")
        // Each grid cell is 16 x 16 pixels here.
        assertEquals(0xFFF2C200.toInt(), icon.getRGB(10 * 16 + 8, 13 * 16 + 8), "yellow eye")
        assertEquals(0xFF101010.toInt(), icon.getRGB(9 * 16 + 8, 11 * 16 + 8), "black pupil")
        assertEquals(0xFFC8102E.toInt(), icon.getRGB(4 * 16 + 8, 27 * 16 + 8), "red perch")
        File("build/icon").mkdirs()
        ImageIO.write(icon, "png", File("build/icon/beebslop-512.png"))
    }
}
