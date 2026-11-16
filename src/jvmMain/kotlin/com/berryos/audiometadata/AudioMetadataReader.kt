package com.berryos.audiometadata

import com.berryos.audiometadata.aiff.AiffMetadataReader
import com.berryos.audiometadata.flac.FlacMetadataReader
import com.berryos.audiometadata.io.RandomAccessSource
import com.berryos.audiometadata.mp3.Mp3MetadataReader
import com.berryos.audiometadata.mp4.Mp4MetadataReader
import com.berryos.audiometadata.opus.OpusMetadataReader
import com.berryos.audiometadata.vorbis.OggVorbisMetadataReader
import com.berryos.audiometadata.wav.WavMetadataReader

/** Reads metadata from any supported format by sniffing the container signature. */
object AudioMetadataReader {
    fun read(source: RandomAccessSource): AudioMetadata? = when (AudioFormatDetector.detect(source)) {
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
}

/** Reads embedded artwork from any supported format. */
object AudioArtworkReader {
    fun read(source: RandomAccessSource): List<Artwork> = when (AudioFormatDetector.detect(source)) {
        AudioFormat.Mp3 -> Mp3MetadataReader.read(source)?.tag?.artworks.orEmpty()
        AudioFormat.Flac -> FlacMetadataReader.read(source)?.artworks.orEmpty()
        AudioFormat.Ogg -> OpusMetadataReader.read(source)?.artworks.orEmpty().ifEmpty {
            OggVorbisMetadataReader.read(source)?.artworks.orEmpty()
        }

        AudioFormat.Opus -> OpusMetadataReader.read(source)?.artworks.orEmpty()
        AudioFormat.Mp4 -> Mp4MetadataReader.read(source)?.artworks.orEmpty()
        AudioFormat.Wav -> WavMetadataReader.read(source)?.artworks.orEmpty()
        AudioFormat.Aiff -> AiffMetadataReader.read(source)?.artworks.orEmpty()
        AudioFormat.Unknown -> Mp3MetadataReader.read(source)?.tag?.artworks.orEmpty()
    }
}
