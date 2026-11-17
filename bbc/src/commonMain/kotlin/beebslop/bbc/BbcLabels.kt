// SPDX-FileCopyrightText: 2026 The Elkslop contributors
// SPDX-FileCopyrightText: 2026 The Beebslop contributors
// SPDX-License-Identifier: BSD-3-Clause

package beebslop.bbc

/**
 * Names for the addresses BBC Micro software calls and pokes most: the OS
 * entry points at the top of memory, the OS vectors in page 2, and the
 * registers of the chips in SHEILA. A debugger or disassembler can show
 * `JSR OSWRCH` instead of `JSR $FFEE`, and `STA SYSVIA_ORB` instead of
 * `STA $FE40`. Adapted from Elkslop's `ElectronLabels`.
 *
 * The OS entry point and vector names are Acorn's, as given in the Advanced
 * User Guide (see ACKNOWLEDGEMENTS.md). The 6522 and 6845 register names are
 * the chip makers' (`ORB`, `T1C_L`, `IFR`, and so on); the prefixes that say
 * which chip, such as `SYSVIA_` and `CRTC_`, are Beebslop's own. Where a
 * write and a read reach different registers, the name gives both, as in
 * `FDC_COMMAND_STATUS`. Only each register's first address is named, not the
 * copies above it in its region.
 */
object BbcLabels {
    private val viaRegisters = listOf(
        "ORB", "ORA", "DDRB", "DDRA", "T1C_L", "T1C_H", "T1L_L", "T1L_H",
        "T2C_L", "T2C_H", "SR", "ACR", "PCR", "IFR", "IER", "ORA_NH",
    )

    private val names: Map<Int, String> = buildMap {
        // OS entry points.
        putAll(mapOf(
            0xFFB9 to "OSRDRM", 0xFFBC to "VDUCHR", 0xFFBF to "OSEVEN",
            0xFFC2 to "GSINIT", 0xFFC5 to "GSREAD", 0xFFC8 to "NVRDCH", 0xFFCB to "NVWRCH",
            0xFFCE to "OSFIND", 0xFFD1 to "OSGBPB", 0xFFD4 to "OSBPUT", 0xFFD7 to "OSBGET",
            0xFFDA to "OSARGS", 0xFFDD to "OSFILE", 0xFFE0 to "OSRDCH", 0xFFE3 to "OSASCI",
            0xFFE7 to "OSNEWL", 0xFFEC to "OSWRCR", 0xFFEE to "OSWRCH", 0xFFF1 to "OSWORD", 0xFFF4 to "OSBYTE",
            0xFFF7 to "OSCLI",
        ))
        // OS vectors.
        putAll(mapOf(
            0x0200 to "USERV", 0x0202 to "BRKV", 0x0204 to "IRQ1V", 0x0206 to "IRQ2V",
            0x0208 to "CLIV", 0x020A to "BYTEV", 0x020C to "WORDV", 0x020E to "WRCHV",
            0x0210 to "RDCHV", 0x0212 to "FILEV", 0x0214 to "ARGSV", 0x0216 to "BGETV",
            0x0218 to "BPUTV", 0x021A to "GBPBV", 0x021C to "FINDV", 0x021E to "FSCV",
            0x0220 to "EVNTV", 0x0222 to "UPTV", 0x0224 to "NETV", 0x0226 to "VDUV",
            0x0228 to "KEYV", 0x022A to "INSV", 0x022C to "REMV", 0x022E to "CNPV",
            0x0230 to "IND1V", 0x0232 to "IND2V", 0x0234 to "IND3V",
        ))
        // SHEILA.
        put(0xFE00, "CRTC_ADDRESS")
        put(0xFE01, "CRTC_DATA")
        put(0xFE08, "ACIA_CONTROL_STATUS")
        put(0xFE09, "ACIA_DATA")
        put(0xFE10, "SERIAL_ULA")
        put(0xFE20, "VIDEO_ULA_CONTROL")
        put(0xFE21, "VIDEO_ULA_PALETTE")
        put(0xFE30, "ROMSEL")
        for ((i, name) in viaRegisters.withIndex()) {
            put(0xFE40 + i, "SYSVIA_$name")
            put(0xFE60 + i, "USRVIA_$name")
        }
        put(0xFE80, "FDC_COMMAND_STATUS")
        put(0xFE81, "FDC_PARAMETER_RESULT")
        put(0xFE84, "FDC_DATA")
        put(0xFEC0, "ADC_START_STATUS")
        put(0xFEC1, "ADC_HIGH")
        put(0xFEC2, "ADC_LOW")
    }

    /** The name for [address], or null. Fits the `label` parameter of `Disassembler`. */
    fun forAddress(address: Int): String? = names[address and 0xFFFF]
}
