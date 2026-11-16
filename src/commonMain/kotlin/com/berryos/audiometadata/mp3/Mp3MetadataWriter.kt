package com.berryos.audiometadata.mp3

import com.berryos.audiometadata.id3.Id3v2Writer
import com.berryos.audiometadata.io.WritableRandomAccessSource

/**
 * Writes the ID3v2 tag back to the source.
 *
 * Padding from the previous tag is reused when possible; otherwise the audio data is shifted. The
 * caller owns the source and therefore any atomicity or backup strategy around it.
 */
internal object Mp3MetadataWriter {
    fun write(source: WritableRandomAccessSource, metadata: Mp3Metadata) {
        val tag = metadata.tag ?: return
        Id3v2Writer.write(source, tag, metadata.audioStartOffset, tag.declaredSize)
    }
}
