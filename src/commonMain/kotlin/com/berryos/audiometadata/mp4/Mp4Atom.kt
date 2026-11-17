package com.berryos.audiometadata.mp4

import com.berryos.audiometadata.asciiAt
import com.berryos.audiometadata.io.RandomAccessSource
import com.berryos.audiometadata.io.readFully

/** One ISO base media file format atom, addressed inside a random-access source. */
internal class Mp4Atom(
    val type: String,
    val offset: Long,
    val payloadStart: Long,
    val payloadSize: Long,
) {
    val size: Long = (payloadStart - offset) + payloadSize
}

internal fun topLevelAtoms(source: RandomAccessSource): List<Mp4Atom>? {
    val atoms = ArrayList<Mp4Atom>()
    var offset = 0L
    while (offset + ATOM_HEADER_SIZE <= source.size) {
        val atom = readAtom(source, offset, source.size) ?: return atoms.ifEmpty { null }
        atoms += atom
        offset += atom.size
    }
    return atoms.ifEmpty { null }
}

internal fun atomsIn(source: RandomAccessSource, parent: Mp4Atom, skip: Int = 0): List<Mp4Atom> {
    val atoms = ArrayList<Mp4Atom>()
    var offset = parent.payloadStart + skip
    val end = parent.payloadStart + parent.payloadSize
    while (offset + ATOM_HEADER_SIZE <= end) {
        val atom = readAtom(source, offset, end) ?: break
        atoms += atom
        offset += atom.size
    }
    return atoms
}

internal fun readAtom(source: RandomAccessSource, offset: Long, limit: Long): Mp4Atom? {
    if (offset + ATOM_HEADER_SIZE > limit) return null
    val header = ByteArray(ATOM_HEADER_SIZE)
    source.readAt(offset, header)
    var size = header.readBigEndianInt(0).toLong() and 0xFFFFFFFFL
    val type = header.asciiAt(4, ATOM_TYPE_SIZE)
    var headerSize = ATOM_HEADER_SIZE
    if (size == 1L) {
        if (offset + LARGE_ATOM_HEADER_SIZE > limit) return null
        val large = ByteArray(8)
        source.readAt(offset + ATOM_HEADER_SIZE, large)
        size = large.readBigEndianLong(0)
        headerSize = LARGE_ATOM_HEADER_SIZE
    } else if (size == 0L) {
        size = limit - offset
    }
    if (size < headerSize || offset + size > limit) return null
    return Mp4Atom(type, offset, offset + headerSize.toLong(), size - headerSize)
}

internal fun readBytes(source: RandomAccessSource, offset: Long, length: Int): ByteArray {
    if (length <= 0) return ByteArray(0)
    val bytes = ByteArray(length)
    source.readFully(offset, bytes, length)
    return bytes
}

internal fun fullBoxText(source: RandomAccessSource, atom: Mp4Atom): String? {
    if (atom.payloadSize <= FULL_BOX_HEADER) return null
    val length = (atom.payloadSize - FULL_BOX_HEADER).toInt()
    return readBytes(source, atom.payloadStart + FULL_BOX_HEADER, length).decodeToString()
}

/** Sample offsets referenced by stco/co64; used to validate writes. */
internal fun readChunkOffsets(source: RandomAccessSource): List<Long> {
    val moov = topLevelAtoms(source)?.firstOrNull { it.type == "moov" } ?: return emptyList()
    val offsets = ArrayList<Long>()
    collectChunkOffsets(source, moov, offsets)
    return offsets
}

private fun collectChunkOffsets(source: RandomAccessSource, parent: Mp4Atom, offsets: MutableList<Long>) {
    atomsIn(source, parent).forEach { child ->
        when (child.type) {
            "stco" -> {
                val data = readBytes(source, child.payloadStart, child.payloadSize.toInt())
                if (data.size >= 8) {
                    var position = 8
                    repeat(data.readBigEndianInt(4)) {
                        if (position + 4 <= data.size) {
                            offsets += data.readBigEndianInt(position).toLong() and 0xFFFFFFFFL
                            position += 4
                        }
                    }
                }
            }

            "co64" -> {
                val data = readBytes(source, child.payloadStart, child.payloadSize.toInt())
                if (data.size >= 8) {
                    var position = 8
                    repeat(data.readBigEndianInt(4)) {
                        if (position + 8 <= data.size) {
                            offsets += data.readBigEndianLong(position)
                            position += 8
                        }
                    }
                }
            }

            in CONTAINER_TYPES -> collectChunkOffsets(source, child, offsets)
        }
    }
}

internal fun buildAtom(type: String, payload: ByteArray): ByteArray {
    val atom = ByteArray(ATOM_HEADER_SIZE + payload.size)
    atom.writeBigEndianInt(0, atom.size)
    type.forEachIndexed { index, char -> atom[ATOM_TYPE_OFFSET + index] = char.code.toByte() }
    payload.copyInto(atom, ATOM_HEADER_SIZE)
    return atom
}

internal fun concat(chunks: List<ByteArray>): ByteArray {
    val total = chunks.sumOf { it.size }
    val result = ByteArray(total)
    var position = 0
    chunks.forEach { chunk ->
        chunk.copyInto(result, position)
        position += chunk.size
    }
    return result
}

internal fun bigEndianInt(value: Int): ByteArray = ByteArray(4).also { it.writeBigEndianInt(0, value) }

internal fun bigEndianShort(value: Int): ByteArray = byteArrayOf((value ushr 8).toByte(), value.toByte())

internal fun ByteArray.readBigEndianInt(offset: Int): Int =
    ((this[offset].toInt() and 0xFF) shl 24) or
        ((this[offset + 1].toInt() and 0xFF) shl 16) or
        ((this[offset + 2].toInt() and 0xFF) shl 8) or
        (this[offset + 3].toInt() and 0xFF)

internal fun ByteArray.readBigEndianShort(offset: Int): Int =
    ((this[offset].toInt() and 0xFF) shl 8) or (this[offset + 1].toInt() and 0xFF)

internal fun ByteArray.readBigEndianLong(offset: Int): Long {
    var value = 0L
    for (index in 0 until 8) {
        value = (value shl 8) or (this[offset + index].toLong() and 0xFF)
    }
    return value
}

internal fun ByteArray.writeBigEndianInt(offset: Int, value: Int) {
    this[offset] = (value ushr 24).toByte()
    this[offset + 1] = (value ushr 16).toByte()
    this[offset + 2] = (value ushr 8).toByte()
    this[offset + 3] = value.toByte()
}

internal fun ByteArray.writeBigEndianLong(offset: Int, value: Long) {
    for (index in 0 until 8) {
        this[offset + index] = (value ushr (56 - index * 8)).toByte()
    }
}

internal const val ATOM_HEADER_SIZE = 8
internal const val ATOM_TYPE_OFFSET = 4
internal const val LARGE_ATOM_HEADER_SIZE = 16
internal const val FULL_BOX_HEADER = 4
internal const val DATA_HEADER_SIZE = 8
internal const val ATOM_TYPE_SIZE = 4

internal val CONTAINER_TYPES = setOf("moov", "trak", "mdia", "minf", "stbl")
