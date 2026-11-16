package com.berryos.audiometadata

import com.berryos.audiometadata.aiff.AiffMetadata
import com.berryos.audiometadata.aiff.AiffMetadataReader
import com.berryos.audiometadata.aiff.AiffMetadataWriter
import com.berryos.audiometadata.id3.Id3Tag
import com.berryos.audiometadata.id3.Id3v2Reader
import com.berryos.audiometadata.id3.Id3v2Tag
import com.berryos.audiometadata.id3.Id3v2Writer
import com.berryos.audiometadata.id3.JaudiotaggerId3Tag
import com.berryos.audiometadata.io.RandomAccessSource
import com.berryos.audiometadata.io.WritableRandomAccessSource
import com.berryos.audiometadata.mp3.Mp3AudioInfoReader
import com.berryos.audiometadata.mp3.Mp3Metadata
import com.berryos.audiometadata.mp3.Mp3MetadataReader
import com.berryos.audiometadata.wav.WavMetadata
import com.berryos.audiometadata.wav.WavMetadataReader
import com.berryos.audiometadata.wav.WavMetadataWriter

/** Maps the shared typed fields onto an ID3 tag. */
internal class Id3TagFields(private val tag: Id3Tag) : TagFields {
    override var title: String?
        get() = tag.title
        set(value) {
            tag.title = value
        }

    override var artists: List<String>
        get() = tag.artists
        set(value) {
            tag.artists = value
        }

    override var album: String?
        get() = tag.album
        set(value) {
            tag.album = value
        }

    override var albumArtist: String?
        get() = tag.albumArtist
        set(value) {
            tag.albumArtist = value
        }

    override var trackNumber: Int?
        get() = tag.trackNumber
        set(value) {
            tag.trackNumber = value
        }

    override var trackTotal: Int?
        get() = tag.trackTotal
        set(value) {
            tag.trackTotal = value
        }

    override var discNumber: Int?
        get() = tag.discNumber
        set(value) {
            tag.discNumber = value
        }

    override var discTotal: Int?
        get() = tag.discTotal
        set(value) {
            tag.discTotal = value
        }

    override var year: String?
        get() = tag.year
        set(value) {
            tag.year = value
        }

    override var genres: List<String>
        get() = tag.genres
        set(value) {
            tag.genres = value
        }

    override var composers: List<String>
        get() = tag.composers
        set(value) {
            tag.composers = value
        }

    override var comments: List<String>
        get() = tag.comments
        set(value) {
            tag.comments = value
        }

    override var replayGain: ReplayGain?
        get() = tag.replayGain
        set(value) {
            tag.replayGain = value
        }
}

/** Backings that expose a low-level [Id3Tag]. */
internal interface Id3FileBacking {
    val id3Tag: Id3Tag
}

/** Spec-driven MP3 backing; the spec-driven reader and writer own the tag. */
internal class Mp3Backing(
    private var metadata: Mp3Metadata,
    private var tag: Id3v2Tag,
    loadArtworks: Boolean,
) : AudioFileBacking(), Id3FileBacking {
    private var fieldView = Id3TagFields(tag)
    private var loadedArtworks: List<Artwork>? = if (loadArtworks) tag.artworks else null

    override val format: AudioFormat
        get() = AudioFormat.Mp3

    override val audioInfo: AudioInfo?
        get() = metadata.audioInfo

    override val text: TagFields
        get() = fieldView

    override val id3Version: Id3Version
        get() = tag.version

    override val id3Tag: Id3v2Tag
        get() = tag

    override var artworks: List<Artwork>?
        get() = loadedArtworks
        set(value) {
            loadedArtworks = value
            if (value != null) tag.setArtworks(value)
        }

    override fun save(source: WritableRandomAccessSource) {
        Id3v2Writer.write(source, tag, metadata.audioStartOffset, tag.declaredSize)
        metadata = Mp3MetadataReader.read(source)
            ?: throw AudioMetadataException("MP3 metadata could not be read back after save")
        tag = Id3v2Reader.read(source)
            ?: throw AudioMetadataException("ID3 tag could not be read back after save")
        fieldView = Id3TagFields(tag)
        loadedArtworks = if (loadedArtworks != null) tag.artworks else null
    }
}

/** Temporary jaudiotagger-backed plumbing shared by WAV and AIFF. */
internal abstract class JaudiotaggerId3Backing(
    id3Tag: JaudiotaggerId3Tag,
    loadArtworks: Boolean,
) : AudioFileBacking(), Id3FileBacking {
    override var id3Tag: JaudiotaggerId3Tag = id3Tag
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

    protected fun reset(tag: JaudiotaggerId3Tag, loadArtworks: Boolean) {
        id3Tag = tag
        fieldView = Id3TagFields(tag)
        loadedArtworks = if (loadArtworks) tag.artworks else null
    }
}

internal class WavBacking(private var metadata: WavMetadata, loadArtworks: Boolean) :
    JaudiotaggerId3Backing(metadata.tag, loadArtworks) {
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
    JaudiotaggerId3Backing(metadata.tag, loadArtworks) {
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
    AudioFormat.Mp3 -> openMp3(source, includeArtworks)
    AudioFormat.Wav -> WavMetadataReader.read(source)?.let { AudioFile(WavBacking(it, includeArtworks)) }
    AudioFormat.Aiff -> AiffMetadataReader.read(source)?.let { AudioFile(AiffBacking(it, includeArtworks)) }
    // Files without an ID3v2 header may still carry an ID3v1 trailer.
    AudioFormat.Unknown -> openMp3(source, includeArtworks)
    else -> null
}

private fun openMp3(source: RandomAccessSource, includeArtworks: Boolean): AudioFile? {
    val metadata = Mp3MetadataReader.read(source)
    val tag = Id3v2Reader.read(source)
    if (metadata == null && tag == null) {
        val audioInfo = Mp3AudioInfoReader.read(source, 0) ?: return null
        val untagged = Mp3Metadata(tag = null, audioStartOffset = 0, id3v1 = null, audioInfo = audioInfo)
        return AudioFile(Mp3Backing(untagged, Id3v2Tag.create(), includeArtworks))
    }
    val resolved = metadata ?: Mp3Metadata(
        tag = null,
        audioStartOffset = 0,
        id3v1 = null,
        audioInfo = Mp3AudioInfoReader.read(source, 0),
    )
    return AudioFile(Mp3Backing(resolved, tag ?: Id3v2Tag.create(), includeArtworks))
}

/** Low-level ID3 tag shared by MP3, WAV and AIFF. */
@ExperimentalAudioMetadataApi
val AudioFile.id3: Id3Tag?
    get() = (backing as? Id3FileBacking)?.id3Tag
