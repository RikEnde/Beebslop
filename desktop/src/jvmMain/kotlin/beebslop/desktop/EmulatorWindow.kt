// SPDX-FileCopyrightText: 2026 The Beebslop contributors
// SPDX-License-Identifier: BSD-3-Clause

package beebslop.desktop

import beebslop.bbc.KeyboardMatrix.Key
import beebslop.bbc.KeyboardText
import beebslop.bbc.Tape
import java.awt.BorderLayout
import java.awt.FileDialog
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.event.ActionEvent
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.InputEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File
import java.io.IOException
import javax.swing.AbstractAction
import javax.swing.ButtonGroup
import javax.swing.JCheckBoxMenuItem
import javax.swing.JFrame
import javax.swing.JMenu
import javax.swing.JMenuBar
import javax.swing.JMenuItem
import javax.swing.JOptionPane
import javax.swing.JRadioButtonMenuItem
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.WindowConstants
import kotlin.system.exitProcess

/** The emulator window: the picture, the keyboard and the menus. */
class EmulatorWindow(roms: Roms, private val options: Options) : Emulator.Listener {
    private val emulator = Emulator(roms::newMachine, options.sound, this)
    private val frame = JFrame()
    private val screen = ScreenPanel(emulator)
    private val isMac = System.getProperty("os.name").startsWith("Mac")
    private val pauseItem = JCheckBoxMenuItem("Pause")
    private var naturalLayout = false
    @Volatile private var capsLock = false
    @Volatile private var shiftLock = false
    @Volatile private var motorRunning = false
    private var tapeName: String? = null

    fun show() {
        frame.defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
        frame.addWindowListener(object : WindowAdapter() {
            override fun windowClosed(e: WindowEvent) {
                emulator.stop()
                exitProcess(0)
            }
        })
        AppIcon.install(frame)
        frame.jMenuBar = createMenuBar()
        frame.contentPane.add(screen, BorderLayout.CENTER)
        screen.scale = options.scale
        screen.addKeyListener(keyListener)
        screen.addFocusListener(object : FocusAdapter() {
            // Keys released while another window has focus never reach us.
            override fun focusLost(e: FocusEvent) = emulator.releaseAll()
        })
        updateTitle()
        frame.pack()
        frame.setLocationRelativeTo(null)
        frame.isVisible = true
        screen.requestFocusInWindow()
        options.tape?.let { insertTape(it) }
        options.typeText?.let { emulator.type(it, delayFrames = BOOT_FRAMES) }
        emulator.start()
    }

    override fun frameReady(frameCount: Long) = screen.repaint()

    override fun stopped(error: Throwable) {
        SwingUtilities.invokeLater {
            pauseItem.isSelected = true
            updateTitle()
            JOptionPane.showMessageDialog(
                frame,
                "The emulated machine stopped:\n${error.message ?: error}\n\n" +
                    "Use Machine > Power Cycle to restart, or BREAK (F12) to reset.",
                "Beebslop",
                JOptionPane.ERROR_MESSAGE,
            )
        }
    }

    override fun motorChanged(on: Boolean) {
        motorRunning = on
        SwingUtilities.invokeLater { updateTitle() }
    }

    override fun ledsChanged(capsLock: Boolean, shiftLock: Boolean) {
        this.capsLock = capsLock
        this.shiftLock = shiftLock
        SwingUtilities.invokeLater { updateTitle() }
    }

    private fun log(e: KeyEvent, result: Any?) {
        if (!options.logKeys) return
        val char = if (e.keyChar == KeyEvent.CHAR_UNDEFINED) "undefined" else "'${e.keyChar}' U+%04X".format(e.keyChar.code)
        val kind = when (e.id) {
            KeyEvent.KEY_PRESSED -> "pressed "
            KeyEvent.KEY_RELEASED -> "released"
            else -> "typed   "
        }
        println(
            "$kind ${KeyEvent.getKeyText(e.keyCode)} (code ${e.keyCode}, location ${e.keyLocation}) char $char " +
                "modifiers [${InputEvent.getModifiersExText(e.modifiersEx)}] -> ${result ?: "nothing"}" +
                if (naturalLayout) "  [natural]" else "",
        )
    }

    private val keyListener = object : KeyAdapter() {
        override fun keyTyped(e: KeyEvent) = log(e, null)

        override fun keyPressed(e: KeyEvent) {
            if (options.logKeys) {
                log(e, if (naturalLayout) {
                    KeyMapping.naturalModifier(e.keyCode)
                        ?: KeyMapping.naturalStroke(e.keyCode, e.keyChar, e.isControlDown, e.isAltDown || e.isAltGraphDown)
                } else {
                    KeyMapping.forKeyCode(e.keyCode)
                })
            }
            // Leave Command shortcuts to the menu bar.
            if (e.isMetaDown) return
            e.consume()
            // Handled here, not as a menu accelerator, so CTRL/SHIFT+BREAK work:
            // the OS reads those keys from the matrix while it restarts.
            if (e.keyCode == KeyMapping.BREAK_KEY) {
                resume()
                emulator.pressBreak()
                return
            }
            if (e.keyCode == KeyEvent.VK_CAPS_LOCK) {
                hostCapsLock(down = true)
                return
            }
            if (naturalLayout) {
                val modifier = KeyMapping.naturalModifier(e.keyCode)
                if (modifier != null) {
                    emulator.naturalModifier(modifier, down = true)
                } else {
                    val stroke = KeyMapping.naturalStroke(e.keyCode, e.keyChar, e.isControlDown, e.isAltDown || e.isAltGraphDown)
                    if (stroke != null) emulator.naturalKeyDown(e.keyCode, stroke)
                }
                return
            }
            KeyMapping.forKeyCode(e.keyCode)?.let(emulator::press)
        }

        override fun keyReleased(e: KeyEvent) {
            log(e, null)
            e.consume()
            if (e.keyCode == KeyEvent.VK_CAPS_LOCK) {
                hostCapsLock(down = false)
                return
            }
            if (naturalLayout) {
                val modifier = KeyMapping.naturalModifier(e.keyCode)
                if (modifier != null) emulator.naturalModifier(modifier, down = false) else emulator.naturalKeyUp(e.keyCode)
                return
            }
            KeyMapping.forKeyCode(e.keyCode)?.let(emulator::release)
        }
    }

    /**
     * The host's Caps Lock is a toggle. On macOS, Java reports a press when it
     * turns on and a release when it turns off, so each event is one press of
     * the key: tap CAPS LOCK for either. Elsewhere it reports both for each press.
     */
    private fun hostCapsLock(down: Boolean) {
        when {
            isMac -> emulator.tap(Key.CAPS_LOCK)
            down -> emulator.press(Key.CAPS_LOCK)
            else -> emulator.release(Key.CAPS_LOCK)
        }
    }

    private fun setNaturalLayout(natural: Boolean) {
        naturalLayout = natural
        // Keys held now would never be released by the other layout's handling.
        emulator.releaseAll()
        updateTitle()
        screen.requestFocusInWindow()
    }

    private fun createMenuBar() = JMenuBar().apply {
        add(JMenu("Machine").apply {
            pauseItem.accelerator = shortcut(KeyEvent.VK_P)
            pauseItem.addActionListener {
                emulator.paused = pauseItem.isSelected
                updateTitle()
            }
            add(pauseItem)
            add(item("Power Cycle", null) {
                resume()
                emulator.powerCycle()
            })
            addSeparator()
            add(JCheckBoxMenuItem("Sound", options.sound).apply {
                isEnabled = options.sound
                addActionListener { emulator.volume = if (isSelected) 1f else 0f }
            })
            if (!isMac) {
                addSeparator()
                add(item("Quit", null) { frame.dispose() })
            }
        })
        add(JMenu("Edit").apply {
            add(item("Paste as Typing", shortcut(KeyEvent.VK_V)) { pasteClipboard() })
            add(item("Stop Typing", null) { emulator.cancelTyping() })
        })
        add(createKeysMenu())
        add(JMenu("Tape").apply {
            add(item("Insert Tape...", shortcut(KeyEvent.VK_O)) { chooseTape() })
            add(item("New Blank Tape", null) {
                emulator.insertTape(Tape.blank())
                tapeName = "blank tape"
                updateTitle()
            })
            add(item("Save Tape As...", shortcut(KeyEvent.VK_S)) { saveTape() })
            addSeparator()
            add(item("Rewind", null) { emulator.rewindTape() })
            add(item("Eject", null) {
                emulator.ejectTape()
                tapeName = null
                updateTitle()
            })
            addSeparator()
            add(JCheckBoxMenuItem("Fast Loading").apply {
                toolTipText = "Run flat out while the cassette motor runs, instead of at 1200 baud"
                addActionListener {
                    emulator.fastTape = isSelected
                    updateTitle()
                }
            })
        })
        add(JMenu("View").apply {
            val group = ButtonGroup()
            for (scale in 1..2) {
                val choice = JRadioButtonMenuItem("${scale}x", scale == options.scale)
                choice.accelerator = shortcut(KeyEvent.VK_0 + scale)
                choice.addActionListener {
                    screen.scale = scale
                    frame.pack()
                }
                group.add(choice)
                add(choice)
            }
        })
        add(JMenu("Help").apply {
            add(item("Keyboard", null) { showKeyboardHelp() })
        })
    }

    /** BBC keys a host keyboard lacks, or that need a held modifier. */
    private fun createKeysMenu() = JMenu("Keys").apply {
        add(item("BREAK  (F12)", null) {
            resume()
            emulator.pressBreak()
        })
        add(item("CTRL + BREAK  (cold start)", null) {
            resume()
            emulator.pressBreakWith(Key.CTRL)
        })
        add(item("SHIFT + BREAK  (boot)", null) {
            resume()
            emulator.pressBreakWith(Key.SHIFT)
        })
        addSeparator()
        add(item("COPY  (End)", null) { emulator.tap(Key.COPY) })
        add(item("CAPS LOCK  (Caps Lock, or Option/Alt)", null) { emulator.tap(Key.CAPS_LOCK) })
        add(item("SHIFT LOCK", null) { emulator.tap(Key.SHIFT_LOCK) })
        add(item("ESCAPE  (Esc)", null) { emulator.tap(Key.ESCAPE) })
        add(item("\\", null) { emulator.tap(Key.BACKSLASH) })
        add(item("|", null) { emulator.tap(Key.SHIFT, Key.BACKSLASH) })
        addSeparator()
        add(JMenu("Function Keys").apply {
            for (n in 0..9) {
                val host = if (n == 0) "F10" else "F$n"
                add(item("f$n  ($host)", null) { emulator.tap(KeyboardText.functionKey(n)) })
            }
        })
        addSeparator()
        add(JMenu("Keyboard Layout").apply {
            val group = ButtonGroup()
            add(JRadioButtonMenuItem("Physical (for games)", true).also { choice ->
                choice.toolTipText = "Keys press the BBC key in the same place, so symbols follow the BBC"
                choice.addActionListener { setNaturalLayout(false) }
                group.add(choice)
            })
            add(JRadioButtonMenuItem("Natural (for typing)").also { choice ->
                choice.toolTipText = "Keys type the character your own keyboard layout produces"
                choice.addActionListener { setNaturalLayout(true) }
                group.add(choice)
            })
        })
    }

    private fun item(title: String, key: KeyStroke?, action: () -> Unit) =
        JMenuItem(object : AbstractAction(title) {
            override fun actionPerformed(e: ActionEvent) = action()
        }).apply { accelerator = key }

    /** Command on macOS. Ctrl+Shift elsewhere, so plain Ctrl stays the BBC's. */
    private fun shortcut(keyCode: Int): KeyStroke = KeyStroke.getKeyStroke(
        keyCode,
        if (isMac) InputEvent.META_DOWN_MASK else InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK,
    )

    private fun resume() {
        emulator.paused = false
        pauseItem.isSelected = false
        updateTitle()
    }

    private fun updateTitle() {
        frame.title = "Beebslop - BBC Micro Model B" +
            (if (capsLock) "  [CAPS]" else "") +
            (if (shiftLock) "  [SHIFT LOCK]" else "") +
            (if (naturalLayout) "  [NATURAL]" else "") +
            (tapeName?.let { "  [$it]" } ?: "") +
            (if (emulator.fastTape) "  [FAST]" else "") +
            (if (motorRunning) "  [MOTOR]" else "") +
            (if (emulator.paused) "  (paused)" else "")
    }

    private fun chooseTape() {
        val dialog = FileDialog(frame, "Insert Tape", FileDialog.LOAD)
        dialog.directory = File("tapes").absolutePath
        dialog.isVisible = true
        val name = dialog.file
        if (name != null) insertTape(File(dialog.directory, name))
        screen.requestFocusInWindow()
    }

    private fun insertTape(file: File) {
        try {
            emulator.insertTape(TapeFile.load(file))
            tapeName = file.name
            updateTitle()
        } catch (e: IOException) {
            JOptionPane.showMessageDialog(frame, "Couldn't load ${file.name}:\n${e.message ?: e}", "Insert Tape", JOptionPane.ERROR_MESSAGE)
        }
    }

    /** Writes the cassette, with anything recorded on it, to a UEF file. */
    private fun saveTape() {
        val dialog = FileDialog(frame, "Save Tape As", FileDialog.SAVE)
        dialog.directory = File("tapes").absolutePath
        dialog.file = tapeName?.takeIf { it.endsWith(".uef") } ?: "tape.uef"
        dialog.isVisible = true
        val name = dialog.file
        if (name == null) {
            screen.requestFocusInWindow()
            return
        }
        val file = File(dialog.directory, if (name.endsWith(".uef")) name else "$name.uef")
        emulator.exportTape { image ->
            SwingUtilities.invokeLater {
                if (image == null) {
                    JOptionPane.showMessageDialog(frame, "There is no tape in the recorder.", "Save Tape", JOptionPane.WARNING_MESSAGE)
                    return@invokeLater
                }
                try {
                    TapeFile.save(image, file)
                    tapeName = file.name
                    updateTitle()
                } catch (e: IOException) {
                    JOptionPane.showMessageDialog(frame, "Couldn't save ${file.name}:\n${e.message ?: e}", "Save Tape", JOptionPane.ERROR_MESSAGE)
                }
            }
        }
        screen.requestFocusInWindow()
    }

    private fun pasteClipboard() {
        val text = try {
            Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as String
        } catch (e: Exception) {
            return
        }
        resume()
        val skipped = emulator.type(text)
        if (skipped.isNotEmpty()) {
            JOptionPane.showMessageDialog(
                frame,
                "The BBC keyboard can't type these characters, so they were skipped:\n" +
                    skipped.joinToString(" ") { if (it.isWhitespace()) "U+%04X".format(it.code) else it.toString() },
                "Paste as Typing",
                JOptionPane.WARNING_MESSAGE,
            )
        }
    }

    private fun showKeyboardHelp() {
        JOptionPane.showMessageDialog(
            frame,
            """
            Keys > Keyboard Layout chooses how keys map. [NATURAL] in the title bar shows it.
            Natural types the character your own keyboard produces; letters still follow
            CAPS LOCK, and the key you pressed last replaces any key still held down.
            Physical, the default, is for games and keeps the BBC's symbols:
              Shift+2 = "   Shift+3 = #   Shift+6 = &   Shift+7 = '   Shift+- = =
              ' is :   = is ^   ` is _ (Shift: £)   \ is @

            BBC key          Host key
            f0-f9            F10, F1-F9   (on a Mac, hold fn if they work the screen or sound)
            BREAK            F12   (Keys menu: CTRL+BREAK, SHIFT+BREAK)
            COPY             End
            CAPS LOCK        Caps Lock, or Option/Alt in the Physical layout
            DELETE           Backspace or Delete
            ESCAPE           Esc
            Cursor keys      Arrows
            \ and |          Keys menu, or the key next to the left Shift on ISO keyboards
            SHIFT LOCK       Keys menu

            The title bar shows [CAPS] and [SHIFT LOCK] while their lights are on.
            Paste as Typing types text from the clipboard; lower case turns CAPS LOCK off.

            Tapes: insert one from the Tape menu, then type CHAIN "" and RETURN.
            To save, use Tape > New Blank Tape, SAVE "NAME", then Tape > Save Tape As.
            Tape > Fast Loading skips the wait; [MOTOR] shows the motor is running.
            """.trimIndent(),
            "Keyboard",
            JOptionPane.PLAIN_MESSAGE,
        )
    }
}
