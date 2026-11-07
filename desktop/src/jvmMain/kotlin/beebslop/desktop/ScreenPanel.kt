// SPDX-FileCopyrightText: 2026 The Beebslop contributors
// SPDX-License-Identifier: BSD-3-Clause

package beebslop.desktop

import beebslop.bbc.Video
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import javax.swing.JPanel

/** Shows the latest picture, scaled to fit with its 4:3 shape and letterboxed in black. */
class ScreenPanel(private val emulator: Emulator) : JPanel() {
    private val image = BufferedImage(Video.WIDTH, Video.HEIGHT, BufferedImage.TYPE_INT_RGB)
    private val pixels = (image.raster.dataBuffer as DataBufferInt).data

    var scale: Int = 1
        set(value) {
            field = value
            preferredSize = Dimension(Video.WIDTH * value, Video.HEIGHT * value)
            revalidate()
        }

    init {
        background = Color.BLACK
        isFocusable = true
        focusTraversalKeysEnabled = false // TAB is a BBC key.
        isDoubleBuffered = true
        scale = 1
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        emulator.copyFrame(pixels)
        val target = fitRectangle(width, height)
        (g as Graphics2D).setRenderingHint(
            RenderingHints.KEY_INTERPOLATION,
            RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR,
        )
        g.drawImage(image, target.x, target.y, target.width, target.height, null)
    }

    companion object {
        /** Largest 4:3 rectangle centred in a width x height area. */
        fun fitRectangle(width: Int, height: Int): Rectangle {
            val w = minOf(width, height * Video.WIDTH / Video.HEIGHT)
            val h = w * Video.HEIGHT / Video.WIDTH
            return Rectangle((width - w) / 2, (height - h) / 2, w, h)
        }
    }
}
