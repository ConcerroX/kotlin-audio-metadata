package com.berryos.audiometadata.ogg

import com.berryos.audiometadata.io.RandomAccessSource
import com.berryos.audiometadata.io.WritableRandomAccessSource
import com.berryos.audiometadata.io.readFully

/**
 * One Ogg page as described by RFC 3533 section 6.
 *
 * Only the header and segment table are kept in memory; payload bytes stay in the source.
 */
internal class OggPage(
    val offset: Long,
    val headerType: Int,
    val granulePosition: Long,
    val serialNumber: Int,
    val sequenceNumber: Int,
    val segmentTable: IntArray,
) {
    val headerSize: Int
        get() = HEADER_BYTES + segmentTable.size

    val payloadSize: Int
        get() = segmentTable.sum()

    val size: Int
        get() = headerSize + payloadSize

    val isContinued: Boolean
        get() = headerType and HEADER_CONTINUED != 0

    val isBos: Boolean
        get() = headerType and HEADER_FIRST_PAGE != 0

    val isEos: Boolean
        get() = headerType and HEADER_LAST_PAGE != 0

    val payloadOffset: Long
        get() = offset + headerSize
}

/** Reads, builds and re-checksums Ogg pages (RFC 3533 section 6). */
internal object OggPages {
    fun read(source: RandomAccessSource, offset: Long): OggPage? {
        if (offset < 0 || offset + HEADER_BYTES > source.size) return null
        val header = ByteArray(HEADER_BYTES)
        if (source.readAt(offset, header) < HEADER_BYTES) return null
        if (!header.hasCapturePattern()) return null
        if (header[VERSION_OFFSET].toInt() != 0) return null

        val segmentCount = header[SEGMENT_COUNT_OFFSET].toInt() and 0xFF
        if (offset + HEADER_BYTES + segmentCount > source.size) return null
        val lacing = ByteArray(segmentCount)
        if (segmentCount > 0 && source.readAt(offset + HEADER_BYTES, lacing) < segmentCount) return null
        val payloadSize = lacing.sumOf { value -> value.toInt() and 0xFF }
        if (offset + HEADER_BYTES + segmentCount + payloadSize > source.size) return null

        return OggPage(
            offset = offset,
            headerType = header[HEADER_TYPE_OFFSET].toInt() and 0xFF,
            granulePosition = header.readLittleEndianLong(GRANULE_OFFSET),
            serialNumber = header.readLittleEndianInt(SERIAL_OFFSET),
            sequenceNumber = header.readLittleEndianInt(SEQUENCE_OFFSET),
            segmentTable = IntArray(segmentCount) { index -> lacing[index].toInt() and 0xFF },
        )
    }

    fun readPayload(source: RandomAccessSource, page: OggPage): ByteArray {
        val payload = ByteArray(page.payloadSize)
        source.readFully(page.payloadOffset, payload, payload.size)
        return payload
    }

    /**
     * Splits [packet] into pages using the lacing rules from RFC 3533 section 6.
     *
     * A page holds at most 255 lacing values; a packet that does not end within those values
     * continues on the next page with the "continued" header flag. Pages that do not finish the
     * packet carry the special granule value -1.
     */
    fun pagePacket(packet: ByteArray, serial: Int, startSequence: Int, granule: Long = 0L): List<ByteArray> {
        val pages = ArrayList<ByteArray>()
        var sequence = startSequence
        var consumed = 0
        var continued = false
        while (true) {
            val lacing = ArrayList<Int>(MAX_SEGMENTS_PER_PAGE)
            var payloadSize = 0
            var endsPacket = false
            while (lacing.size < MAX_SEGMENTS_PER_PAGE) {
                val remaining = packet.size - consumed - payloadSize
                if (remaining >= MAX_LACING_VALUE) {
                    lacing += MAX_LACING_VALUE
                    payloadSize += MAX_LACING_VALUE
                    continue
                }
                lacing += remaining
                payloadSize += remaining
                endsPacket = true
                break
            }
            val payload = packet.copyOfRange(consumed, consumed + payloadSize)
            pages += buildPage(
                payload = payload,
                lacing = lacing,
                serial = serial,
                sequence = sequence,
                continued = continued,
                granule = if (endsPacket) granule else NO_PACKET_GRANULE,
            )
            sequence++
            consumed += payloadSize
            continued = true
            if (endsPacket) return pages
        }
    }

    /** Rewrites [OggPage.sequenceNumber] and the checksum, keeping the payload unchanged. */
    fun renumberPage(source: WritableRandomAccessSource, offset: Long, sequence: Int): Int {
        val page = read(source, offset) ?: return -1
        val bytes = ByteArray(page.size)
        source.readFully(offset, bytes, bytes.size)
        bytes.writeLittleEndianInt(SEQUENCE_OFFSET, sequence)
        bytes.writeLittleEndianInt(CRC_OFFSET, 0)
        bytes.writeLittleEndianInt(CRC_OFFSET, computeCrc(bytes, 0, bytes.size))
        source.writeAt(offset, bytes)
        return page.size
    }

    /** CRC of [from] until [to) with the generator polynomial 0x04c11db7 and an initial value of 0. */
    fun computeCrc(bytes: ByteArray, from: Int, to: Int): Int {
        var crc = 0
        for (index in from until to) {
            val high = (crc ushr 24) and 0xFF
            crc = (crc shl 8) xor CRC_LOOKUP[high xor (bytes[index].toInt() and 0xFF)]
        }
        return crc
    }

    private fun buildPage(
        payload: ByteArray,
        lacing: List<Int>,
        serial: Int,
        sequence: Int,
        continued: Boolean,
        granule: Long,
    ): ByteArray {
        val header = ByteArray(HEADER_BYTES + lacing.size)
        header[0] = 'O'.code.toByte()
        header[1] = 'g'.code.toByte()
        header[2] = 'g'.code.toByte()
        header[3] = 'S'.code.toByte()
        header[VERSION_OFFSET] = 0
        header[HEADER_TYPE_OFFSET] = (if (continued) HEADER_CONTINUED else 0).toByte()
        header.writeLittleEndianLong(GRANULE_OFFSET, granule)
        header.writeLittleEndianInt(SERIAL_OFFSET, serial)
        header.writeLittleEndianInt(SEQUENCE_OFFSET, sequence)
        header.writeLittleEndianInt(CRC_OFFSET, 0)
        header[SEGMENT_COUNT_OFFSET] = lacing.size.toByte()
        lacing.forEachIndexed { index, value -> header[HEADER_BYTES + index] = value.toByte() }

        val page = header + payload
        page.writeLittleEndianInt(CRC_OFFSET, computeCrc(page, 0, page.size))
        return page
    }

    private fun ByteArray.hasCapturePattern(): Boolean =
        size >= HEADER_BYTES &&
            this[0] == 'O'.code.toByte() && this[1] == 'g'.code.toByte() &&
            this[2] == 'g'.code.toByte() && this[3] == 'S'.code.toByte()

    private const val VERSION_OFFSET = 4
    private const val HEADER_TYPE_OFFSET = 5
    private const val GRANULE_OFFSET = 6
    private const val SERIAL_OFFSET = 14
    private const val SEQUENCE_OFFSET = 18
    private const val CRC_OFFSET = 22
    private const val SEGMENT_COUNT_OFFSET = 26
    private const val MAX_SEGMENTS_PER_PAGE = 255
    private const val MAX_LACING_VALUE = 255
    private const val NO_PACKET_GRANULE = -1L

    private val CRC_LOOKUP = IntArray(256) { index ->
        var value = index shl 24
        repeat(8) {
            value = if (value and Int.MIN_VALUE != 0) (value shl 1) xor CRC_POLYNOMIAL else value shl 1
        }
        value
    }
    private const val CRC_POLYNOMIAL = 0x04C11DB7
}

private const val HEADER_BYTES = 27
private const val HEADER_CONTINUED = 0x01
private const val HEADER_FIRST_PAGE = 0x02
private const val HEADER_LAST_PAGE = 0x04

internal fun ByteArray.readLittleEndianInt(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or
        ((this[offset + 1].toInt() and 0xFF) shl 8) or
        ((this[offset + 2].toInt() and 0xFF) shl 16) or
        ((this[offset + 3].toInt() and 0xFF) shl 24)

internal fun ByteArray.readLittleEndianLong(offset: Int): Long =
    (this[offset].toLong() and 0xFF) or
        ((this[offset + 1].toLong() and 0xFF) shl 8) or
        ((this[offset + 2].toLong() and 0xFF) shl 16) or
        ((this[offset + 3].toLong() and 0xFF) shl 24) or
        ((this[offset + 4].toLong() and 0xFF) shl 32) or
        ((this[offset + 5].toLong() and 0xFF) shl 40) or
        ((this[offset + 6].toLong() and 0xFF) shl 48) or
        ((this[offset + 7].toLong() and 0xFF) shl 56)

internal fun ByteArray.writeLittleEndianInt(offset: Int, value: Int) {
    this[offset] = value.toByte()
    this[offset + 1] = (value ushr 8).toByte()
    this[offset + 2] = (value ushr 16).toByte()
    this[offset + 3] = (value ushr 24).toByte()
}

private fun ByteArray.writeLittleEndianLong(offset: Int, value: Long) {
    for (index in 0 until 8) {
        this[offset + index] = (value ushr (index * 8)).toByte()
    }
}

internal fun ByteArray.readLittleEndianShort(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)
