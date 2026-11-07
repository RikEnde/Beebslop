# Acknowledgements

Beebslop exists because many people spent years working out how the BBC Micro
behaves, and wrote it down. This is a record of the work it relies on. It will
grow as the emulator does.

## The machine

The BBC Microcomputer was designed by Acorn Computers for the BBC Computer
Literacy Project, and launched in 1981. Steve Furber and Sophie Wilson designed
its hardware, and Sophie Wilson wrote BBC BASIC.

Like the Electron's, the MOS 1.20 ROM hides a list of thanks where no program can
read it, at `&FC00-&FEFF`, under FRED, JIM and SHEILA. We reproduce it here so it
can be seen:

> (C) 1981 Acorn Computers Ltd. Thanks are due to the following contributors to
> the development of the BBC Computer (among others too numerous to mention):-
> David Allen, Bob Austin, Ram Banerjee, Paul Bond, Allen Boothroyd, Cambridge,
> Cleartone, John Coll, John Cox, Andy Cripps, Chris Curry, 6502 designers,
> Jeremy Dion, Tim Dobson, Joe Dunn, Paul Farrell, Ferranti, Steve Furber, Jon
> Gibbons, Andrew Gordon, Lawrence Hardwick, Dylan Harris, Hermann Hauser,
> Hitachi, Andy Hopper, ICL, Martin Jackson, Brian Jones, Chris Jordan, David
> King, David Kitson, Paul Kriwaczek, Computer Laboratory, Peter Miller, Arthur
> Norman, Glyn Phillips, Mike Prees, John Radcliffe, Wilberforce Road, Peter
> Robinson, Richard Russell, Kim Spence-Jones, Graham Tebby, Jon Thackray, Chris
> Turner, Adrian Warner, Sophie Wilson, Alan Wright.

The quote is otherwise verbatim, apart from spacing after full stops and commas;
the ROM credits Sophie Wilson under her former name.

## Emulators

- **[MAME](https://github.com/mamedev/mame)**, all BSD-3-Clause:
  - the BBC Micro driver and video (`bbcb.cpp`, `bbc_v.cpp`), by Gordon
    Jefferyes and Nigel Barnes: our memory map, paged ROM selection, values for
    absent hardware, chip wiring and screen address logic;
  - the BBC keyboard (`bbc_kbd.cpp`) and serial ULA (`acorn_serproc.cpp`), by
    Nigel Barnes;
  - the 6522 VIA (`6522via.cpp`), by Peter Trauner and Mathis Rosenhauer;
  - the 6845 CRTC (`mc6845.cpp`), by Wilbert Pol and Nigel Barnes;
  - the 6850 ACIA (`6850acia.cpp`), by smf;
  - the SAA5050 teletext generator (`saa5050.cpp`), by Curt Coder and Nigel
    Barnes: our MODE 7 attributes, character rounding and mosaic graphics;
  - the SN76489 family (`sn76496.cpp`), by Nicola Salmoria: our tone and noise
    generators, noise taps and volume steps.

  Its ROM hashes for `os12.rom` and `basic2.rom` confirmed our images.
- **[b2](https://github.com/tom-seddon/b2)**, by Tom Seddon: the source of our
  ROM images. Its table of cycle-stretched addresses confirmed BeebWiki's, and
  its 6845 helped us understand interlace sync and video mode. b2 is GPL-3.0,
  and none of its code is used here.

## The teletext font

- **Ben Harris, Simon Tatham, Marnanel Thurman and Neil Williamson**:
  [Bedstead](https://bjh21.me.uk/bedstead/), whose `bedstead.c` records the
  SAA5050's character shapes from Mullard's July 1982 datasheet, checked against
  a real chip, and explains the character rounding. They dedicated it to the
  public domain. Our MODE 7 glyphs come from it.
- **Mullard**, who designed the SAA5050 and its characters.

## 6502 tests and references

The CPU comes from [Elkslop](https://github.com/RikEnde/elkslop), and with it
the work it was checked against:

- **Thomas Harte and contributors**:
  [SingleStepTests 65x02](https://github.com/SingleStepTests/65x02) (MIT), whose
  per-cycle vectors check every one of our 256 opcodes.
- **Klaus Dormann**:
  [6502 functional test](https://github.com/Klaus2m5/6502_65C02_functional_tests)
  (GPL-3.0), included as a test fixture.
- **Norbert Landsteiner**:
  [6502 instruction set reference](https://www.masswerk.at/6502/6502_instruction_set.html),
  including the undocumented opcodes and their names.
- **Neil Parker**: [6502 opcode encoding](https://llx.com/Neil/a2/opcodes.html).
- **Michael Steil**: [how the 6502 decodes instructions](https://www.pagetable.com/31).

Licenses and pinned versions for the test fixtures are in
[`cpu/src/jvmTest/resources/reference/README.md`](cpu/src/jvmTest/resources/reference/README.md).

## Documentation

- The *BBC Microcomputer Advanced User Guide*, by Andrew C. Bray, Adrian C.
  Dickens and Mark A. Holmes, for the memory map, SHEILA and the paged ROM
  select register.
- **[BeebWiki](https://beebwiki.mdfs.net/)**: the
  [cycle stretching](https://beebwiki.mdfs.net/Cycle_stretching) page, for which
  devices run at 1 MHz.
- The Acorn community, especially the [StarDot forums](https://stardot.org.uk)
  and [BeebWiki](https://beebwiki.mdfs.net/), where much of this knowledge is
  kept alive.

If we've missed anyone, or credited something wrongly, please tell us.
