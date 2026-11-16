package com.berryos.audiometadata.mp3

import com.berryos.audiometadata.io.WritableRandomAccessSource
import com.berryos.audiometadata.io.moveTail
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
        source.moveTail(metadata.audioStartOffset, bytes.size.toLong(), chunkSize)
        source.writeAt(0, bytes)
        source.flush()
    }
}

private const val DEFAULT_CHUNK_SIZE = 1 shl 20
