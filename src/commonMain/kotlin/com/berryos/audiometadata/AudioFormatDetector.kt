package com.berryos.audiometadata

import com.berryos.audiometadata.io.RandomAccessSource

/** Detects the container format from the leading bytes of [source]. */
object AudioFormatDetector {
    fun detect(source: RandomAccessSource): AudioFormat {
        val magic = ByteArray(MAGIC_SIZE)
        val count = source.readAt(0, magic)
        return when {
            count >= 3 && magic.hasTypeAt(0, "ID3") -> AudioFormat.Mp3
            count >= 4 && magic.hasTypeAt(0, "fLaC") -> AudioFormat.Flac
            count >= 4 && magic.hasTypeAt(0, "OggS") -> AudioFormat.Ogg
            count >= 12 && magic.hasTypeAt(0, "RIFF") && magic.hasTypeAt(8, "WAVE") -> AudioFormat.Wav
            count >= 12 && magic.hasTypeAt(0, "FORM") &&
                (magic.hasTypeAt(8, "AIFF") || magic.hasTypeAt(8, "AIFC")) -> AudioFormat.Aiff

            count >= 8 && (magic.hasTypeAt(4, "ftyp") || magic.hasTypeAt(0, "moov")) -> AudioFormat.Mp4
            else -> AudioFormat.Unknown
        }
    }
}

private fun ByteArray.hasTypeAt(offset: Int, type: String): Boolean {
    if (size < offset + type.length) return false
    for (index in type.indices) {
        if (this[offset + index] != type[index].code.toByte()) return false
    }
    return true
}

private const val MAGIC_SIZE = 12
