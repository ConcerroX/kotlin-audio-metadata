package com.berryos.audiometadata

import com.berryos.audiometadata.aiff.AiffMetadata
import com.berryos.audiometadata.aiff.AiffMetadataReader
import com.berryos.audiometadata.aiff.AiffMetadataWriter
import com.berryos.audiometadata.id3.Id3Tag
import com.berryos.audiometadata.id3.Id3v2Reader
import com.berryos.audiometadata.id3.Id3v2Tag
import com.berryos.audiometadata.id3.Id3v2Writer
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

/** Extension storage for ID3 tags; ID3v2.2 has no sort/compilation frames. */
internal class Id3ExtensionFields(private val tag: Id3v2Tag) : ExtensionFields {
    private val supported = tag.version != Id3Version.V22

    override var titleSort: String?
        get() = if (supported) tag.frameText("TSOT") else null
        set(value) = setText("TSOT", value)

    override var artistSort: String?
        get() = if (supported) tag.frameText("TSOP") else null
        set(value) = setText("TSOP", value)

    override var albumSort: String?
        get() = if (supported) tag.frameText("TSOA") else null
        set(value) = setText("TSOA", value)

    override var albumArtistSort: String?
        get() = if (supported) tag.frameText("TSO2") else null
        set(value) = setText("TSO2", value)

    override var compilation: Boolean?
        get() = if (supported) tag.frameText("TCMP")?.let { it == "1" } else null
        set(value) = setText("TCMP", value?.let { if (it) "1" else "0" })

    override var bpm: Int?
        get() = if (supported) tag.frameText("TBPM")?.toIntOrNull() else null
        set(value) = setText("TBPM", value?.toString())

    override var isrc: String?
        get() = if (supported) tag.frameText("TSRC") else null
        set(value) = setText("TSRC", value)

    override var originalDate: String?
        get() = if (supported) tag.frameText(originalDateFrameId) else null
        set(value) = setText(originalDateFrameId, value)

    override var r128TrackGain: String?
        get() = if (supported) tag.userText("R128_TRACK_GAIN") else null
        set(value) {
            if (supported) tag.setUserText("R128_TRACK_GAIN", value)
        }

    override var r128AlbumGain: String?
        get() = if (supported) tag.userText("R128_ALBUM_GAIN") else null
        set(value) {
            if (supported) tag.setUserText("R128_ALBUM_GAIN", value)
        }

    override val musicBrainz: MusicBrainzFields = Id3MusicBrainzFields(tag)

    private val originalDateFrameId: String
        get() = when (tag.version) {
            Id3Version.V22 -> "TOR"
            Id3Version.V23 -> "TORY"
            Id3Version.V24 -> "TDOR"
        }

    private fun setText(id: String, value: String?) {
        if (supported) tag.setFrameText(id, value)
    }
}

/** MusicBrainz storage for ID3: UFID for the recording ID, TXXX for the rest. */
internal class Id3MusicBrainzFields(private val tag: Id3v2Tag) : MusicBrainzFields {
    private val supported = tag.version != Id3Version.V22
    private val owner = "http://musicbrainz.org"

    override var recordingId: String?
        get() = if (supported) tag.ufid(owner) else null
        set(value) {
            if (supported) tag.setUfid(owner, value)
        }

    override var releaseTrackId: String?
        get() = text("MusicBrainz Release Track Id")
        set(value) = setText("MusicBrainz Release Track Id", value)

    override var albumId: String?
        get() = text("MusicBrainz Album Id")
        set(value) = setText("MusicBrainz Album Id", value)

    override var artistId: String?
        get() = text("MusicBrainz Artist Id")
        set(value) = setText("MusicBrainz Artist Id", value)

    override var albumArtistId: String?
        get() = text("MusicBrainz Album Artist Id")
        set(value) = setText("MusicBrainz Album Artist Id", value)

    override var releaseGroupId: String?
        get() = text("MusicBrainz Release Group Id")
        set(value) = setText("MusicBrainz Release Group Id", value)

    override var workId: String?
        get() = text("MusicBrainz Work Id")
        set(value) = setText("MusicBrainz Work Id", value)

    private fun text(description: String): String? = if (supported) tag.userText(description) else null

    private fun setText(description: String, value: String?) {
        if (supported) tag.setUserText(description, value)
    }
}

/** Common plumbing for containers whose metadata is an [Id3v2Tag]. */
internal abstract class SpecId3Backing(tag: Id3v2Tag, loadArtworks: Boolean) : AudioFileBacking(), Id3FileBacking {
    private var currentTag = tag
    private var fieldView = Id3TagFields(currentTag)
    private var extensionView = Id3ExtensionFields(currentTag)
    private var loadedArtworks: List<Artwork>? = if (loadArtworks) currentTag.artworks else null

    override val text: TagFields
        get() = fieldView

    override val extensions: ExtensionFields
        get() = extensionView

    override val id3Version: Id3Version
        get() = currentTag.version

    override val id3Tag: Id3v2Tag
        get() = currentTag

    override var artworks: List<Artwork>?
        get() = loadedArtworks
        set(value) {
            loadedArtworks = value
            if (value != null) currentTag.setArtworks(value)
        }

    protected val artworksLoaded: Boolean
        get() = loadedArtworks != null

    protected fun replaceTag(tag: Id3v2Tag, loadArtworks: Boolean) {
        currentTag = tag
        fieldView = Id3TagFields(tag)
        extensionView = Id3ExtensionFields(tag)
        loadedArtworks = if (loadArtworks) tag.artworks else null
    }
}

internal class Mp3Backing(
    private var metadata: Mp3Metadata,
    tag: Id3v2Tag,
    loadArtworks: Boolean,
) : SpecId3Backing(tag, loadArtworks) {
    override val format: AudioFormat
        get() = AudioFormat.Mp3

    override val audioInfo: AudioInfo?
        get() = metadata.audioInfo

    override fun save(source: WritableRandomAccessSource) {
        Id3v2Writer.write(source, id3Tag, metadata.audioStartOffset, id3Tag.declaredSize)
        metadata = Mp3MetadataReader.read(source)
            ?: throw AudioMetadataException("MP3 metadata could not be read back after save")
        replaceTag(metadata.tag ?: Id3v2Tag.create(), artworksLoaded)
    }
}

internal class WavBacking(private var metadata: WavMetadata, loadArtworks: Boolean) :
    SpecId3Backing(metadata.tag, loadArtworks) {
    override val format: AudioFormat
        get() = AudioFormat.Wav

    override val audioInfo: AudioInfo?
        get() = metadata.audioInfo

    override fun save(source: WritableRandomAccessSource) {
        WavMetadataWriter.write(source, metadata)
        metadata = WavMetadataReader.read(source)
            ?: throw AudioMetadataException("WAV metadata could not be read back after save")
        replaceTag(metadata.tag, artworksLoaded)
    }
}

internal class AiffBacking(private var metadata: AiffMetadata, loadArtworks: Boolean) :
    SpecId3Backing(metadata.tag, loadArtworks) {
    override val format: AudioFormat
        get() = AudioFormat.Aiff

    override val audioInfo: AudioInfo?
        get() = metadata.audioInfo

    override fun save(source: WritableRandomAccessSource) {
        AiffMetadataWriter.write(source, metadata)
        metadata = AiffMetadataReader.read(source)
            ?: throw AudioMetadataException("AIFF metadata could not be read back after save")
        replaceTag(metadata.tag, artworksLoaded)
    }
}

internal fun openMp3(source: RandomAccessSource, includeArtworks: Boolean): AudioFile? {
    val metadata = Mp3MetadataReader.read(source)
    if (metadata == null) {
        val audioInfo = Mp3AudioInfoReader.read(source, 0) ?: return null
        val untagged = Mp3Metadata(tag = null, audioStartOffset = 0, id3v1 = null, audioInfo = audioInfo)
        return AudioFile(Mp3Backing(untagged, Id3v2Tag.create(), includeArtworks))
    }
    val tag = Id3v2Reader.read(source) ?: metadata.tag ?: Id3v2Tag.create()
    return AudioFile(Mp3Backing(metadata, tag, includeArtworks))
}
