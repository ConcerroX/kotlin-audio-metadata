package com.berryos.audiometadata.io

import com.berryos.audiometadata.AudioMetadataException

/**
 * Moves everything from [from] to [to], extending or shortening the source as needed.
 *
 * Copies run backwards when growing and forwards when shrinking so overlapping ranges stay safe.
 */
internal fun WritableRandomAccessSource.moveTail(
    from: Long,
    to: Long,
    chunkSize: Int = DEFAULT_MOVE_CHUNK_SIZE,
) {
    val delta = to - from
    if (delta == 0L) return
    if (delta > 0) {
        var end = size
        val buffer = ByteArray(chunkSize)
        while (end > from) {
            val count = minOf(chunkSize.toLong(), end - from).toInt()
            val start = end - count
            readFully(start, buffer, count)
            writeAt(start + delta, buffer, 0, count)
            end = start
        }
    } else {
        var position = from
        val buffer = ByteArray(chunkSize)
        val originalSize = size
        while (position < originalSize) {
            val count = minOf(chunkSize.toLong(), originalSize - position).toInt()
            readFully(position, buffer, count)
            writeAt(position + delta, buffer, 0, count)
            position += count
        }
        truncate(originalSize + delta)
    }
}

internal fun RandomAccessSource.readFully(offset: Long, buffer: ByteArray, count: Int) {
    var position = 0
    while (position < count) {
        val read = readAt(offset + position, buffer, position, count - position)
        if (read <= 0) throw AudioMetadataException("Unexpected end of data at ${offset + position}")
        position += read
    }
}

private const val DEFAULT_MOVE_CHUNK_SIZE = 1 shl 20
