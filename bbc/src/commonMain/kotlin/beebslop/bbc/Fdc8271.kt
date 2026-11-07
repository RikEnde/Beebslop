// SPDX-FileCopyrightText: 2026 The Beebslop contributors
// SPDX-License-Identifier: BSD-3-Clause

package beebslop.bbc

/**
 * The Intel 8271 floppy disc controller of the Acorn disc interface, at
 * `&FE80-&FE9F`, with its two drives. Commands, parameters, results, special
 * registers and error codes follow MAME's `i8271.cpp`; the board's register
 * decoding and wiring follow MAME's `bbc/fdc/acorn.cpp`.
 *
 * The board puts the status/command register at offset 0, result/parameter at
 * 1, reset at 2, and the data register at 4, repeating every 8 bytes. The
 * 8271's INT line drives the 6502's NMI. Acorn DFS runs it in non-DMA mode, so
 * INT also goes high for each data byte, and the NMI handler moves the byte.
 *
 * Discs are modelled sector by sector ([Disc]), not as flux, but with the
 * timing of a real drive: the disc turns at 300 rpm, a byte passes the head
 * every 64 µs, sectors are found where they lie on the track, and a step takes
 * the time the step rate asks for. A data byte the CPU doesn't take before the
 * next one arrives ends the command with a late-DMA error, as on the real chip.
 *
 * Differences from the hardware:
 * - On the real board both READY inputs follow the head-load line. Here a
 *   drive is ready only if it also holds a disc, so DFS reports an empty drive
 *   by waiting until ESCAPE instead of searching forever.
 * - Deleted-data marks, CRCs and the Scan commands aren't modelled. Scan ends
 *   at once with "sector not found".
 * - Read Drive Status puts TRK0 in bit 1 and WR PROT in bit 3, from memory of
 *   the datasheet; MAME differs, and DFS reads only the ready bit.
 */
class Fdc8271 : IoDevice {
    /** A drive: the disc in it and where its head is. */
    class Drive {
        var disc: Disc? = null
        var headTrack: Int = 0
            internal set
    }

    val drives = arrayOf(Drive(), Drive())

    /** The head-load output, which runs the drive motors on the Acorn board. */
    var headLoaded: Boolean = false
        private set

    /** Side select, output port bit 5. */
    val side: Int get() = if (outputPort and 0x20 != 0) 1 else 0

    /** The drive the current or last command selected. */
    var selectedDrive: Int = 0
        private set

    /** INT: a result is waiting, or in non-DMA mode a data byte is. Drives NMI. */
    val interrupt: Boolean get() = irq || (nonDma && drq)

    private var phase = PHASE_IDLE
    private val command = IntArray(6)
    private var commandLength = 0
    private var expected = 0
    private var result = 0
    private var irq = false
    private var drq = false
    private var data = 0

    private var mode = 0xC0
    private val nonDma: Boolean get() = mode and 1 != 0
    private var outputPort = 0
    private var stepRate = 0
    private var headSettle = 0
    private var indexCount = 0
    private var headLoadTime = 0
    private val pcn = IntArray(2)
    private val badTracks = Array(2) { intArrayOf(0xFF, 0xFF) }
    private val notReady = BooleanArray(2)
    private var scanSector = 0
    private val scanCount = IntArray(2)

    // Rotation: ticks since the index hole. Both drives turn in step.
    private var revolution = 0
    private var idleIndexes = 0

    // The command in progress.
    private var operation = OP_NONE
    private var stage = STAGE_NONE
    private var wait = 0
    private var targetTrack = 0
    private var sectorNumber = 0
    private var sizeCode = 0
    private var count = 0
    private var indexesSeen = 0
    private var sector: Disc.Sector? = null
    private var byteIndex = 0
    private var buffer = ByteArray(0)
    private val formatted = mutableListOf<Disc.Sector>()
    private var formatCursor = 0
    private val formatId = IntArray(4)

    private val drive: Drive get() = drives[selectedDrive]

    /** One 2 MHz tick. */
    fun tick() {
        if (++revolution == TICKS_PER_REVOLUTION) {
            revolution = 0
            index()
        }
        if (stage != STAGE_NONE && wait > 0 && --wait == 0) advance()
    }

    override fun read(offset: Int): Int {
        if (offset and 4 != 0) {
            drq = false
            return data
        }
        return when (offset and 3) {
            0 -> status()
            1 -> {
                if (phase == PHASE_RESULT) phase = PHASE_IDLE
                irq = false
                result
            }
            else -> 0xFF
        }
    }

    override fun peek(offset: Int): Int {
        if (offset and 4 != 0) return data
        return when (offset and 3) {
            0 -> status()
            1 -> result
            else -> 0xFF
        }
    }

    override fun write(offset: Int, value: Int) {
        if (offset and 4 != 0) {
            if (drq) {
                drq = false
                data = value
            }
            return
        }
        when (offset and 3) {
            0 -> writeCommand(value)
            1 -> writeParameter(value)
            2 -> if (value == 1) reset()
        }
    }

    /** The reset register: abandons any command; drive positions are kept. */
    fun reset() {
        phase = PHASE_IDLE
        operation = OP_NONE
        stage = STAGE_NONE
        wait = 0
        headLoaded = false
        irq = false
        drq = false
        commandLength = 0
        result = 0
        scanSector = 0
        mode = 0xC0
        outputPort = 0
        scanCount.fill(0)
    }

    private fun status(): Int {
        var value = if (irq) SR_IRQ else 0
        when (phase) {
            PHASE_CMD -> value = value or SR_CF
            PHASE_EXEC -> {
                value = value or SR_BSY
                if (nonDma && drq) value = value or SR_DRQ
            }
            PHASE_RESULT -> value = value or SR_RF
        }
        return value
    }

    private fun writeCommand(value: Int) {
        if (phase != PHASE_IDLE) return
        val parameters = parameterCount(value and 0x3F) ?: return
        command[0] = value
        commandLength = 1
        expected = parameters + 1
        phase = PHASE_CMD
        if (commandLength == expected) start()
    }

    private fun writeParameter(value: Int) {
        if (phase != PHASE_CMD) return
        command[commandLength++] = value
        if (commandLength == expected) start()
    }

    private fun start() {
        phase = PHASE_EXEC
        selectedDrive = if (command[0] and 0x80 != 0) 1 else 0
        when (command[0] and 0x3F) {
            0x0A, 0x0E -> data(OP_WRITE, command[1], command[2], 1)
            0x0B, 0x0F -> data(OP_WRITE, command[1], command[2], command[3])
            0x12, 0x16 -> data(OP_READ, command[1], command[2], 1)
            0x13, 0x17 -> data(OP_READ, command[1], command[2], command[3])
            0x1E -> data(OP_VERIFY, command[1], command[2], 1)
            0x1F -> data(OP_VERIFY, command[1], command[2], command[3])
            0x1B -> {
                // Read ID: track, (unused), number of IDs.
                count = maxOf(1, command[3])
                begin(OP_READ_ID, command[1])
            }
            0x23 -> {
                // Format: track, gap 3, size and count, gap 5, gap 1.
                sizeCode = command[3] shr 5
                count = command[3] and 0x1F
                begin(OP_FORMAT, command[1])
            }
            0x29 -> {
                headLoaded = true
                if (command[1] == 0) {
                    operation = OP_RECALIBRATE
                    count = 255
                    step()
                } else {
                    begin(OP_SEEK, command[1])
                }
            }
            0x2C -> immediate(driveStatus().also { notReady.fill(false) })
            0x35 -> {
                when (command[1]) {
                    0x0D -> {
                        stepRate = command[2]
                        headSettle = command[3]
                        indexCount = command[4] shr 4
                        headLoadTime = command[4] and 0x0F
                    }
                    0x10, 0x18 -> {
                        val d = if (command[1] == 0x18) 1 else 0
                        badTracks[d][0] = command[2]
                        badTracks[d][1] = command[3]
                        pcn[d] = command[4]
                    }
                }
                immediate(result)
            }
            0x3A -> {
                writeSpecialRegister(command[1], command[2])
                immediate(result)
            }
            0x3D -> immediate(readSpecialRegister(command[1] and 0x3F))
            0x00, 0x04 -> finish(ERR_NF)
        }
    }

    /** Commands that need no drive time end at once, without an interrupt. */
    private fun immediate(value: Int) {
        result = value
        phase = PHASE_IDLE
    }

    private fun data(op: Int, track: Int, sectorId: Int, sizeAndCount: Int) {
        sectorNumber = sectorId
        sizeCode = sizeAndCount shr 5
        count = sizeAndCount and 0x1F
        begin(op, track)
    }

    /** Load the head, check the drive, and seek to [track] before [operation]. */
    private fun begin(op: Int, track: Int) {
        operation = op
        targetTrack = track
        headLoaded = true
        idleIndexes = 0
        if (op != OP_SEEK) {
            if (!ready(selectedDrive)) notReady[selectedDrive] = true
            if (notReady[selectedDrive]) return finish(ERR_NR)
            if ((op == OP_WRITE || op == OP_FORMAT) && drive.disc!!.writeProtected) return finish(ERR_WP)
        }
        step()
    }

    /** Move one step towards [targetTrack], or go on to the operation once there. */
    private fun step() {
        val d = selectedDrive
        if (operation == OP_RECALIBRATE) {
            if (drive.headTrack == 0) {
                pcn[d] = 0
                return finish(ERR_NONE)
            }
            if (count-- == 0) return finish(ERR_T0NF)
            drive.headTrack--
            return schedule(STAGE_STEP, stepTicks())
        }
        if (pcn[d] == targetTrack) return arrived()
        val outwards = pcn[d] > targetTrack
        do {
            pcn[d] = (pcn[d] + if (outwards) -1 else 1) and 0xFF
        } while (pcn[d] == badTracks[d][0] || pcn[d] == badTracks[d][1])
        drive.headTrack = (drive.headTrack + if (outwards) -1 else 1).coerceIn(0, MAX_HEAD_TRACK)
        schedule(STAGE_STEP, stepTicks())
    }

    private fun stepTicks() = maxOf(MIN_STEP_TICKS, stepRate * STEP_UNIT_TICKS)

    private fun arrived() {
        when (operation) {
            OP_SEEK -> finish(ERR_NONE)
            OP_FORMAT -> {
                // Writing starts at the index hole.
                stage = STAGE_WAIT_INDEX
                wait = 0
            }
            else -> {
                indexesSeen = 0
                searchId()
            }
        }
    }

    private fun track(): Disc.Track? = drive.disc?.track(side, drive.headTrack)

    /** Wait for the next ID field to pass the head. */
    private fun searchId() {
        val here = revolution
        val next = track()?.sectors?.firstOrNull { (it.position + ID_BYTES) * TICKS_PER_BYTE > here }
        if (next == null) {
            // Nothing more this turn: wait for the index hole.
            stage = STAGE_SEARCH
            wait = 0
            return
        }
        sector = next
        schedule(STAGE_ID, (next.position + ID_BYTES) * TICKS_PER_BYTE - here)
    }

    private fun schedule(next: Int, ticks: Int) {
        stage = next
        wait = maxOf(1, ticks)
    }

    private fun index() {
        val disc = drive.disc
        if (phase != PHASE_EXEC && headLoaded) {
            idleIndexes++
            if (indexCount != 0x0F && idleIndexes >= indexCount) headLoaded = false
        }
        if (disc == null) return
        when (stage) {
            STAGE_SEARCH, STAGE_ID -> {
                if (++indexesSeen == 2) return finish(ERR_NF)
                if (stage == STAGE_SEARCH) searchId()
            }
            STAGE_WAIT_INDEX -> startFormat()
            STAGE_FORMAT_END -> finish(ERR_NONE)
        }
    }

    private fun advance() {
        when (stage) {
            STAGE_STEP -> step()
            STAGE_ID -> idFound()
            STAGE_READ -> readByte()
            STAGE_WRITE -> writeByte()
            STAGE_SECTOR_END -> sectorDone()
            STAGE_ID_BYTE -> idByte()
            STAGE_FORMAT_ID -> formatByte()
            STAGE_FORMAT_GAP -> formatSector()
        }
    }

    private fun idFound() {
        val found = sector!!
        if (operation == OP_READ_ID) {
            byteIndex = 0
            return idByte()
        }
        val matches = found.track == targetTrack && found.number == sectorNumber && found.sizeCode == sizeCode
        if (!matches) {
            // A sector from another track means the seek went wrong.
            if (found.track != targetTrack) return finish(ERR_NF)
            return searchId()
        }
        byteIndex = 0
        val size = sectorSize(sizeCode)
        // The first data byte is complete DATA_OFFSET - ID_BYTES + 1 bytes after the ID's CRC.
        val first = (Disc.DATA_OFFSET - ID_BYTES + 1) * TICKS_PER_BYTE
        when (operation) {
            OP_READ -> schedule(STAGE_READ, first)
            OP_VERIFY -> schedule(STAGE_SECTOR_END, first + (size + 1) * TICKS_PER_BYTE)
            OP_WRITE -> {
                buffer = ByteArray(size)
                drq = true
                schedule(STAGE_WRITE, first)
            }
        }
    }

    private fun readByte() {
        if (drq) return finish(ERR_LATE_DMA)
        val bytes = sector!!.data
        data = if (byteIndex < bytes.size) bytes[byteIndex].toInt() and 0xFF else 0
        drq = true
        if (++byteIndex < sectorSize(sizeCode)) schedule(STAGE_READ, TICKS_PER_BYTE) else schedule(STAGE_SECTOR_END, CRC_BYTES * TICKS_PER_BYTE)
    }

    private fun writeByte() {
        if (drq) return finish(ERR_LATE_DMA)
        buffer[byteIndex] = data.toByte()
        if (++byteIndex < buffer.size) {
            drq = true
            schedule(STAGE_WRITE, TICKS_PER_BYTE)
            return
        }
        val bytes = sector!!.data
        buffer.copyInto(bytes, 0, 0, minOf(buffer.size, bytes.size))
        drive.disc!!.changed()
        schedule(STAGE_SECTOR_END, CRC_BYTES * TICKS_PER_BYTE)
    }

    private fun sectorDone() {
        count = (count - 1) and 0x1F
        if (count == 0) return finish(ERR_NONE)
        sectorNumber++
        indexesSeen = 0
        searchId()
    }

    /** Read ID passes on the four bytes of each ID field as they are read. */
    private fun idByte() {
        if (byteIndex > 0 && drq) return finish(ERR_LATE_DMA)
        if (byteIndex == 4) {
            if (--count == 0) return finish(ERR_NONE)
            indexesSeen = 0
            return searchId()
        }
        val found = sector!!
        data = when (byteIndex) {
            0 -> found.track
            1 -> found.head
            2 -> found.number
            else -> found.sizeCode
        }
        drq = true
        byteIndex++
        schedule(STAGE_ID_BYTE, TICKS_PER_BYTE)
    }

    private fun startFormat() {
        formatted.clear()
        val gap5 = command[4]
        val gap1 = command[5]
        // With gap 5, an index mark and its sync come first.
        formatCursor = (if (gap5 != 0) gap5 + 7 else 0) + gap1
        if (count == 0) return endFormat()
        byteIndex = 0
        drq = true
        schedule(STAGE_FORMAT_ID, (formatCursor + Disc.ID_MARK + 1) * TICKS_PER_BYTE)
    }

    /** Takes one of the four ID bytes the host supplies for each sector. */
    private fun formatByte() {
        if (drq) return finish(ERR_LATE_DMA)
        formatId[byteIndex++] = data
        if (byteIndex < 4) {
            drq = true
            return schedule(STAGE_FORMAT_ID, TICKS_PER_BYTE)
        }
        val size = sectorSize(sizeCode)
        val position = (formatCursor + Disc.ID_MARK) % Disc.BYTES_PER_TRACK
        formatted += Disc.Sector(formatId[0], formatId[1], formatId[2], formatId[3], position, ByteArray(size) { Disc.FORMAT_FILL })
        val length = Disc.sectorLength(size, command[2])
        formatCursor += length
        schedule(STAGE_FORMAT_GAP, (length - Disc.ID_MARK - 4) * TICKS_PER_BYTE)
    }

    private fun formatSector() {
        if (formatted.size == count) return endFormat()
        byteIndex = 0
        drq = true
        schedule(STAGE_FORMAT_ID, (Disc.ID_MARK + 1) * TICKS_PER_BYTE)
    }

    /** The rest of the track is gap; the command ends at the index hole. */
    private fun endFormat() {
        drive.disc!!.format(side, drive.headTrack, formatted.toList())
        stage = STAGE_FORMAT_END
        wait = 0
    }

    private fun finish(code: Int) {
        result = code
        operation = OP_NONE
        stage = STAGE_NONE
        wait = 0
        drq = false
        idleIndexes = 0
        phase = PHASE_RESULT
        irq = true
    }

    private fun ready(d: Int) = headLoaded && drives[d].disc != null

    private fun driveStatus(): Int {
        val disc = drive.disc
        val ready = ready(selectedDrive)
        var value = if (ready) 0x44 else 0
        if (disc != null && revolution < INDEX_PULSE_TICKS) value = value or 0x10
        if (disc?.writeProtected == true) value = value or 0x08
        if (drive.headTrack == 0) value = value or 0x02
        return value
    }

    private fun readSpecialRegister(register: Int): Int = when (register) {
        0x06 -> scanSector
        0x10, 0x11 -> badTracks[0][register and 1]
        0x12 -> pcn[0]
        0x13 -> scanCount[0]
        0x14 -> scanCount[1]
        0x17 -> mode
        0x18, 0x19 -> badTracks[1][register and 1]
        0x1A -> pcn[1]
        0x22 -> driveStatus()
        0x23 -> (command[0] and 0xC0) or outputPort
        else -> 0
    }

    private fun writeSpecialRegister(register: Int, value: Int) {
        when (register) {
            0x06 -> scanSector = value
            0x10, 0x11 -> badTracks[0][register and 1] = value
            0x12 -> pcn[0] = value
            0x13 -> scanCount[0] = value
            0x14 -> scanCount[1] = value
            0x17 -> mode = value or 0xC0
            0x18, 0x19 -> badTracks[1][register and 1] = value
            0x1A -> pcn[1] = value
            0x23 -> {
                outputPort = value and 0x3F
                headLoaded = value and 0x08 != 0
                idleIndexes = 0
            }
        }
    }

    companion object {
        const val TICKS_PER_BYTE = 128 // 64 µs: FM at 125 kbit/s
        const val TICKS_PER_REVOLUTION = Disc.BYTES_PER_TRACK * TICKS_PER_BYTE // 0.2 s: 300 rpm

        const val SR_BSY = 0x80
        const val SR_CF = 0x40
        const val SR_PF = 0x20
        const val SR_RF = 0x10
        const val SR_IRQ = 0x08
        const val SR_DRQ = 0x04

        const val ERR_NONE = 0x00
        const val ERR_LATE_DMA = 0x0A
        const val ERR_NR = 0x10
        const val ERR_WP = 0x12
        const val ERR_T0NF = 0x14
        const val ERR_NF = 0x18

        private const val ID_BYTES = 6 // four ID bytes and the CRC
        private const val CRC_BYTES = 2
        
        private const val MAX_HEAD_TRACK = 83
        private const val STEP_UNIT_TICKS = 4000 // 2 ms, as in MAME
        private const val MIN_STEP_TICKS = 5
        private const val INDEX_PULSE_TICKS = 8000 // 4 ms

        private const val PHASE_IDLE = 0
        private const val PHASE_CMD = 1
        private const val PHASE_EXEC = 2
        private const val PHASE_RESULT = 3

        private const val OP_NONE = 0
        private const val OP_SEEK = 1
        private const val OP_RECALIBRATE = 2
        private const val OP_READ = 3
        private const val OP_VERIFY = 4
        private const val OP_WRITE = 5
        private const val OP_READ_ID = 6
        private const val OP_FORMAT = 7

        private const val STAGE_NONE = 0
        private const val STAGE_STEP = 1
        private const val STAGE_SEARCH = 2
        private const val STAGE_ID = 3
        private const val STAGE_READ = 4
        private const val STAGE_WRITE = 5
        private const val STAGE_SECTOR_END = 6
        private const val STAGE_ID_BYTE = 7
        private const val STAGE_WAIT_INDEX = 8
        private const val STAGE_FORMAT_ID = 9
        private const val STAGE_FORMAT_GAP = 10
        private const val STAGE_FORMAT_END = 11

        /** Parameters each command takes, or null for an invalid command. */
        private fun parameterCount(code: Int): Int? = when (code) {
            0x00, 0x04 -> 5
            0x0A, 0x0E, 0x12, 0x16, 0x1E -> 2
            0x0B, 0x0F, 0x13, 0x17, 0x1F, 0x1B -> 3
            0x23 -> 5
            0x29 -> 1
            0x2C -> 0
            0x35 -> 4
            0x3A -> 2
            0x3D -> 1
            else -> null
        }

        private fun sectorSize(code: Int) = if (code > 7) 16384 else 128 shl code
    }
}
