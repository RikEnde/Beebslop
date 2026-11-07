# Discs

Disc images for the emulator: SSD files hold one side, DSD files two. They need
the disc interface, which is fitted when `roms/dnfs.rom` is there.

| File | What it is | Source |
|---|---|---|
| `welcome.ssd` | The Welcome disc, with Acorn's disc utilities | [b-em](https://github.com/stardot/b-em)'s [`discs/Welcome.ssd`](https://github.com/stardot/b-em/blob/master/discs/Welcome.ssd) |
| `blank-40.ssd` | A formatted, empty 40-track single-sided disc | Made by Beebslop |
| `blank-80.ssd` | A formatted, empty 80-track single-sided disc | Made by Beebslop |
| `blank-80.dsd` | A formatted, empty 80-track double-sided disc | Made by Beebslop |

## Using them

Choose **Disc > Drive 0 > Insert Disc...**, or start with a disc in the drive:

```sh
./run.sh --disc discs/welcome.ssd --boot
```

`--boot` presses SHIFT+BREAK, which boots the disc. `*CAT` lists a disc, and
`CHAIN "NAME"` runs a BASIC program. `CHAIN ""` only works on tape.

Changes are saved to the image file, so copy a blank disc before using it:

```sh
cp discs/blank-80.ssd my-disc.ssd
./run.sh --disc my-disc.ssd
```

Or choose **Disc > Drive 0 > New Blank Disc**, which makes one in memory, and
**Save Disc As...** to keep it.

## The Welcome disc

The disc version of the Welcome package: the programs from the
[Welcome cassette](../tapes/README.md), and Acorn's utility programs. SHIFT+BREAK
shows a welcome message, then SPACE gives a menu of the programs. Catalogued with
`*CAT`, it has the title `WELCOME-DISK` and boot option 3 (`*EXEC !BOOT`).

| Directory | Files |
|---|---|
| `$` | `!BOOT`, `!B` (the menu), `content`, FORM40, FORM80, VERIFY, DCONV |
| `W` | WELCOME, INDEX, KEYBD, SKETCH, CALC, ALPHA, POEM, PHONE, BATBALL, MUSIC, CLOCK, PHOTO, PATTERN, KINGDOM, BIORTHM, BPART2, HELP |

- **FORM40 and FORM80** format a disc: `*FORM80`, then the drive number. DFS
  1.20 has no format command of its own.
- **VERIFY** reads every sector of the current drive: `*DRIVE 1`, then `*VERIFY`.
- **DCONV** converts discs for older Acorn disc systems: `CHAIN "DCONV"`.
- **The programs chain each other by name, from directory `W`.** The menu sets
  `*DIR W` first. To run one yourself, do the same: `*DIR W`, then
  `CHAIN "ALPHA"`. Started as `CHAIN "W.ALPHA"` from `$`, it ends with
  `Not found` when it tries to load the next one.
- **"Go on to the next program (Y/N)?" waits for RETURN** after the Y or N, so
  you can change your mind.

The image is the one b-em ships, byte-identical to the copy in
[b2](https://github.com/tom-seddon/b2)'s `etc/discs/`. Neither says where it was
imaged from. Both emulators are GPL; the disc is Acorn's, not theirs.

## The blank discs

Written by `Disc.blank` in this project, as FORM40 or FORM80 leave a disc: an
empty catalogue, and every other byte `&E5`, the format filler. They are
byte-identical to b2's `40.ssd`, `80.ssd` and `80.dsd`, which were made
independently. They are Beebslop's own, under its BSD licence.

## Checks

`DiscRomTest` catalogues and boots `welcome.ssd`, and formats and verifies a disc
with its FORM40 and VERIFY. No test runs the Welcome programs, though a few have
been tried by hand.

| File | SHA-256 |
|---|---|
| `welcome.ssd` | `c3da7ea982d0d7e93411abbb1f807a82e821f0d339bd0570d3274896d54b7833` |
| `blank-40.ssd` | `cd44213f73cf986948e7d4cea2b4a18f7bb9c19e0b98ebdaf98d7e8f1bbed2f7` |
| `blank-80.ssd` | `c41ca728a53c477a49426c821280b659aaaaf313e051995861cc548a36fe980c` |
| `blank-80.dsd` | `17afda6c5961743ba8f1c3dffb263a6f72193cdfd41fa088f0288a58576594fa` |

## Copyright

`welcome.ssd` is © Acorn Computers Ltd. It isn't covered by Beebslop's BSD
licence, and no licence for it has been granted to this project; see
[LicenseRef-Acorn-ROM.txt](../LICENSES/LicenseRef-Acorn-ROM.txt).
