package com.berryos.audiometadata

import com.berryos.audiometadata.flac.FlacMetadata
import com.berryos.audiometadata.flac.FlacMetadataReader
import com.berryos.audiometadata.flac.FlacMetadataWriter
import com.berryos.audiometadata.io.WritableRandomAccessSource
import com.berryos.audiometadata.mp4.Mp4Metadata
import com.berryos.audiometadata.mp4.Mp4MetadataWriter
import com.berryos.audiometadata.mp4.Mp4Tags
import com.berryos.audiometadata.opus.OpusMetadata
import com.berryos.audiometadata.opus.OpusMetadataWriter
import com.berryos.audiometadata.vorbis.OggVorbisMetadata
import com.berryos.audiometadata.vorbis.OggVorbisMetadataWriter
import com.berryos.audiometadata.vorbis.VorbisCommentTag
import com.berryos.audiometadata.vorbis.artworks
import com.berryos.audiometadata.vorbis.setArtworks

/** Typed view over one tag structure; every backing implements it the same way for [AudioFile]. */
internal interface TagFields {
    var title: String?
    var artists: List<String>
    var album: String?
    var albumArtist: String?
    var trackNumber: Int?
    var trackTotal: Int?
    var discNumber: Int?
    var discTotal: Int?
    var year: String?
    var genres: List<String>
    var composers: List<String>
    var comments: List<String>
    var replayGain: ReplayGain?
}

/** Format-specific state behind an [AudioFile]. */
internal abstract class AudioFileBacking {
    abstract val format: AudioFormat
    abstract val audioInfo: AudioInfo?
    abstract val text: TagFields
    abstract val extensions: ExtensionFields
    abstract var artworks: List<Artwork>?

    open val id3Version: Id3Version?
        get() = null

    open val vorbisTag: VorbisCommentTag?
        get() = null

    open val mp4Tags: Mp4Tags?
        get() = null

    abstract fun save(source: WritableRandomAccessSource)
}

/** Vorbis comments map directly to the typed fields; shared by FLAC, Ogg Vorbis and Opus. */
internal class VorbisTagFields(private val tag: VorbisCommentTag) : TagFields {
    override var title: String?
        get() = tag.value("TITLE")?.takeIf(String::isNotEmpty)
        set(value) = tag.set("TITLE", value.orEmpty())

    override var artists: List<String>
        get() = tag.values("ARTIST")
        set(value) = tag.setValues("ARTIST", value)

    override var album: String?
        get() = tag.value("ALBUM")?.takeIf(String::isNotEmpty)
        set(value) = tag.set("ALBUM", value.orEmpty())

    override var albumArtist: String?
        get() = tag.value("ALBUMARTIST")?.takeIf(String::isNotEmpty)
        set(value) = tag.set("ALBUMARTIST", value.orEmpty())

    override var trackNumber: Int?
        get() = readNumber("TRACKNUMBER", "TRACKTOTAL").first
        set(value) = writeNumber("TRACKNUMBER", value, trackTotal)

    override var trackTotal: Int?
        get() = readNumber("TRACKNUMBER", "TRACKTOTAL").second
        set(value) {
            tag.set("TRACKTOTAL", value?.toString().orEmpty())
            writeNumber("TRACKNUMBER", trackNumber, value)
        }

    override var discNumber: Int?
        get() = readNumber("DISCNUMBER", "DISCTOTAL").first
        set(value) = writeNumber("DISCNUMBER", value, discTotal)

    override var discTotal: Int?
        get() = readNumber("DISCNUMBER", "DISCTOTAL").second
        set(value) {
            tag.set("DISCTOTAL", value?.toString().orEmpty())
            writeNumber("DISCNUMBER", discNumber, value)
        }

    override var year: String?
        get() = tag.value("DATE")?.takeIf(String::isNotEmpty)
        set(value) = tag.set("DATE", value.orEmpty())

    override var genres: List<String>
        get() = tag.values("GENRE")
        set(value) = tag.setValues("GENRE", value)

    override var composers: List<String>
        get() = tag.values("COMPOSER")
        set(value) = tag.setValues("COMPOSER", value)

    override var comments: List<String>
        get() = tag.values("COMMENT")
        set(value) = tag.setValues("COMMENT", value)

    override var replayGain: ReplayGain?
        get() = tag.replayGain
        set(value) {
            tag.set("REPLAYGAIN_TRACK_GAIN", value?.trackGain.orEmpty())
            tag.set("REPLAYGAIN_TRACK_PEAK", value?.trackPeak.orEmpty())
            tag.set("REPLAYGAIN_ALBUM_GAIN", value?.albumGain.orEmpty())
            tag.set("REPLAYGAIN_ALBUM_PEAK", value?.albumPeak.orEmpty())
        }

    private fun readNumber(key: String, totalKey: String): Pair<Int?, Int?> {
        val parts = tag.value(key).orEmpty().split('/', limit = 2)
        val number = parts.getOrNull(0)?.trim()?.toIntOrNull()
        val total = tag.value(totalKey)?.toIntOrNull()
            ?: parts.getOrNull(1)?.trim()?.toIntOrNull()
        return number to total
    }

    private fun writeNumber(key: String, number: Int?, total: Int?) {
        val text = when {
            number == null -> ""
            total == null -> number.toString()
            else -> "$number/$total"
        }
        tag.set(key, text)
    }
}

internal class VorbisExtensionFields(private val tag: VorbisCommentTag) : ExtensionFields {
    override var titleSort: String?
        get() = value("TITLESORT")
        set(value) = tag.set("TITLESORT", value.orEmpty())

    override var artistSort: String?
        get() = value("ARTISTSORT")
        set(value) = tag.set("ARTISTSORT", value.orEmpty())

    override var albumSort: String?
        get() = value("ALBUMSORT")
        set(value) = tag.set("ALBUMSORT", value.orEmpty())

    override var albumArtistSort: String?
        get() = value("ALBUMARTISTSORT")
        set(value) = tag.set("ALBUMARTISTSORT", value.orEmpty())

    override var compilation: Boolean?
        get() = tag.value("COMPILATION")?.let(::parseBoolean)
        set(value) = tag.set("COMPILATION", value?.let { if (it) "1" else "0" }.orEmpty())

    override var bpm: Int?
        get() = value("BPM")?.toIntOrNull()
        set(value) = tag.set("BPM", value?.toString().orEmpty())

    override var isrc: String?
        get() = value("ISRC")
        set(value) = tag.set("ISRC", value.orEmpty())

    private fun value(key: String): String? = tag.value(key)?.takeIf(String::isNotEmpty)

    private fun parseBoolean(value: String): Boolean =
        value == "1" || value.equals("true", ignoreCase = true) || value.equals("yes", ignoreCase = true)
}

internal class FlacBacking(initial: FlacMetadata) : AudioFileBacking() {
    private var metadata = initial
    private var fields = VorbisTagFields(metadata.tag)
    private var extensionView = VorbisExtensionFields(metadata.tag)

    override val format: AudioFormat
        get() = AudioFormat.Flac

    override val audioInfo: AudioInfo?
        get() = metadata.streamInfo?.toAudioInfo()

    override val text: TagFields
        get() = fields

    override val extensions: ExtensionFields
        get() = extensionView

    override val vorbisTag: VorbisCommentTag
        get() = metadata.tag

    override var artworks: List<Artwork>?
        get() = metadata.artworks
        set(value) {
            metadata.artworks = value
        }

    override fun save(source: WritableRandomAccessSource) {
        val current = metadata
        FlacMetadataWriter.write(source, current)
        metadata = FlacMetadataReader.read(source, includeArtworks = current.artworks != null)
            ?: throw AudioMetadataException("FLAC metadata could not be read back after save")
        fields = VorbisTagFields(metadata.tag)
        extensionView = VorbisExtensionFields(metadata.tag)
    }
}

internal class OggVorbisBacking(
    private val metadata: OggVorbisMetadata,
    loadArtworks: Boolean,
) : AudioFileBacking() {
    private var loadedArtworks: List<Artwork>? = if (loadArtworks) metadata.tag.artworks() else null

    override val format: AudioFormat
        get() = AudioFormat.Ogg

    override val audioInfo: AudioInfo?
        get() = metadata.audioInfo

    override val text: TagFields = VorbisTagFields(metadata.tag)

    override val extensions: ExtensionFields = VorbisExtensionFields(metadata.tag)

    override val vorbisTag: VorbisCommentTag
        get() = metadata.tag

    override var artworks: List<Artwork>?
        get() = loadedArtworks
        set(value) {
            loadedArtworks = value
            if (value != null) metadata.tag.setArtworks(value)
        }

    override fun save(source: WritableRandomAccessSource) {
        OggVorbisMetadataWriter.write(source, metadata)
    }
}

internal class OpusBacking(
    private val metadata: OpusMetadata,
    loadArtworks: Boolean,
) : AudioFileBacking() {
    private var loadedArtworks: List<Artwork>? = if (loadArtworks) metadata.tag.artworks() else null

    override val format: AudioFormat
        get() = AudioFormat.Opus

    override val audioInfo: AudioInfo?
        get() = metadata.audioInfo

    override val text: TagFields = VorbisTagFields(metadata.tag)

    override val extensions: ExtensionFields = VorbisExtensionFields(metadata.tag)

    override val vorbisTag: VorbisCommentTag
        get() = metadata.tag

    override var artworks: List<Artwork>?
        get() = loadedArtworks
        set(value) {
            loadedArtworks = value
            if (value != null) metadata.tag.setArtworks(value)
        }

    override fun save(source: WritableRandomAccessSource) {
        OpusMetadataWriter.write(source, metadata)
    }
}

/** iTunes fields are an immutable value object, so every edit replaces [tags] with a copy. */
internal class Mp4TagFields(var tags: Mp4Tags) : TagFields, ExtensionFields {
    override var title: String?
        get() = tags.title
        set(value) {
            tags = tags.copy(title = value)
        }

    override var artists: List<String>
        get() = tags.artists
        set(value) {
            tags = tags.copy(artists = value)
        }

    override var album: String?
        get() = tags.album
        set(value) {
            tags = tags.copy(album = value)
        }

    override var albumArtist: String?
        get() = tags.albumArtist
        set(value) {
            tags = tags.copy(albumArtist = value)
        }

    override var trackNumber: Int?
        get() = tags.trackNumber
        set(value) {
            tags = tags.copy(trackNumber = value)
        }

    override var trackTotal: Int?
        get() = tags.trackTotal
        set(value) {
            tags = tags.copy(trackTotal = value)
        }

    override var discNumber: Int?
        get() = tags.discNumber
        set(value) {
            tags = tags.copy(discNumber = value)
        }

    override var discTotal: Int?
        get() = tags.discTotal
        set(value) {
            tags = tags.copy(discTotal = value)
        }

    override var year: String?
        get() = tags.year
        set(value) {
            tags = tags.copy(year = value)
        }

    override var genres: List<String>
        get() = tags.genres
        set(value) {
            tags = tags.copy(genres = value)
        }

    override var composers: List<String>
        get() = tags.composers
        set(value) {
            tags = tags.copy(composers = value)
        }

    override var comments: List<String>
        get() = tags.comments
        set(value) {
            tags = tags.copy(comments = value)
        }

    override var replayGain: ReplayGain?
        get() = null
        set(value) {
            if (value != null) throw UnsupportedOperationException("MP4 does not store ReplayGain")
        }

    override var titleSort: String?
        get() = tags.titleSort
        set(value) {
            tags = tags.copy(titleSort = value)
        }

    override var artistSort: String?
        get() = tags.artistSort
        set(value) {
            tags = tags.copy(artistSort = value)
        }

    override var albumSort: String?
        get() = tags.albumSort
        set(value) {
            tags = tags.copy(albumSort = value)
        }

    override var albumArtistSort: String?
        get() = tags.albumArtistSort
        set(value) {
            tags = tags.copy(albumArtistSort = value)
        }

    override var compilation: Boolean?
        get() = tags.compilation
        set(value) {
            tags = tags.copy(compilation = value)
        }

    override var bpm: Int?
        get() = tags.bpm
        set(value) {
            tags = tags.copy(bpm = value)
        }

    override var isrc: String?
        get() = null
        set(value) {
            if (value != null) throw UnsupportedOperationException("MP4 ISRC requires freeform atom support")
        }
}

internal class Mp4Backing(private val metadata: Mp4Metadata) : AudioFileBacking() {
    private val tagFields = Mp4TagFields(metadata.tags)

    override val format: AudioFormat
        get() = AudioFormat.Mp4

    override val audioInfo: AudioInfo?
        get() = metadata.audioInfo

    override val text: TagFields
        get() = tagFields

    override val extensions: ExtensionFields
        get() = tagFields

    override val mp4Tags: Mp4Tags
        get() = tagFields.tags

    override var artworks: List<Artwork>?
        get() = tagFields.tags.artworks
        set(value) {
            tagFields.tags = tagFields.tags.copy(artworks = value)
        }

    override fun save(source: WritableRandomAccessSource) {
        Mp4MetadataWriter.write(source, metadata, tagFields.tags)
    }
}
