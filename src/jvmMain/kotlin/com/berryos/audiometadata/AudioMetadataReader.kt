package com.berryos.audiometadata

import com.berryos.audiometadata.flac.FlacMetadataReader
import com.berryos.audiometadata.io.RandomAccessSource
import com.berryos.audiometadata.mp3.Mp3MetadataReader
import com.berryos.audiometadata.mp4.Mp4MetadataReader
import com.berryos.audiometadata.opus.OpusMetadataReader
import com.berryos.audiometadata.vorbis.OggVorbisMetadataReader

/** Reads metadata from any supported format by sniffing the container signature. */
object AudioMetadataReader {
    fun read(source: RandomAccessSource): AudioMetadata? {
        val magic = ByteArray(8)
        val count = source.readAt(0, magic)
        return when {
            count >= 3 && magic.startsWith(ASCII_ID3) -> Mp3MetadataReader.read(source)?.toAudioMetadata()
            count >= 4 && magic.startsWith(FLAC_MAGIC) -> FlacMetadataReader.read(source)?.toAudioMetadata()
            count >= 4 && magic.startsWith(OGG_MAGIC) ->
                OpusMetadataReader.read(source)?.toAudioMetadata()
                    ?: OggVorbisMetadataReader.read(source)?.toAudioMetadata()

            count >= 8 && (magic.hasTypeAt(4, "ftyp") || magic.hasTypeAt(0, "moov")) ->
                Mp4MetadataReader.read(source)?.toAudioMetadata()

            // Files without an ID3v2 header may still carry an ID3v1 trailer.
            else -> Mp3MetadataReader.read(source)?.toAudioMetadata()
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

private fun ByteArray.startsWith(aspect: ByteArray): Boolean {
    if (size < aspect.size) return false
    for (index in aspect.indices) {
        if (this[index] != aspect[index]) return false
    }
    return true
}

private val ASCII_ID3 = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte())
private val FLAC_MAGIC = "fLaC".encodeToByteArray()
private val OGG_MAGIC = "OggS".encodeToByteArray()
