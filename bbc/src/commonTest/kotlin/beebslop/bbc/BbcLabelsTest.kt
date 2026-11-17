// SPDX-FileCopyrightText: 2026 The Beebslop contributors
// SPDX-License-Identifier: BSD-3-Clause

package beebslop.bbc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BbcLabelsTest {
    @Test
    fun namesTheOsAndSheila() {
        assertEquals("OSWRCH", BbcLabels.forAddress(0xFFEE))
        assertEquals("WRCHV", BbcLabels.forAddress(0x020E))
        assertEquals("ROMSEL", BbcLabels.forAddress(0xFE30))
        assertEquals("SYSVIA_ORB", BbcLabels.forAddress(0xFE40))
        assertEquals("SYSVIA_IFR", BbcLabels.forAddress(0xFE4D))
        assertEquals("USRVIA_ORA_NH", BbcLabels.forAddress(0xFE6F))
        assertEquals("FDC_DATA", BbcLabels.forAddress(0xFE84))
        // Only the first address of each register is named.
        assertNull(BbcLabels.forAddress(0xFE50))
        assertNull(BbcLabels.forAddress(0x1900))
    }
}
