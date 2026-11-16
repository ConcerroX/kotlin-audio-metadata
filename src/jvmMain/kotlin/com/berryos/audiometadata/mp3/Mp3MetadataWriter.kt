package com.berryos.audiometadata.mp3

import com.berryos.audiometadata.io.WritableRandomAccessSource
import java.io.ByteArrayOutputStream

/**
 * Writes the ID3v2 tag back to the source.
 *
 * When the serialized tag fits into the space occupied by the previous tag, it is written in place.
 * Otherwise the audio data is shifted by the size difference first. The caller owns the source and
 * therefore any atomicity or backup strategy around it.
 */
object Mp3MetadataWriter {
    fun write(source: WritableRandomAccessSource, metadata: Mp3Metadata, chunkSize: Int = DEFAULT_CHUNK_SIZE) {
        val tag = metadata.tag ?: return
        val bytes = ByteArrayOutputStream().use { output ->
            tag.tag.write(output, tag.originalTagSize)
            output.toByteArray()
        }
        moveAudio(source, metadata.audioStartOffset, bytes.size.toLong(), chunkSize)
        source.writeAt(0, bytes)
        source.flush()
    }

    private fun moveAudio(
        source: WritableRandomAccessSource,
        oldStart: Long,
        newStart: Long,
        chunkSize: Int,
    ) {
        val delta = newStart - oldStart
        if (delta == 0L) return
        if (delta > 0) {
            moveForwardIntoGrowth(source, oldStart, delta, chunkSize)
        } else {
            moveBackwardIntoShrink(source, oldStart, delta, chunkSize)
        }
    }

    private fun moveForwardIntoGrowth(
        source: WritableRandomAccessSource,
        oldStart: Long,
        delta: Long,
        chunkSize: Int,
    ) {
        val size = source.size
        val buffer = ByteArray(chunkSize)
        var end = size
        while (end > oldStart) {
            val count = minOf(chunkSize.toLong(), end - oldStart).toInt()
            val from = end - count
            readFully(source, from, buffer, count)
            source.writeAt(from + delta, buffer, 0, count)
            end = from
        }
    }

    private fun moveBackwardIntoShrink(
        source: WritableRandomAccessSource,
        oldStart: Long,
        delta: Long,
        chunkSize: Int,
    ) {
        val size = source.size
        val buffer = ByteArray(chunkSize)
        var position = oldStart
        while (position < size) {
            val count = minOf(chunkSize.toLong(), size - position).toInt()
            readFully(source, position, buffer, count)
            source.writeAt(position + delta, buffer, 0, count)
            position += count
        }
        source.truncate(size + delta)
    }

    private fun readFully(source: WritableRandomAccessSource, offset: Long, buffer: ByteArray, count: Int) {
        var position = 0
        while (position < count) {
            val read = source.readAt(offset + position, buffer, position, count - position)
            check(read > 0) { "Unexpected end of data at ${offset + position}" }
            position += read
        }
    }
}

private const val DEFAULT_CHUNK_SIZE = 1 shl 20
