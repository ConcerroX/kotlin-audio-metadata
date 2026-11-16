package com.berryos.audiometadata

import com.berryos.audiometadata.io.RandomAccessSource

internal enum class DetectedFormat { Mp3, Flac, Ogg, Mp4, Unknown }

internal fun detectAudioFormat(source: RandomAccessSource): DetectedFormat {
    val magic = ByteArray(8)
    val count = source.readAt(0, magic)
    return when {
        count >= 3 && magic.hasTypeAt(0, "ID3") -> DetectedFormat.Mp3
        count >= 4 && magic.hasTypeAt(0, "fLaC") -> DetectedFormat.Flac
        count >= 4 && magic.hasTypeAt(0, "OggS") -> DetectedFormat.Ogg
        count >= 8 && (magic.hasTypeAt(4, "ftyp") || magic.hasTypeAt(0, "moov")) -> DetectedFormat.Mp4
        else -> DetectedFormat.Unknown
    }
}

internal fun ByteArray.hasTypeAt(offset: Int, type: String): Boolean {
    if (size < offset + type.length) return false
    for (index in type.indices) {
        if (this[offset + index] != type[index].code.toByte()) return false
    }
    return true
}
