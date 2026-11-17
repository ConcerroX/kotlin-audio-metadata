package com.berryos.audiometadata

import com.berryos.audiometadata.io.RandomAccessSource

/**
 * Detects the container format from the leading bytes of the given source.
 *
 * Returns [AudioFormat.UNKNOWN] when no known signature matches. A recognised signature does not
 * guarantee that the rest of the file is valid; readers report that by throwing
 * [AudioMetadataException].
 */
public object AudioFormatDetector {
    public fun detect(source: RandomAccessSource): AudioFormat {
        val magic = ByteArray(MAGIC_SIZE)
        val count = source.readAt(0, magic)
        return when {
            count >= 3 && magic.hasAsciiAt(0, "ID3") -> AudioFormat.MP3
            count >= 4 && magic.hasAsciiAt(0, "fLaC") -> AudioFormat.FLAC
            count >= 4 && magic.hasAsciiAt(0, "OggS") -> AudioFormat.OGG
            count >= 12 && magic.hasAsciiAt(0, "RIFF") && magic.hasAsciiAt(8, "WAVE") -> AudioFormat.WAV
            count >= 12 && magic.hasAsciiAt(0, "FORM") &&
                (magic.hasAsciiAt(8, "AIFF") || magic.hasAsciiAt(8, "AIFC")) -> AudioFormat.AIFF

            count >= 8 && (magic.hasAsciiAt(4, "ftyp") || magic.hasAsciiAt(0, "moov")) -> AudioFormat.MP4
            else -> AudioFormat.UNKNOWN
        }
    }
}

private const val MAGIC_SIZE = 12
