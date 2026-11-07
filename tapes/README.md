# Tapes

Cassette images for the emulator, in [UEF](https://en.wikipedia.org/wiki/Unified_Emulator_Format)
format. They come from the [Stairway to Hell](https://www.stairwaytohell.com/)
UEF archive, through its mirror on [beebstuff.uk](https://www.beebstuff.uk/f/acorn/STH-UEF/),
which serves the `.uef` files without zips. The Stairway to Hell's own link for
them (`/bbc/uefarchive/leisure/Acornsoft/Welcome_B.zip`) returned 404 on
7 November 2026. Each file here is the mirror's `.uef`, unchanged, so it's still
gzipped. Both were made with MakeUEF 0.3b, according to the origin chunk.

| File | What it is | Files on tape | Source |
|---|---|---|---|
| `welcome-english.uef` | The Welcome cassette | 17 | [`Welcome_B.uef`](https://www.beebstuff.uk/f/acorn/STH-UEF/leisure/Acornsoft/Welcome_B.uef) |
| `welcome-german.uef` | The German Welcome cassette | 27 (18 programs) | [`Welcome-German_B.uef`](https://www.beebstuff.uk/f/acorn/STH-UEF/leisure/Acornsoft/Welcome-German_B.uef) |

The same folder has the BBC Master's Welcome tape, in two sides
(`Welcome-BBCMaster-Side1_BM.uef` and `-Side2_BM.uef`). It isn't included, because
Beebslop emulates the Model B.

## Playing them

Choose **Tape > Insert Tape...**, or start with a tape in the recorder:

```sh
./run.sh --tape tapes/welcome-english.uef --type 'CHAIN ""\n'
```

`CHAIN ""` loads and runs the next program on the tape, and `*CAT` lists it.
Tapes play at the real 1200 baud, about 120 bytes a second, so the whole English
tape takes almost 20 minutes. Turn on **Tape > Fast Loading** to skip the wait.

## The Welcome cassette

The demonstration tape supplied with the BBC Micro, with the *Welcome Guide*
booklet, which isn't included. The byte counts are of the files as stored on the
tape.

| File | Bytes | File | Bytes |
|---|---:|---|---:|
| INTRO | 2190 | BAT'N'BALL | 2196 |
| INDEX | 2746 | MUSIC | 1273 |
| KEYBOARD | 9541 | CLOCK | 2233 |
| SKETCH | 2013 | PHOTO | 2548 |
| CALCULATOR | 5327 | PATTERNS | 1954 |
| ALPHASORT | 4555 | KINGDOM | 8953 |
| POEM | 9721 | BIORHYTHMS | 6576 |
| TELEPHONE | 7275 | BIO | 848 |
| | | MESSAGE | 1119 |

## The German Welcome cassette

This is not a translation of the English tape. Its line-up follows the
Electron's German *Einführungskassette* (in [Elkslop](https://github.com/RikEnde/elkslop)'s
`tapes/`), in nearly the same order. Some names differ: SKETCH for MALER,
BOTSCHAFT for HINWEIS, MARSLAND for LANDUNG, ADIEU for BISBALD. The BBC tape also
has an INTRO. INSEL, ISLESKR and PLASKR are the same size on both tapes, and the
rest differ. We haven't compared the programs themselves.

| File | Bytes | File | Bytes |
|---|---:|---|---:|
| WELCOME | 131 ×10 | MUSTER | 2573 |
| INTRO | 4542 | MARSLAND | 4647 |
| TASTATUR | 12943 | GALAXIS | 4109 |
| SKETCH | 3601 | INSEL | 1920 |
| KLAVIER | 6107 | ISLESKR | 14276 |
| AUTODROM | 5376 | PLANETEN | 1664 |
| BIORHYTHMS | 4294 | PLASKR | 11158 |
| UHRZEIT | 2922 | ADIEU | 1485 |
| GOMUKU | 6607 | | |
| BOTSCHAFT | 1697 | | |

- **WELCOME is recorded ten times.** It plays a tone and says when the volume is
  set right ("Ihre Lautstaerke ist jetzt richtig eingestellt !"), so you can set
  up your recorder before loading the rest.
- **GOMUKU** is spelled that way on the tape.

## Checks

Apart from the origin note, which the emulator skips, both use only the data,
carrier and gap chunks it reads. `*CAT` lists every file on both tapes without
an error. `TapeLoadTest` loads INTRO from both tapes through the real MOS and
compares RAM with the file on the tape. No test covers the later programs.

| File | SHA-256 |
|---|---|
| `welcome-english.uef` | `4e27253b95eb6e1bf71cfcd42c175e8d96ddfcbaafc3d3b536b7b0f058731a55` |
| `welcome-german.uef` | `8678f1db9bf353bbe0043cc7c4b145bce808ac80ddfb0bee33550972d9cda29b` |

## Copyright

These images are © Acorn Computers Ltd. They aren't covered by Beebslop's BSD
licence, and no licence for them has been granted to this project; see
[LicenseRef-Acorn-ROM.txt](../LICENSES/LicenseRef-Acorn-ROM.txt).
