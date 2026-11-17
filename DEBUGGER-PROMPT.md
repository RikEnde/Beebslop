# Prompt: port Elkslop's debugger

Use this as the prompt for an agent working in **Beebslop** (`~/projects/acorn/beebslop`)
or **C64slop** (`~/projects/commodore/c64`). Elkslop (`~/projects/acorn/elkslop`,
branch `debugger`) has an integrated 6502 debugger. Port it so the three projects
share the same code, with only the package names and the machine-specific parts
changed.

---

## Goal

Add a debugger to this emulator:

- **Debug › Debugger…** (Cmd+D on macOS, Ctrl+Shift+D elsewhere) opens a
  separate window on the running machine. It shows the code around the PC with
  breakpoints, the registers and flags, the stack, a breakpoint list and a
  memory dump. It updates ten times a second while the machine runs.
- In that window: F5 Continue, F6 Pause, F7 Step Into, F8 Step Over,
  Shift+F8 Step Out, F9 toggles the breakpoint on the selected line. Clicking a
  line's left edge, or double-clicking it, toggles its breakpoint. Right-click a
  line to run to it, or to show the code or memory its operand names. **Go to**
  takes a hex address or a name.
- **Debug › Break into Debugger** pauses the machine and opens the window. Hitting
  a breakpoint opens it too.
- `--break ADDR`, which can be repeated, sets breakpoints at startup. In the
  headless `--disassemble` mode, the run stops at the first breakpoint reached,
  and the registers are printed before the listing.

## Read these first in Elkslop

Copy the following, change the package (`elkslop.` → `beebslop.` or `c64slop.`),
and keep everything else the same, including class and method names, so a fix in
one project can be carried to the others. Keep each project's own SPDX header
("The Beebslop contributors" or "The C64slop contributors").

**`cpu` module.** This is generic 6502 code, and your `cpu` module started from
the same code:
- `cpu/src/commonMain/kotlin/elkslop/cpu/StepKind.kt`: new.
- `Cpu6502.kt`: add `lastStep: StepKind` and set it in each branch of `step()`.
  Diff your file against Elkslop's to find the lines; it is a small patch.
- `Debugger.kt`: new. Breakpoints, step into, over and out, and run to.
- `CpuState.kt`: add the `flags` and `summary` properties.
- `Disassembler.kt`: add `before(address, count)`.
- Tests: `cpu/src/commonTest/kotlin/elkslop/cpu/DebuggerTest.kt`, and the two
  `before` tests in `DisassemblerTest.kt`.

**`debugger` module.** This is new, JVM only, and depends only on `cpu`:
- `debugger/build.gradle.kts`. Add `":debugger"` to `settings.gradle.kts`, and
  `implementation(project(":debugger"))` to `desktop`.
- `debugger/src/jvmMain/kotlin/elkslop/debugger/`: `DebugSession.kt`,
  `DebugTarget.kt`, `DebugSnapshot.kt` and `DebuggerWindow.kt`. Copy them
  unchanged apart from the package. They know nothing about any machine.
- `debugger/src/jvmTest/kotlin/elkslop/debugger/DebugSessionTest.kt`.

**Desktop glue.** This is where you adapt the code rather than copy it. See the
diff of Elkslop's branch for:
- `desktop/.../Emulator.kt`:
  - It holds a `DebugSession({ machine.bus.peek(it) })`.
  - It exposes `val debugTarget = debug.target(post = …, cpu = { machine.cpu }, setPaused = { paused = it })`.
  - Its loop calls `debug.pausedChanged(paused)` when pause changes, and `debug.tick(cpu, paused, frameCount)` once per pass.
  - Every `machine.step()` is wrapped as `if (debug.shouldStop(cpu)) { paused = true; listener.debuggerStopped(); return }`, `machine.step()`, `debug.stepped(cpu)`.
  - A JAM halt calls `debug.stopped("Halted by JAM at $xxxx")`.
- **Stopping in the middle of a frame.** Work done once a frame (the typist,
  keyboard queues, joystick and so on) must only run when a new frame starts.
  Otherwise each single step repeats it. Elkslop keeps a `preparedFrame` number
  for this. Find every per-frame hook in your `runFrame`.
- `EmulatorWindow.kt`:
  - Add the Debug menu.
  - Create `DebuggerWindow(emulator.debugTarget, "<App> Debugger", <Labels>::forAddress, AppIcon::install)` lazily.
  - Add the `pausedChanged` listener, which keeps the Pause menu item and the title in step.
  - Add `debuggerStopped`, which opens the window.
- `Main.kt`:
  - The `--break` option.
  - `runHeadless` returns `HeadlessRun(machine, breakpoint)` and checks a `Debugger` before each step.
  - When breakpoints are given, `main` prints `Breakpoint at XXXX: <summary>`.
  - Update the existing tests that call `runHeadless` to use `.machine`.
  - `useSystemLookAndFeel()`, called at the start of `main` before any window
    exists, so the app and the debugger use the host's own look rather than
    Swing's Metal. It is one standard Swing call that works on every
    platform; add nothing OS-specific. The `debugger` module doesn't set it itself,
    because the look and feel belongs to the whole app.

## The machine-specific part: names for addresses

Elkslop has `electron/src/commonMain/kotlin/elkslop/electron/ElectronLabels.kt`.
It is a map from address to name, with `forAddress(address): String?`. Write the
equivalent in your machine module. **Use only the official names from the
manufacturer's documentation**, and credit that documentation in
`ACKNOWLEDGEMENTS.md`. Don't take label names from third-party ROM disassemblies
unless their licence allows it.

### Beebslop (BBC Micro), `bbc/src/commonMain/kotlin/beebslop/bbc/BbcLabels.kt`

- The OS entry points and the page 2 vectors are the same as the Electron's
  (OSWRCH, OSBYTE, WRCHV, IRQ1V and so on). Copy those entries from
  `ElectronLabels.kt`, and check them against the *BBC Microcomputer Advanced User
  Guide*. The BBC's MOS also has OSWRCR and OSRDRM. Add an entry only where the
  Guide confirms the address.
- Replace the Electron's ULA registers with the SHEILA devices this emulator
  implements (`Sheila.kt`, `Crtc6845.kt`, `Via6522.kt`, `VideoUla.kt`, `Fdc8271.kt`,
  `Acia6850.kt`, `SerialUla.kt`). Name the base of each register block as the
  Advanced User Guide does: for example the system VIA at &FE40 and the user VIA
  at &FE60. Read the addresses from the code, not from memory.
- `Emulator.runFrame` has **two** step loops (`MIN_FRAME_TICKS` and
  `MAX_FRAME_TICKS`). Both need the `shouldStop`/`stepped` wrapping.
- Host F-keys may type the BBC's red function keys in the main window. The
  debugger's F5–F9 are bound only in the debugger window, so they don't clash.
  Check this.

### C64slop (Commodore 64), `c64/src/commonMain/kotlin/c64slop/c64/C64Labels.kt`

- The KERNAL jump table, as named in the *Commodore 64 Programmer's Reference
  Guide*: CHROUT (&FFD2), CHRIN (&FFCF), GETIN (&FFE4), SETLFS, SETNAM, OPEN,
  CLOSE, LOAD, SAVE, PLOT, SCNKEY, STOP, CLALL and the rest. Take every address
  from the Guide's table.
- The RAM vectors: CINV (&0314), CBINV (&0316), NMINV (&0318), and the I/O
  vectors after them, also as the Guide names them.
- The I/O chips as this emulator maps them (`Vic6569.kt`, `Sid6581.kt`,
  `Cia6526.kt`). Name the base of each block, for example VIC at &D000, SID at
  &D400, CIA1 at &DC00 and CIA2 at &DD00. Read the addresses from the code.
- The disassembler already prints `$` hex, which suits the C64. Go to accepts
  `$`, `&` and `0x`.
- The C64's F1–F8 may be mapped to host F-keys in the main window. As above,
  check that only the debugger window binds F5–F9.
- **The C64 also has a 6510 port at $00/$01, and banking.** The snapshot reads
  memory through `C64MemoryBus.peek`, which shows what the CPU sees now: BASIC,
  KERNAL or RAM, depending on banking. That is correct for a debugger. Say so in
  the README.

## Constraints

- **Behaviour matches Elkslop's.** Breakpoints stop *before* their instruction
  runs. After any stop, the instruction at the PC runs before breakpoints are
  checked again.
  - Step Over runs a JSR until it returns to the next instruction at the same
    stack level, and runs interrupts through to their RTI.
  - Step Out stops after the RTS or RTI that takes the stack above where it was.
- **Only the emulation thread touches the machine.** The window draws only from
  snapshots. Its requests go through `DebugTarget`, which posts them to the
  command queue.
- **No cost when idle.** With no breakpoints and no step under way, the cost per
  instruction is a single `active` check.
- Don't change the existing disassembler listing format or the existing tests'
  expectations.
- Update `README.md`:
  - a Debugger section for users;
  - the `--break` option;
  - the `debugger` module in the module list;
  - the new `cpu` files.

## Verify

1. Run `./gradlew :cpu:jvmTest :<machine>:jvmTest :debugger:jvmTest :desktop:jvmTest`.
   Everything must pass, including the new `DebuggerTest`, `DebugSessionTest` and
   `--break` tests.
2. Run `./gradlew :cpu:compileKotlinMacosArm64` (or the native target for your
   host), so `cpu` still builds for Kotlin/Native.
3. Headless: break at the IRQ handler. Read its address from the ROM's vector at
   $FFFE. The run must stop there with `lastStep == IRQ`. Add this as a test, as
   Elkslop does in `DisassembleOptionsTest.stopsAtABreakpoint`.
4. By hand, with an assembly hello world:
   - Set a breakpoint on its first instruction and run it.
   - Step over `JSR` the character-output routine (OSWRCH or CHROUT): one
     character appears per step.
   - Step Into the routine, then Step Out of it.
   - Continue.
   - Check that the code view shows the routine's name.

   BASIC's inline assembler does this on the BBC. On the C64, POKE the bytes, or
   load a small PRG.

When you're done, report:
- what changed;
- the test counts;
- every place you had to adapt the code rather than copy it.
