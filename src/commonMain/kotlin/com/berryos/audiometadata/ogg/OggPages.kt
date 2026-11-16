package com.berryos.audiometadata.ogg

import com.berryos.audiometadata.io.RandomAccessSource
import com.berryos.audiometadata.io.WritableRandomAccessSource
import com.berryos.audiometadata.io.readFully

/** Parsed Ogg page header and sizes; payload bytes stay in the source. */
internal class OggPage(
    val offset: Long,
    val headerType: Int,
    val granulePosition: Long,
    val serialNumber: Int,
    val sequenceNumber: Int,
    val segmentTable: IntArray,
) {
    val headerSize: Int = PAGE_HEADER_SIZE + segmentTable.size
    val payloadSize: Int = segmentTable.sum()
    val size: Int = headerSize + payloadSize

    val isContinued: Boolean = headerType and CONTINUED_FLAG != 0
    val isBos: Boolean = headerType and BOS_FLAG != 0
    val isEos: Boolean = headerType and EOS_FLAG != 0
    val payloadOffset: Long = offset + headerSize
}

internal object OggPages {
    fun read(source: RandomAccessSource, offset: Long): OggPage? {
        if (offset < 0 || offset + PAGE_HEADER_SIZE > source.size) return null
        val header = ByteArray(PAGE_HEADER_SIZE)
        if (source.readAt(offset, header) < PAGE_HEADER_SIZE) return null
        if (header[0] != 'O'.code.toByte() || header[1] != 'g'.code.toByte() ||
            header[2] != 'g'.code.toByte() || header[3] != 'S'.code.toByte()
        ) {
            return null
        }
        if (header[4].toInt() != 0) return null
        val headerType = header[5].toInt() and 0xFF
        val granule = header.readLittleEndianLong(6)
        val serial = header.readLittleEndianInt(14)
        val sequence = header.readLittleEndianInt(18)
        val segmentCount = header[26].toInt() and 0xFF
        if (offset + PAGE_HEADER_SIZE + segmentCount > source.size) return null
        val segments = ByteArray(segmentCount)
        if (segmentCount > 0 && source.readAt(offset + PAGE_HEADER_SIZE, segments) < segmentCount) return null
        val table = IntArray(segmentCount) { segments[it].toInt() and 0xFF }
        return OggPage(offset, headerType, granule, serial, sequence, table)
    }

    fun readPayload(source: RandomAccessSource, page: OggPage): ByteArray {
        val payload = ByteArray(page.payloadSize)
        source.readFully(page.payloadOffset, payload, payload.size)
        return payload
    }

    /** Splits a packet into one or more pages, starting at [startSequence]. */
    fun pagePacket(packet: ByteArray, serial: Int, startSequence: Int, granule: Long = 0L): List<ByteArray> {
        val pages = ArrayList<ByteArray>()
        var position = 0
        var sequence = startSequence
        var continued = false
        do {
            val remaining = packet.size - position
            val continuesPacket = remaining > MAX_PAGE_PAYLOAD
            val payloadSize = if (continuesPacket) MAX_PAGE_PAYLOAD else remaining
            val payload = packet.copyOfRange(position, position + payloadSize)
            pages += buildPage(payload, serial, sequence++, continued, granule, endsPacket = !continuesPacket)
            position += payloadSize
            continued = true
        } while (position < packet.size)
        return pages
    }

    /**
     * Rewrites the sequence number and checksum of an existing page, leaving its payload alone.
     */
    fun renumberPage(source: WritableRandomAccessSource, offset: Long, sequence: Int): Int {
        val page = read(source, offset) ?: return -1
        val bytes = ByteArray(page.size)
        source.readFully(offset, bytes, bytes.size)
        bytes.writeLittleEndianInt(18, sequence)
        bytes.writeLittleEndianInt(22, 0)
        val crc = computeCrc(bytes, 0, bytes.size)
        bytes.writeLittleEndianInt(22, crc)
        source.writeAt(offset, bytes)
        return page.size
    }

    fun computeCrc(bytes: ByteArray, from: Int, to: Int): Int {
        var crc = 0
        for (index in from until to) {
            crc = (crc shl 8) xor CRC_TABLE[((crc ushr 24) xor (bytes[index].toInt() and 0xFF)) and 0xFF]
        }
        return crc
    }

    private fun buildPage(
        payload: ByteArray,
        serial: Int,
        sequence: Int,
        continued: Boolean,
        granule: Long,
        endsPacket: Boolean,
    ): ByteArray {
        val segments = ArrayList<Int>()
        var remaining = payload.size
        while (remaining >= 255) {
            segments += 255
            remaining -= 255
        }
        if (endsPacket) segments += remaining

        val header = ByteArray(PAGE_HEADER_SIZE + segments.size)
        header[0] = 'O'.code.toByte()
        header[1] = 'g'.code.toByte()
        header[2] = 'g'.code.toByte()
        header[3] = 'S'.code.toByte()
        header[4] = 0
        header[5] = (if (continued) CONTINUED_FLAG else 0).toByte()
        header.writeLittleEndianLong(6, granule)
        header.writeLittleEndianInt(14, serial)
        header.writeLittleEndianInt(18, sequence)
        header.writeLittleEndianInt(22, 0)
        header[26] = segments.size.toByte()
        segments.forEachIndexed { index, size -> header[PAGE_HEADER_SIZE + index] = size.toByte() }

        val page = header + payload
        val crc = computeCrc(page, 0, page.size)
        page.writeLittleEndianInt(22, crc)
        return page
    }

    private const val MAX_PAGE_PAYLOAD = 255 * 255

    private val CRC_TABLE = IntArray(256) { index ->
        var value = index shl 24
        repeat(8) {
            value = if (value and Int.MIN_VALUE != 0) (value shl 1) xor OGG_CRC_POLY else value shl 1
        }
        value
    }
    private const val OGG_CRC_POLY = 0x04C11DB7
}

private const val PAGE_HEADER_SIZE = 27
private const val CONTINUED_FLAG = 0x01
private const val BOS_FLAG = 0x02
private const val EOS_FLAG = 0x04

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
