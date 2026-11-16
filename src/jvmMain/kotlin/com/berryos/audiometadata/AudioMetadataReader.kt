package com.berryos.audiometadata

import com.berryos.audiometadata.aiff.AiffMetadataReader
import com.berryos.audiometadata.flac.FlacMetadataReader
import com.berryos.audiometadata.io.RandomAccessSource
import com.berryos.audiometadata.mp3.Mp3MetadataReader
import com.berryos.audiometadata.mp4.Mp4MetadataReader
import com.berryos.audiometadata.opus.OpusMetadataReader
import com.berryos.audiometadata.vorbis.OggVorbisMetadataReader
import com.berryos.audiometadata.wav.WavMetadataReader

/**
 * Reads metadata from any supported format by sniffing the container signature.
 *
 * Returns null for unrecognised containers and throws [AudioMetadataException] when a recognised
 * container is malformed.
 */
object AudioMetadataReader {
    fun read(source: RandomAccessSource): AudioMetadata? {
        val format = AudioFormatDetector.detect(source)
        val metadata = when (format) {
            AudioFormat.Mp3 -> Mp3MetadataReader.read(source)?.toAudioMetadata()
            AudioFormat.Flac -> FlacMetadataReader.read(source)?.toAudioMetadata()
            AudioFormat.Ogg -> OpusMetadataReader.read(source)?.toAudioMetadata()
                ?: OggVorbisMetadataReader.read(source)?.toAudioMetadata()

            AudioFormat.Opus -> OpusMetadataReader.read(source)?.toAudioMetadata()
            AudioFormat.Mp4 -> Mp4MetadataReader.read(source)?.toAudioMetadata()
            AudioFormat.Wav -> WavMetadataReader.read(source)?.toAudioMetadata()
            AudioFormat.Aiff -> AiffMetadataReader.read(source)?.toAudioMetadata()
            // Files without an ID3v2 header may still carry an ID3v1 trailer.
            AudioFormat.Unknown -> Mp3MetadataReader.read(source)?.toAudioMetadata()
        }
        if (metadata == null && format != AudioFormat.Unknown) {
            throw AudioMetadataException("Detected ${format.name} data but the metadata could not be read")
        }
        return metadata
    }
}

/** Reads embedded artwork from any supported format. */
object AudioArtworkReader {
    fun read(source: RandomAccessSource): List<Artwork> {
        val format = AudioFormatDetector.detect(source)
        val artworks: List<Artwork>? = when (format) {
            AudioFormat.Mp3 -> Mp3MetadataReader.read(source)?.tag?.artworks
            AudioFormat.Flac -> FlacMetadataReader.read(source)?.artworks
            AudioFormat.Ogg -> OpusMetadataReader.read(source)?.artworks
                ?: OggVorbisMetadataReader.read(source)?.artworks

            AudioFormat.Opus -> OpusMetadataReader.read(source)?.artworks
            AudioFormat.Mp4 -> Mp4MetadataReader.read(source)?.artworks
            AudioFormat.Wav -> WavMetadataReader.read(source)?.artworks
            AudioFormat.Aiff -> AiffMetadataReader.read(source)?.artworks
            AudioFormat.Unknown -> Mp3MetadataReader.read(source)?.tag?.artworks
        }
        if (artworks == null && format != AudioFormat.Unknown) {
            throw AudioMetadataException("Detected ${format.name} data but the artwork could not be read")
        }
        return artworks.orEmpty()
    }
}
