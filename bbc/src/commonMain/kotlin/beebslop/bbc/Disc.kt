// SPDX-FileCopyrightText: 2026 The Beebslop contributors
// SPDX-License-Identifier: BSD-3-Clause

package beebslop.bbc

/**
 * A floppy disc as the 8271 sees it: on each side, tracks of sectors, each with
 * an ID field (track, head, sector number and size code) and its data, at a
 * position on the track. This is the level the controller works at, so a disc
 * formatted with unusual IDs or sizes behaves as it would on a real drive. Flux
 * and CRCs are not modelled: every sector reads back without error.
 *
 * Disc images are sector dumps. An SSD holds one side, track after track; a DSD
 * holds both, alternating a track of side 0 with the same track of side 1. Each
 * track is 10 sectors of 256 bytes, numbered 0-9, as Acorn DFS formats it. A
 * short image is padded with zeros up to the size its catalogue gives, and the
 * rest of the 80 tracks are left unformatted. [toImage] writes the standard
 * sectors back, so a disc formatted another way can't be saved as an image.
 */
class Disc private constructor(val sides: Int, private val tracks: Array<Array<Track>>) {
    /** Set by the user; the drive's sensor reports it and writes fail. */
    var writeProtected: Boolean = false

    /** Counts writes, so the host can tell when to save the image. */
    var changes: Long = 0
        private set

    fun track(side: Int, track: Int): Track? = tracks.getOrNull(side)?.getOrNull(track)

    /** The first sector with this ID on a track, for tests and tools. */
    fun sector(side: Int, track: Int, sector: Int): Sector? =
        track(side, track)?.sectors?.firstOrNull { it.track == track && it.number == sector }

    internal fun changed() { changes++ }

    /** Replaces a track's sectors, as Format Track does. */
    internal fun format(side: Int, track: Int, sectors: List<Sector>) {
        tracks[side][track] = Track(sectors.sortedBy { it.position })
        changed()
    }

    /**
     * The standard sectors as an SSD or DSD image, up to the last track that has
     * any. Missing sectors read as zeros.
     */
    fun toImage(): ByteArray {
        var used = 0
        for (side in 0 until sides) for (t in 0 until TRACKS) if (tracks[side][t].sectors.isNotEmpty()) used = maxOf(used, t + 1)
        val out = ByteArray(used * sides * TRACK_BYTES)
        for (t in 0 until used) for (side in 0 until sides) for (s in 0 until SECTORS) {
            val data = sector(side, t, s)?.data ?: continue
            data.copyInto(out, ((t * sides + side) * SECTORS + s) * SECTOR_SIZE, 0, minOf(data.size, SECTOR_SIZE))
        }
        return out
    }

    /** One track: its sectors in the order they pass the head. */
    class Track(val sectors: List<Sector>)

    /**
     * A sector. [position] is where its ID field starts, in bytes from the index
     * hole; its data follows [DATA_OFFSET] bytes later.
     */
    class Sector(val track: Int, val head: Int, val number: Int, val sizeCode: Int, val position: Int, val data: ByteArray)

    companion object {
        const val TRACKS = 80
        const val SECTORS = 10
        const val SECTOR_SIZE = 256
        const val TRACK_BYTES = SECTORS * SECTOR_SIZE

        /** What Format Track writes in each sector's data. */
        const val FORMAT_FILL: Byte = 0xE5.toByte()

        /** FM bytes on a track: 125 kbit/s at 300 rpm. */
        const val BYTES_PER_TRACK = 3125

        /** From the start of an ID field to the first data byte: ID, CRC, gap 2, sync and data mark. */
        const val DATA_OFFSET = 4 + 2 + 11 + 6 + 1

        /** The 8271's gaps when Acorn DFS formats a disc: gap 1 before sector 0, gap 3 after each sector. */
        private const val GAP1 = 16
        private const val GAP3 = 21

        /** Sync bytes and the address mark before an ID field. */
        internal const val ID_MARK = 6 + 1

        /** The bytes from one sector's ID field to the next's: data, CRC, gap 3 and the next mark. */
        internal fun sectorLength(size: Int, gap3: Int) = DATA_OFFSET + size + 2 + gap3 + ID_MARK

        /**
         * A freshly formatted disc, as FORM40 or FORM80 leaves it: an empty
         * catalogue on each side, and every other byte the format filler &E5.
         */
        fun blank(sides: Int, tracks: Int = TRACKS, title: String = ""): Disc {
            require(sides in 1..2 && tracks in 1..TRACKS)
            val image = ByteArray(tracks * sides * TRACK_BYTES) { FORMAT_FILL }
            for (side in 0 until sides) {
                val catalogue = side * TRACK_BYTES
                image.fill(0, catalogue, catalogue + 2 * SECTOR_SIZE)
                val name = title.take(12).padEnd(12, '\u0000').map { it.code.toByte() }
                for (i in 0 until 8) image[catalogue + i] = name[i]
                for (i in 0 until 4) image[catalogue + SECTOR_SIZE + i] = name[8 + i]
                val sectors = tracks * SECTORS
                image[catalogue + SECTOR_SIZE + 6] = (sectors shr 8).toByte()
                image[catalogue + SECTOR_SIZE + 7] = sectors.toByte()
            }
            return parse(image, sides)
        }

        /**
         * Reads an SSD ([sides] 1) or DSD ([sides] 2) image.
         * @throws IllegalArgumentException if it is too big or has no catalogue.
         */
        fun parse(image: ByteArray, sides: Int): Disc {
            require(sides in 1..2)
            require(image.size <= TRACKS * sides * TRACK_BYTES) { "Disc image is larger than an 80-track disc" }
            require(image.size >= 2 * SECTOR_SIZE) { "Disc image is too short to hold a catalogue" }
            fun byte(i: Int) = if (i < image.size) image[i] else 0
            // Each side's catalogue gives its size in sectors; trust whichever is larger.
            var tracksUsed = (image.size + sides * TRACK_BYTES - 1) / (sides * TRACK_BYTES)
            for (side in 0 until sides) {
                val sector1 = side * TRACK_BYTES + SECTOR_SIZE
                val count = ((byte(sector1 + 6).toInt() and 3) shl 8) or (byte(sector1 + 7).toInt() and 0xFF)
                tracksUsed = maxOf(tracksUsed, minOf(TRACKS, (count + SECTORS - 1) / SECTORS))
            }
            val tracks = Array(sides) { side ->
                Array(TRACKS) { t ->
                    if (t >= tracksUsed) return@Array Track(emptyList())
                    Track((0 until SECTORS).map { s ->
                        val start = ((t * sides + side) * SECTORS + s) * SECTOR_SIZE
                        val data = ByteArray(SECTOR_SIZE) { byte(start + it) }
                        Sector(t, 0, s, 1, GAP1 + ID_MARK + s * sectorLength(SECTOR_SIZE, GAP3), data)
                    })
                }
            }
            return Disc(sides, tracks)
        }
    }
}
