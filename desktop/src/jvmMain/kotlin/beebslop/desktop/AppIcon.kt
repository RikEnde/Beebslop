// SPDX-FileCopyrightText: 2026 The Elkslop contributors
// SPDX-FileCopyrightText: 2026 The Beebslop contributors
// SPDX-License-Identifier: BSD-3-Clause

package beebslop.desktop

import java.awt.Image
import java.awt.RenderingHints
import java.awt.Taskbar
import java.awt.image.BufferedImage
import javax.swing.JFrame

/**
 * The application icon: a pixel-art owl on a red perch, the colour of the
 * Model B's function keys. An owl in the spirit of the BBC Computer Literacy
 * Project, not a copy of its logo. Drawn from a 32 x 32 grid and scaled with
 * nearest-neighbour filtering, so it stays crisp at every size. Adapted from
 * Elkslop's `AppIcon`.
 */
object AppIcon {
    private val ART = arrayOf(
        "................................",
        "................................",
        ".......oo..............oo.......",
        ".......obo............obo.......",
        ".......obbo..........obbo.......",
        ".......obbboooooooooobbbo.......",
        "......obbbbbbbbbbbbbbbbbbo......",
        "......obbbbbbbbbbbbbbbbbbo......",
        ".....obffffffbbbbbbffffffbo.....",
        ".....offyyyyffbbbbffyyyyffo.....",
        ".....ofywyyyyfbbbbfwyyyyyfo.....",
        ".....ofyyppyyfbbbbfyyppyyfo.....",
        ".....ofyyppyyffbbffyyppyyfo.....",
        ".....ofyyyyyyffkkffyyyyyyfo.....",
        ".....offyyyyfffkkfffyyyyffo.....",
        ".....obffffffbbkkbbffffffbo.....",
        ".....obbbbbbbbbbbbbbbbbbbbo.....",
        ".....obbbtbbtbbbbbbtbbtbbbo.....",
        "....obbbbbbbbbbbbbbbbbbbbbbo....",
        "....obtbbtbbtbbbbbbtbbtbbtbo....",
        "....oBbbbbbbbbbbbbbbbbbbbbBo....",
        "....oBBbtbbtbbbbbbbbtbbtbBBo....",
        "....oBBbbbbbbbbbbbbbbbbbbBBo....",
        ".....oBBbbbbbbbbbbbbbbbbBBo.....",
        ".....oBBBbbbbbbbbbbbbbbBBBo.....",
        "......oBBBBbbbbbbbbbbBBBBo......",
        ".......ooollloooooolllooo.......",
        "...rrrrrrrlrlrrrrrrlrlrrrrrrr...",
        "...RRRRRRRRRRRRRRRRRRRRRRRRRR...",
        "................................",
        "................................",
        "................................",
    )

    private val PALETTE = mapOf(
        '.' to 0x00000000,
        'o' to 0xFF2A221C.toInt(), // outline
        'b' to 0xFF6B4A2E.toInt(), // feathers
        'B' to 0xFF4E3520.toInt(), // wing shade
        't' to 0xFF8A6440.toInt(), // chest flecks
        'f' to 0xFFE8D8B0.toInt(), // face disc
        'y' to 0xFFF2C200.toInt(), // eyes
        'p' to 0xFF101010.toInt(), // pupils
        'w' to 0xFFFFFFFF.toInt(), // glints
        'k' to 0xFFE07020.toInt(), // beak
        'l' to 0xFFE07020.toInt(), // talons
        'r' to 0xFFC8102E.toInt(), // perch
        'R' to 0xFF8A0A1E.toInt(), // perch shade
    )

    private val base: BufferedImage by lazy {
        BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB).apply {
            for ((y, row) in ART.withIndex()) {
                for ((x, char) in row.withIndex()) setRGB(x, y, PALETTE.getValue(char))
            }
        }
    }

    /** The icon at [size] x [size] pixels. */
    fun render(size: Int): BufferedImage {
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
        g.drawImage(base, 0, 0, size, size, null)
        g.dispose()
        return image
    }

    /** Window icons (title bar, Windows taskbar, Linux docks) and the macOS Dock icon. */
    fun install(frame: JFrame) {
        frame.iconImages = listOf(16, 32, 64, 128, 256).map(::render)
        try {
            val taskbar = Taskbar.getTaskbar()
            if (taskbar.isSupported(Taskbar.Feature.ICON_IMAGE)) taskbar.iconImage = render(512) as Image
        } catch (_: UnsupportedOperationException) {
            // No taskbar on this platform; window icons are enough.
        } catch (_: SecurityException) {
        }
    }
}
