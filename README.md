# Beebslop

An emulator of the Acorn BBC Microcomputer Model B, in Kotlin Multiplatform.
It is built the same way as its sibling
[Elkslop](https://github.com/RikEnde/elkslop), an Acorn Electron emulator: a
platform-free emulation core, a Swing desktop front end, and headless tests that
run the real ROMs.

## Status

Early. MOS 1.20 boots to the BASIC prompt in a desktop window, draws the
screen in every mode, takes keyboard input and plays sound; there is no
cassette or disc yet.

| Part | State |
|---|---|
| NMOS 6502 (`cpu`) | Done: copied from Elkslop, all tests pass |
| Memory map, SHEILA, 1 MHz cycle stretching | Done |
| System VIA, CRTC, video ULA; boot to the BASIC prompt | Done |
| MODE 7 (SAA5050 teletext), then modes 0-6 | Done |
| Keyboard: host mapping (physical and natural), paste as typing | Done |
| Sound (SN76489) | Done |
| Cassette (6850 ACIA, serial ULA, UEF) | Not started |
| Disc (8271, DFS, SSD/DSD) | Not started |
| Econet (68B54 ADLC), networking several instances | Not started |

The first target is a Model B with MOS 1.20 and BASIC II, booting to the BASIC
prompt in MODE 7.

## Plan

Each step ends with a headless test on the real ROMs.

1. **Skeleton:** Gradle, the `cpu` module and licensing. Done.
2. **Memory map:** RAM, the OS ROM, ROMSEL and the sideways ROM slots with BASIC
   fitted, and SHEILA decoding with 1 MHz cycle stretching built into the clock.
   Done.
3. **Boot:** the system VIA, CRTC, video ULA and keyboard links, enough for MOS
   1.20 to reach the BASIC prompt. The test reads the banner and `>` from MODE 7
   screen memory. Done.
4. **Video:** MODE 7 first, then modes 0-6 with the palette and scroll wrap.
   Done.
5. **Keyboard:** the matrix, autoscan, host mapping (physical and natural) and a
   typist. Done.
6. **Sound:** the SN76489. Done.
7. **Cassette:** the 6850 ACIA, serial ULA and UEF images, with fast loading.
8. **Disc:** the Intel 8271, as fitted to the original Model B, with Acorn DFS
   and SSD/DSD images. We still need a DFS 0.90 or 1.20 dump for the 8271.
9. **Econet:** the 68B54 ADLC and station ID, so that **several running instances
   of Beebslop can form one Econet network**. The core models only the ADLC and
   exchanges whole frames through an interface. The desktop carries the frames
   between instances over the host network. Things to settle when we get there:
   - the transport, for example UDP on the local host or LAN, and whether to
     follow an existing convention such as AUN so that other emulators can join;
   - how station numbers are set for each instance;
   - the network clock, which a real network gets from a separate clock box;
   - a file server: another instance running file server software, or one on the
     host.
10. **Accuracy:** exact VIA timers, per-line video effects, and the games in
    MAME's compatibility list.

### Teletext font

The SAA5050's character shapes come from Ben Harris's
[Bedstead](https://bjh21.me.uk/bedstead/). `tools/teletext_font.py` reads
`bedstead.c` from Bedstead 3.261, checks its SHA-256, and writes
`TeletextFont.kt`: the 5×9 bitmaps for codes `&20-&7F` of the English
SAA5050. Character rounding is done in `Saa5050.kt`.

Bedstead has no table of the English character set, so the script builds it:
Bedstead's US ASCII (SAA5055) table, with the eleven glyphs it lists as "Extra
characters found in the English (SAA5050) character set" at `&23` (£), `&27`,
`&5B-&5E` (← ½ → ↑), `&60` (—) and `&7B-&7E` (¼ ‖ ¾ ÷), and `#` moved to `&5F`.
The *Advanced User Guide* confirms three of these: the MOS stores `#` as `&5F`,
`_` as `&60` and `£` as `&23` in MODE 7 screen memory (section 11, the table at
`&C4B6`). Bedstead's metrics (a 10-row cell, ascent 8, cap height 7) put the 9
glyph rows below one blank row.

Bedstead's code and new glyphs are dedicated to the public domain (CC0). The
original SAA5050 designs are covered by its authors' reading of CDPA 1988 s.55,
which Debian considers too narrow to make the package free. `TeletextFont.kt` is
marked `CC0-1.0 AND LicenseRef-CDPA-s55`; see
[`LICENSES/LicenseRef-CDPA-s55.txt`](LICENSES/LicenseRef-CDPA-s55.txt).

## Run it

```sh
./run.sh                           # open the emulator window
./run.sh --scale 2                 # a 1408 x 1056 window
./run.sh --screenshot boot.png     # no window: boot, save a PNG, exit
./run.sh --help
```

`run.sh` builds the desktop jar with Gradle if needed, then runs it. It needs a
JDK 17 or newer: `$JAVA_HOME`, else `.tools/jdk`, else `java` on the `PATH`. The
ROMs are read from `roms/`, or from `--roms DIR`.

| Option | Meaning |
|---|---|
| `--roms DIR` | Directory with `os.rom` and `basic.rom` (default: `roms`) |
| `--scale 1\|2` | Window size; 1 is 704 x 528 (default: 1) |
| `--screenshot FILE` | Run without a window, save a PNG of the picture, then exit |
| `--frames N` | 50ths of a second to run before the screenshot (default: 150) |
| `--no-sound` | Run without sound |
| `--log-keys` | Print each host key event and the BBC key it maps to |

The window shows the picture and runs in real time. **Machine** has Pause
(Cmd+P, or Ctrl+Shift+P off macOS), Power Cycle and a Sound switch; **Edit** has Paste as Typing;
**Keys** has BREAK and its variants, keys a host keyboard lacks, f0-f9 and the
keyboard layout; **View** sets the size; **Help > Keyboard** lists the keys.

### Keyboard

There are two layouts, chosen in **Keys > Keyboard Layout**:

- **Physical** (the default, for games) maps keys by position, following MAME,
  so the symbols are the BBC's own: Shift+2 is `"` and the `'` key is `:`.
- **Natural** (for typing) types the character your own keyboard produces.
  The key pressed last replaces any key still held, so it is less suited to
  games.

| BBC key | Host key |
|---|---|
| f0-f9 | F10, F1-F9 (on a Mac, hold fn if they control the screen or sound) |
| BREAK | F12 (Keys menu: CTRL+BREAK for a cold start, SHIFT+BREAK to boot) |
| COPY | End |
| CAPS LOCK | Caps Lock, or Option/Alt in the Physical layout |
| SHIFT LOCK | Keys menu |
| DELETE | Backspace or Delete |
| ESCAPE | Esc |
| `^ ~` `_ £` `@` | `=`, `` ` `` and `\` in the Physical layout |
| `\ \|` | The key next to the left Shift on ISO keyboards, or the Keys menu |

The title bar shows `[CAPS]` and `[SHIFT LOCK]` while their LEDs are lit. With
CAPS LOCK on, a letter is a capital with or without SHIFT, as on the real
machine. Paste as Typing turns CAPS LOCK off when it meets lower case, and skips
characters the BBC can't type, such as `` ` ``.

## Platform independence

The emulation core lives in `commonMain` source sets and depends only on the
common Kotlin standard library. It declares JVM and Kotlin/Native macOS, Linux and
Windows targets. Loading test fixtures and ROM files lives in `jvmTest`. Only the
JVM tests have been run so far. The `desktop` front end is JVM-only, because it
uses Swing.

## Build and test

Use a JDK 17 or newer. The Gradle wrapper pins Gradle 8.10 and verifies its
archive checksum; Kotlin is pinned to 2.2.21.

```sh
./gradlew :cpu:jvmTest :bbc:jvmTest
```

Tests use checked-in fixtures and ROMs; they download nothing.

## CPU module

The production code is in `cpu/src/commonMain/kotlin/beebslop/cpu`.

- `MemoryBus.kt`: the boundary between the CPU and the machine.
- `CpuState.kt`: validated register values for inspection and initialization.
- `StatusFlag.kt`: named processor-status bits.
- `Instruction.kt`: instruction and addressing-mode names, and the
  `UndocumentedOpcodeException` thrown in strict mode.
- `InstructionSet.kt`: explicit lookup table for all 256 opcodes, with the 105
  undocumented ones in a separate, marked section.
- `Cpu6502.kt`: instruction execution, address calculation, arithmetic, stack,
  and interrupt handling. Each instruction emits its actual read/write sequence;
  cycle counts are derived from those accesses rather than a separate table.

### Connecting a machine

Implement `MemoryBus` to route reads and writes to RAM, ROM, or hardware:

```kotlin
import beebslop.cpu.Cpu6502
import beebslop.cpu.MemoryBus

val memory = ByteArray(65536)
val bus = object : MemoryBus {
    override fun read(address: Int): Int = memory[address].toInt() and 0xFF

    override fun write(address: Int, value: Int) {
        memory[address] = value.toByte()
    }
}

// A tiny program: LDA #$42; STA $80; JMP $0204.
memory[0x0200] = 0xA9.toByte()
memory[0x0201] = 0x42
memory[0x0202] = 0x85.toByte()
memory[0x0203] = 0x80.toByte()
memory[0x0204] = 0x4C
memory[0x0205] = 0x04
memory[0x0206] = 0x02
memory[0xFFFC] = 0x00
memory[0xFFFD] = 0x02

val cpu = Cpu6502(bus)
cpu.reset() // Seven bus cycles; loads PC from $FFFC/$FFFD.
cpu.step()  // LDA: two cycles.
cpu.step()  // STA: three cycles. memory[$80] is now $42.
```

`step()` completes one instruction, or services one interrupt, and returns its
CPU cycle count. `totalCycles` accumulates bus cycles, including reset. It is not
reset by `reset()` or `loadState()`.

Every bus call represents a CPU cycle, including dummy reads and the original-
value write of an NMOS read-modify-write instruction. The machine can advance
its hardware clock in those calls and account for contention separately. The
CPU itself performs no sleeping, pacing, allocation of machine memory, or
host I/O. Bus callbacks must not re-enter the CPU or throw.

Registers are readable but not directly mutable. `state()` returns their values;
`loadState()` validates and loads them without accessing memory. It clears pending
NMI and resamples I, preserves the IRQ input and cycle count, and is intended for
tests/debuggers. It is not an exact whole-machine save-state facility.

### Implemented behavior

- All 256 NMOS 6502 opcodes: the 151 documented ones and the 105 undocumented
  ones (see below), with their addressing modes and bus cycles.
- Binary and NMOS decimal ADC/SBC, including NMOS flag behavior.
- Zero-page, stack, and 16-bit address wrapping; indirect JMP page-wrap quirk.
- Page-crossing cycles, taken-branch cycles, dummy accesses, and RMW double writes.
- Reset, BRK, IRQ, NMI, RTI, and the status-byte conventions for stack operations.
- IRQ-mask delay after CLI, SEI, and PLP; immediate mask restoration after RTI.

IRQ is level-sensitive: set `irqAsserted` and leave it set until its source is
cleared. `requestNmi()` represents one active NMI edge and latches a pending
request. At a step boundary, pending NMI wins over eligible IRQ. Reset clears
pending NMI but leaves the externally driven IRQ level alone.

This is an instruction-stepped CPU with bus-access sequencing, **not a pin-level
6502 model**. IRQ/NMI are serviced at step boundaries. Exact intra-instruction
interrupt polling, branch interrupt-poll quirks, NMI hijacking of BRK/IRQ vectors,
RDY stalls, SO input, and asynchronous reset are not implemented. Full BBC Micro
timing compatibility will require further work around these boundaries.

#### Undocumented opcodes

They run as on a real NMOS 6502, because some BBC Micro software uses them:

- Stable combinations: SLO, RLA, SRE, RRA, DCP, ISC (read-modify-write, then an
  ALU operation), SAX, LAX, ANC, ALR, ARR (including its NMOS decimal-mode
  behavior), SBX, LAS, the `$EB` duplicate of SBC, and 27 NOPs that still perform
  their addressing mode's reads.
- Unstable on real chips: ANE (`$8B`) and LXA (`$AB`) use the magic constant
  `$EE`; SHA, SHX, SHY and TAS AND the value with the base address's high byte + 1,
  and on a page crossing the value also replaces the address's high byte. These
  follow the reference vectors; software relying on them is unreliable on real
  6502s too.
- JAM (12 opcodes) halts the CPU: `halted` becomes true, IRQ and NMI are ignored,
  and each `step()` performs one idle read of `$FFFF` so machine time continues.
  `reset()` restarts it. The desktop app pauses and reports a JAM.

Set `rejectUndocumentedOpcodes = true` for a strict mode that throws
`UndocumentedOpcodeException` after the opcode fetch instead. This helps catch a
program, or an emulator bug, that has jumped into data.

Hardware power-on register values
are undefined; the core starts deterministically with zeroed A/X/Y/PC/SP and I
set. The initial reset takes SP from $00 to $FD. Subsequent resets decrement the
current SP by three and preserve A/X/Y and decimal mode.

### Verification

`commonTest` covers reset/interrupt sequences, stack behavior, bus accesses,
address boundaries, flags, and errors. Arithmetic tests exhaust all 131,072
input/carry combinations for each binary ADC and SBC, plus 40,000 valid BCD
addition/subtraction cases.

`jvmTest` additionally runs:

- 60,700 pinned [SingleStepTests](https://github.com/SingleStepTests/65x02)
  cases covering all 256 opcodes, comparing registers, memory, every bus
  address/value/direction, and cycle counts. This includes all 10,000 cases for
  immediate ADC, SBC and ARR, with valid and invalid decimal digits.
- [Klaus Dormann's functional test](https://github.com/Klaus2m5/6502_65C02_functional_tests),
  starting at $0400 and requiring the success loop at $3469, with an instruction
  limit to make failures terminate.

See `cpu/src/jvmTest/resources/reference/README.md` for provenance and licenses.
These tests establish the CPU behavior above; they do not establish
complete BBC Micro compatibility or pin-level interrupt accuracy.

## BBC machine module

The production code is in `bbc/src/commonMain/kotlin/beebslop/bbc`.

| File | Contents |
|---|---|
| `BbcMachine.kt` | One Model B: the chips and their wiring, with BASIC in ROM 15 |
| `BbcMemoryBus.kt` | Address decoding, per the *Advanced User Guide*, chapter 17 |
| `PagedRoms.kt` | The four paged ROM sockets and ROMSEL |
| `Sheila.kt` | The SHEILA device table and the `IoDevice` interface |
| `BbcClock.kt` | Machine time in 2 MHz ticks, with 1 MHz cycle stretching |
| `Via6522.kt` | The 6522 VIA, used twice: the system VIA and the user VIA |
| `AddressableLatch.kt` | IC32, the 74LS259 on system VIA port B |
| `KeyboardMatrix.kt` | The keyboard matrix, its scan counter and the start-up links |
| `KeyboardText.kt` | Which key and SHIFT type each character |
| `Typist.kt` | Presses keys frame by frame, for pasted text and menu chords |
| `Crtc6845.kt` | The HD6845S CRTC: registers, counters and vertical sync |
| `VideoUla.kt` | The video ULA's control and palette registers |
| `ScreenAddress.kt` | The board's screen address logic, including scroll wrap-around |
| `Video.kt` | The picture, built one character at a time from the 6845's outputs |
| `Saa5050.kt` | The SAA5050 teletext character generator for MODE 7 |
| `TeletextFont.kt` | The SAA5050's character shapes, generated from Bedstead |
| `Sn76489.kt` | The SN76489A sound chip, and resampling for the host |
| `Acia6850.kt` | The 6850 ACIA's and serial ULA's registers |

### Memory map

| Address | Contents | Bus cycle |
|---|---|---|
| `&0000-&7FFF` | RAM, 32 KiB | 2 MHz |
| `&8000-&BFFF` | Paged ROM, selected by ROMSEL | 2 MHz |
| `&C000-&FBFF`, `&FF00-&FFFF` | MOS 1.20 | 2 MHz |
| `&FC00-&FDFF` | FRED and JIM, the 1 MHz bus; nothing fitted reads `&FF` | 1 MHz |
| `&FE00-&FEFF` | SHEILA; see below | per device |

| SHEILA | Device | Bus cycle | Emulated |
|---|---|---|---|
| `&00-&07` | 6845 CRTC | 1 MHz | Registers, counters, vsync |
| `&08-&0F` | 6850 ACIA | 1 MHz | Registers and interrupts |
| `&10-&17` | Serial ULA | 1 MHz | Register |
| `&18-&1F` | STATID (Econet station number) | 1 MHz | No |
| `&20-&2F` | Video ULA | 2 MHz | Registers |
| `&30-&3F` | ROMSEL | 2 MHz | Yes |
| `&40-&5F` | System VIA | 1 MHz | Yes, without the shift register |
| `&60-&7F` | User VIA | 1 MHz | Yes, with nothing on its ports |
| `&80-&9F` | 8271 disc controller | 2 MHz | No; reads `&FF` |
| `&A0-&BF` | 68B54 ADLC (Econet) | 2 MHz | No |
| `&C0-&DF` | uPD7002 ADC | 1 MHz | No |
| `&E0-&FF` | Tube | 2 MHz | No |

The address ranges are from the *Advanced User Guide*, chapter 17. The list of
1 MHz devices is from
[BeebWiki: Cycle stretching](https://beebwiki.mdfs.net/Cycle_stretching). SHEILA
addresses with no device attached read `&FE`, and the empty disc controller slot
reads `&FF`, as in MAME. Real hardware leaves the data bus floating, and emulators
differ: b2 returns 0.

**Paged ROMs.** The board has four sockets, which the *Advanced User Guide*
numbers as ROMs 12-15 (chapter 21). ROMSEL is write-only, and without an
expansion board only its two low bits select a socket, so each ROM also answers
under three other numbers: BASIC, in ROM 15, also appears as 3, 7 and 11. This
follows MAME's `bbcb.cpp`. Empty sockets read `&FF`.

**Timing.** Machine time counts 2 MHz ticks. The CPU and video share RAM by
interleaving, so the display never holds up the CPU, and an ordinary bus cycle is
one tick. A 1 MHz access is stretched until it ends on a 1 MHz clock edge, which
makes it 2 or 3 ticks depending on the phase it starts in. Each access in an
instruction is stretched separately, so `INC &FE60` stretches its read and both
writes. In this model the 1 MHz edges fall on even ticks. The VIAs and the
keyboard's scan counter tick on those edges; the CRTC ticks once per character,
at 2 MHz or 1 MHz as the video ULA selects.

### Chips and wiring

The wiring follows MAME's `bbcb.cpp` and the *Advanced User Guide*, chapter 23.

- **System VIA.** Port A is the slow data bus to the keyboard and the sound
  chip. Port B bits 0-3 drive the addressable latch; bits 4-7 read 1 (fire
  buttons up, no speech chip). CA1 is the CRTC's vertical sync and CA2 the
  keyboard interrupt.
- **Addressable latch.** B0 enables sound writes, B3 switches the keyboard
  between polled and free-running, B4-B5 give the screen size for wrap-around,
  B6-B7 drive the lock LEDs.
- **Interrupts.** IRQ is the OR of both VIAs and the ACIA. The ACIA has to be
  there for the OS to run: its interrupt handler checks the ACIA first, and an
  absent device reading `&FE` looks like an ACIA interrupt that never clears.
- **Power-on and BREAK.** The machine starts powered on, with both VIAs reset.
  BREAK resets the CPU and the user VIA but not the system VIA, which is how the
  OS tells BREAK from power-on: it reads the system VIA's interrupt enable
  register first thing.
- **6522 timers.** Timer 1 counts N ... 0, `&FFFF`, then reloads N, flagging as
  it reaches `&FFFF`, so it runs N+2 cycles; it reloads in one-shot mode too.
  Timer 2 is one-shot and keeps counting. After T1C-H or T2C-H is written, the
  counter holds for one cycle. This follows MAME's `6522via.cpp`. MOS 1.20
  loads timer 1's latch with `&270E` (9998) in free-running mode, which gives
  its 100 Hz clock: 10,000 cycles.
- **6845 in MODE 7.** MODE 7 uses interlace sync and video mode, where the
  raster counter steps by 2 and a row ends when it matches R9 ignoring bit 0.
  Each field then has 31 rows of 10 lines plus 2, which is 312 lines of 64 µs,
  and vertical sync comes at 50 Hz. The extra half line of an interlaced field
  is not produced yet.
- **Screen wrap-around.** MOS 1.20 writes latch B5 B4 = 1 0 for modes 0-2, 0 0
  for mode 3, 1 1 for modes 4-5 and 0 1 for mode 6, and MAME's address logic
  wraps each to the right screen start. The table in the online copy of the
  *Advanced User Guide* (23.2) has the values for modes 0-2 and 4-5 swapped.
- **Sound.** The SN76489A is the variant in MAME's BBC driver, clocked at 4 MHz
  and dividing by 16, so its counters step at 250 kHz: every 8 machine ticks.
  A tone flips every N counts (250 kHz / 2N; N = 0 acts as 1024). Noise is a
  16-bit shift register with taps 2 and 3 for white noise, stepped every 32, 64
  or 128 counts or at half tone 2's rate, and reset when its register is written.
  Attenuation is 2 dB a step and 15 is off. The chip takes a byte as latch B0
  goes low. At power-on it plays all channels at full volume until MOS silences
  it. This follows MAME's `sn76496.cpp`. For the host, the output is averaged
  over each sample and passed through a DC-blocking filter, like the BBC's
  output capacitor.
- **Placeholders.** The ACIA and serial ULA hold their registers, but no bits
  move: transmitted bytes are dropped.

### Video

`Video.kt` builds a 768 by 576 RGB picture the way a PAL monitor would: one
character at a time as the 6845 produces it, so palette and register changes in
the middle of a frame appear where they happen. The rendering follows MAME's
`bbc_v.cpp` and `saa5050.cpp`.

- **Geometry.** Horizontally each pixel is 1/16 µs, so 2 MHz characters are 8
  pixels and 1 MHz characters 16. The picture starts 10 µs after horizontal sync
  and 22 lines after vertical sync. In every mode the MOS's settings put the
  display 32 lines after vertical sync and 13-15 µs after horizontal sync, so
  all modes land in about the same place. Interlaced fields are woven together;
  a non-interlaced picture has each line drawn twice. The desktop shows a
  704 by 528 part of it: the 640 by 512 display of modes 0-6 at (80, 20) with a
  border of 32 pixels at the sides and 8 at the top and bottom. MODE 7's
  display is 3 pixels narrower and lands at (83, 22).
- **Modes 0-6.** Each byte is shifted out at the video ULA's pixel rate (2, 4, 8
  or 16 MHz). The logical colour is bits 7, 5, 3 and 1, and 1s are shifted in, so
  the same logic serves 2, 4 and 16 colours. The palette maps it to a physical
  colour, stored inverted, with bit 3 for flashing. Raster lines 8-9 of a
  character row are blank, giving the gaps in modes 3 and 6.
- **MODE 7.** With MA13 set, the byte at `&7C00` onwards goes to the SAA5050.
  Its 12 dots per 1 µs character are stretched to 16 pixels. It implements
  coloured text and graphics, contiguous and separated graphics, hold graphics,
  double height, flash, conceal, and new and black backgrounds. Each displayed
  line starts fresh with white text on black. The field (the 6845's RA0) chooses
  which half of each rounded dot row is drawn. The board's latch holds each byte
  for one character, and the SAA5050 outputs each character one character after
  taking it in. So the picture comes 2 µs after the 6845's timing, which lines
  MODE 7 up with the other modes. MOS sets the 6845's skew to match: display
  enable 1 character (the latch), cursor 2 (the whole path).
- **Cursor.** The 6845's cursor inverts the colours of 1, 2 or 4 characters, as
  the video ULA's control register sets, from where the skewed cursor signal
  arrives.
- **Not yet emulated:** the extra half line of an interlaced field; teletext
  boxing, which the BBC doesn't use.

### Verification

`commonTest` checks:

- RAM, MOS and paged ROM decoding, ROMSEL for all 256 values, and the SHEILA
  table.
- The stretched timing of every SHEILA address from both clock phases, and of
  whole instructions (`LDA`, `STA` and `INC`) to 1 MHz and 2 MHz devices.
- The 6522's timer sequences, one-shot and free-running interrupts, interrupt
  enable and flag registers, CA1 and CA2 edges and handshake clearing, and port
  reads and writes.
- The latch, keyboard polling, scanning and links, sound chip registers, CRTC
  register masks and read-back, vertical sync length, and MODE 7's 50 Hz field.
- Screen addresses for MODE 7, MODE 0 and the wrap-around of every screen size.
- The ACIA's status and transmit interrupt.
- The sound chip: tone pitch, period 0, the attenuation steps, periodic and
  white noise, and resampled output with no DC offset.

`jvmTest` uses the real ROMs. It runs MOS 1.20 from its reset vector to its first
I/O access, and checks that it reads the system VIA's interrupt enable register
with a stretched cycle and gets `&80`. It then boots to the BASIC prompt and
reads MODE 7 screen memory:

```text

BBC Computer 32K

BASIC

>
```

Row 0 is blank because the MOS prints a line feed before the banner. The tests
also check that vertical sync runs at 50 Hz, and that BREAK is a warm start. On
BREAK the MOS prints "BBC Computer" without the memory size, because the size
and its beep come from separate strings that it prints only at power-on. Fitting
start-up links 6-8 boots in MODE 0. BASIC is found in ROM 15 and its three
aliases, and the I/O pages hide the MOS bytes beneath them. MOS silences the
sound chip at reset, and VDU 7 sounds a tone that stops again.

The typing tests press keys in the matrix as the typist does. They type
`10 REM` followed by every character the BBC can type and read the line back
from BASIC's program memory, check that the typist turns SHIFT LOCK and CAPS
LOCK off when it needs to, that CTRL+BREAK is a cold start that clears the
program, and that f0 types its `*KEY` definition. The table in `KeyboardText`
came from pressing every key, with and without SHIFT, on the real ROM; it agrees
with MAME.

The video tests draw through the real MOS VDU drivers: a small routine in RAM
sends bytes through OSWRCH, which is quicker than typing. They check
pixels, and write PNGs to `bbc/build/test-frames/`:

- the boot screen, white text on black, with row 0 blank;
- MODE 7 graphics colour bars in all seven colours, and double-height text;
- the MODE 7 cursor on the cell after the prompt, one character wide;
- MODE 2 bars in its eight colours;
- MODE 0 text, and MODE 6's 8 lit and 2 blank lines per row.

`commonTest` also checks the SAA5050: the letter A with character rounding,
solid and separated mosaics, when colour changes take effect, and held graphics.

## License

Beebslop is licensed under the [BSD 3-Clause License](LICENSE). Third-party files
keep their own licenses: see [`NOTICE`](NOTICE), [`REUSE.toml`](REUSE.toml) and
[`LICENSES/`](LICENSES/).

## Acknowledgements

See [`ACKNOWLEDGEMENTS.md`](ACKNOWLEDGEMENTS.md).

## ROMs

`roms/` holds the two ROMs the Model B needs. They are copyright Acorn Computers
Ltd and are included without a licence; see
[`LICENSES/LicenseRef-Acorn-ROM.txt`](LICENSES/LicenseRef-Acorn-ROM.txt).

| File | Contents | SHA-1 | Matches |
|---|---|---|---|
| `roms/os.rom` | MOS 1.20, 16 KiB | `0d9bcaf6a393c9ce2359ed700ddb53c232c2c45d` | MAME `os12.rom` (CRC `3c14fc70`) |
| `roms/basic.rom` | BBC BASIC II, 16 KiB | `4a7393f3a45ea309f744441c16723e2ef447a281` | MAME `basic2.rom` (CRC `79434781`) |

Both were taken from [b2](https://github.com/tom-seddon/b2)'s `etc/roms/`
(`OS12.ROM`, `BASIC2.ROM`). The BASIC image is byte-identical to Elkslop's,
because the Electron runs the same BASIC II.
