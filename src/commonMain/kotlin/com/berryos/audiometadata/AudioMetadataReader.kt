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
public object AudioMetadataReader {
    public fun read(source: RandomAccessSource): AudioMetadata? {
        val format = AudioFormatDetector.detect(source)
        val metadata = when (format) {
            AudioFormat.MP3 -> Mp3MetadataReader.read(source)?.toAudioMetadata()
            AudioFormat.FLAC -> FlacMetadataReader.read(source)?.toAudioMetadata()
            AudioFormat.OGG -> OpusMetadataReader.read(source)?.toAudioMetadata()
                ?: OggVorbisMetadataReader.read(source)?.toAudioMetadata()

            AudioFormat.OPUS -> OpusMetadataReader.read(source)?.toAudioMetadata()
            AudioFormat.MP4 -> Mp4MetadataReader.read(source)?.toAudioMetadata()
            AudioFormat.WAV -> WavMetadataReader.read(source)?.toAudioMetadata()
            AudioFormat.AIFF -> AiffMetadataReader.read(source)?.toAudioMetadata()
            // Files without an ID3v2 header may still carry an ID3v1 trailer.
            AudioFormat.UNKNOWN -> Mp3MetadataReader.read(source)?.toAudioMetadata()
        }
        if (metadata == null && format != AudioFormat.UNKNOWN) {
            throw AudioMetadataException("Detected ${format.name} data but the metadata could not be read")
        }
        return metadata
    }
}

/** Reads embedded artwork from any supported format. */
public object AudioArtworkReader {
    public fun read(source: RandomAccessSource): List<Artwork> {
        val format = AudioFormatDetector.detect(source)
        val artworks: List<Artwork>? = when (format) {
            AudioFormat.MP3 -> Mp3MetadataReader.read(source)?.tag?.artworks
            AudioFormat.FLAC -> FlacMetadataReader.read(source)?.artworks
            AudioFormat.OGG -> OpusMetadataReader.read(source)?.artworks
                ?: OggVorbisMetadataReader.read(source)?.artworks

            AudioFormat.OPUS -> OpusMetadataReader.read(source)?.artworks
            AudioFormat.MP4 -> Mp4MetadataReader.read(source)?.artworks
            AudioFormat.WAV -> WavMetadataReader.read(source)?.artworks
            AudioFormat.AIFF -> AiffMetadataReader.read(source)?.artworks
            AudioFormat.UNKNOWN -> Mp3MetadataReader.read(source)?.tag?.artworks
        }
        if (artworks == null && format != AudioFormat.UNKNOWN) {
            throw AudioMetadataException("Detected ${format.name} data but the artwork could not be read")
        }
        return artworks.orEmpty()
    }
}
