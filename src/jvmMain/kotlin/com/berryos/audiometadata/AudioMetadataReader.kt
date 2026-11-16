package com.berryos.audiometadata

import com.berryos.audiometadata.flac.FlacMetadataReader
import com.berryos.audiometadata.io.RandomAccessSource
import com.berryos.audiometadata.mp3.Mp3MetadataReader
import com.berryos.audiometadata.mp4.Mp4MetadataReader
import com.berryos.audiometadata.opus.OpusMetadataReader
import com.berryos.audiometadata.vorbis.OggVorbisMetadataReader

/** Reads metadata from any supported format by sniffing the container signature. */
object AudioMetadataReader {
    fun read(source: RandomAccessSource): AudioMetadata? = when (detectAudioFormat(source)) {
        DetectedFormat.Mp3 -> Mp3MetadataReader.read(source)?.toAudioMetadata()
        DetectedFormat.Flac -> FlacMetadataReader.read(source)?.toAudioMetadata()
        DetectedFormat.Ogg -> OpusMetadataReader.read(source)?.toAudioMetadata()
            ?: OggVorbisMetadataReader.read(source)?.toAudioMetadata()

        DetectedFormat.Mp4 -> Mp4MetadataReader.read(source)?.toAudioMetadata()
        // Files without an ID3v2 header may still carry an ID3v1 trailer.
        DetectedFormat.Unknown -> Mp3MetadataReader.read(source)?.toAudioMetadata()
    }
}

/** Reads embedded artwork from any supported format. */
object AudioArtworkReader {
    fun read(source: RandomAccessSource): List<Artwork> = when (detectAudioFormat(source)) {
        DetectedFormat.Mp3 -> Mp3MetadataReader.read(source)?.tag?.artworks.orEmpty()
        DetectedFormat.Flac -> FlacMetadataReader.read(source)?.artworks.orEmpty()
        DetectedFormat.Ogg -> OpusMetadataReader.read(source)?.artworks.orEmpty().ifEmpty {
            OggVorbisMetadataReader.read(source)?.artworks.orEmpty()
        }

        DetectedFormat.Mp4 -> Mp4MetadataReader.read(source)?.artworks.orEmpty()
        DetectedFormat.Unknown -> Mp3MetadataReader.read(source)?.tag?.artworks.orEmpty()
    }
}
