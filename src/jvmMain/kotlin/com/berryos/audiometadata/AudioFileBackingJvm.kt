package com.berryos.audiometadata

import com.berryos.audiometadata.aiff.AiffMetadata
import com.berryos.audiometadata.aiff.AiffMetadataReader
import com.berryos.audiometadata.aiff.AiffMetadataWriter
import com.berryos.audiometadata.id3.Id3Tag
import com.berryos.audiometadata.io.RandomAccessSource
import com.berryos.audiometadata.io.WritableRandomAccessSource
import com.berryos.audiometadata.mp3.Mp3Metadata
import com.berryos.audiometadata.mp3.Mp3MetadataReader
import com.berryos.audiometadata.mp3.Mp3MetadataWriter
import com.berryos.audiometadata.wav.WavMetadata
import com.berryos.audiometadata.wav.WavMetadataReader
import com.berryos.audiometadata.wav.WavMetadataWriter
import org.jaudiotagger.tag.FieldKey

/** Maps the shared typed fields onto an ID3 tag. */
internal class Id3TagFields(private val tag: Id3Tag) : TagFields {
    override var title: String?
        get() = tag.title.ifEmpty { null }
        set(value) {
            tag.title = value.orEmpty()
        }

    override var artists: List<String>
        get() = tag.values(FieldKey.ARTIST)
        set(value) = tag.setValues(FieldKey.ARTIST, value)

    override var album: String?
        get() = tag.album.ifEmpty { null }
        set(value) {
            tag.album = value.orEmpty()
        }

    override var albumArtist: String?
        get() = tag.albumArtist.ifEmpty { null }
        set(value) {
            tag.albumArtist = value.orEmpty()
        }

    override var trackNumber: Int?
        get() = tag.trackNumber.toIntOrNull()
        set(value) {
            tag.trackNumber = value?.toString().orEmpty()
        }

    override var trackTotal: Int?
        get() = tag.trackTotal.toIntOrNull()
        set(value) {
            tag.trackTotal = value?.toString().orEmpty()
        }

    override var discNumber: Int?
        get() = tag.discNumber.toIntOrNull()
        set(value) {
            tag.discNumber = value?.toString().orEmpty()
        }

    override var discTotal: Int?
        get() = tag.discTotal.toIntOrNull()
        set(value) {
            tag.discTotal = value?.toString().orEmpty()
        }

    override var year: String?
        get() = tag.year.ifEmpty { null }
        set(value) {
            tag.year = value.orEmpty()
        }

    override var genres: List<String>
        get() = tag.genres()
        set(value) = tag.setValues(FieldKey.GENRE, value)

    override var composers: List<String>
        get() = tag.values(FieldKey.COMPOSER)
        set(value) = tag.setValues(FieldKey.COMPOSER, value)

    override var comments: List<String>
        get() = tag.values(FieldKey.COMMENT)
        set(value) = tag.setValues(FieldKey.COMMENT, value)

    override var replayGain: ReplayGain?
        get() = tag.replayGain
        set(value) {
            tag.replayGain = value
        }
}

/** Shared plumbing for MP3, WAV and AIFF, which all keep their metadata in an ID3 tag. */
internal abstract class Id3Backing(id3Tag: Id3Tag, loadArtworks: Boolean) : AudioFileBacking() {
    var id3Tag: Id3Tag = id3Tag
        protected set

    private var fieldView = Id3TagFields(id3Tag)
    private var loadedArtworks: List<Artwork>? = if (loadArtworks) id3Tag.artworks else null

    override val text: TagFields
        get() = fieldView

    override val id3Version: Id3Version
        get() = id3Tag.version

    override var artworks: List<Artwork>?
        get() = loadedArtworks
        set(value) {
            loadedArtworks = value
            if (value != null) id3Tag.setArtworks(value)
        }

    protected val artworksLoaded: Boolean
        get() = loadedArtworks != null

    protected fun reset(tag: Id3Tag, loadArtworks: Boolean) {
        id3Tag = tag
        fieldView = Id3TagFields(tag)
        loadedArtworks = if (loadArtworks) tag.artworks else null
    }
}

internal class Mp3Backing(private var metadata: Mp3Metadata, loadArtworks: Boolean) :
    Id3Backing(metadata.tag ?: Id3Tag.create(), loadArtworks) {
    override val format: AudioFormat
        get() = AudioFormat.Mp3

    override val audioInfo: AudioInfo?
        get() = metadata.audioInfo

    override fun save(source: WritableRandomAccessSource) {
        val current = Mp3Metadata(id3Tag, metadata.audioStartOffset, metadata.id3v1, metadata.audioInfo)
        Mp3MetadataWriter.write(source, current)
        metadata = Mp3MetadataReader.read(source)
            ?: throw AudioMetadataException("MP3 metadata could not be read back after save")
        reset(metadata.tag ?: Id3Tag.create(), artworksLoaded)
    }
}

internal class WavBacking(private var metadata: WavMetadata, loadArtworks: Boolean) :
    Id3Backing(metadata.tag, loadArtworks) {
    override val format: AudioFormat
        get() = AudioFormat.Wav

    override val audioInfo: AudioInfo?
        get() = metadata.audioInfo

    override fun save(source: WritableRandomAccessSource) {
        WavMetadataWriter.write(source, metadata)
        metadata = WavMetadataReader.read(source)
            ?: throw AudioMetadataException("WAV metadata could not be read back after save")
        reset(metadata.tag, artworksLoaded)
    }
}

internal class AiffBacking(private var metadata: AiffMetadata, loadArtworks: Boolean) :
    Id3Backing(metadata.tag, loadArtworks) {
    override val format: AudioFormat
        get() = AudioFormat.Aiff

    override val audioInfo: AudioInfo?
        get() = metadata.audioInfo

    override fun save(source: WritableRandomAccessSource) {
        AiffMetadataWriter.write(source, metadata)
        metadata = AiffMetadataReader.read(source)
            ?: throw AudioMetadataException("AIFF metadata could not be read back after save")
        reset(metadata.tag, artworksLoaded)
    }
}

internal actual fun openPlatformAudioFile(
    source: RandomAccessSource,
    format: AudioFormat,
    includeArtworks: Boolean,
): AudioFile? = when (format) {
    AudioFormat.Mp3 -> Mp3MetadataReader.read(source)?.let { AudioFile(Mp3Backing(it, includeArtworks)) }
    AudioFormat.Wav -> WavMetadataReader.read(source)?.let { AudioFile(WavBacking(it, includeArtworks)) }
    AudioFormat.Aiff -> AiffMetadataReader.read(source)?.let { AudioFile(AiffBacking(it, includeArtworks)) }
    // Files without an ID3v2 header may still carry an ID3v1 trailer.
    AudioFormat.Unknown -> Mp3MetadataReader.read(source)?.let { AudioFile(Mp3Backing(it, includeArtworks)) }
    else -> null
}

/** Low-level ID3 tag shared by MP3, WAV and AIFF. */
@ExperimentalAudioMetadataApi
val AudioFile.id3: Id3Tag?
    get() = (backing as? Id3Backing)?.id3Tag
