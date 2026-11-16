package com.berryos.audiometadata

import com.berryos.audiometadata.flac.FlacMetadata
import com.berryos.audiometadata.flac.FlacMetadataReader
import com.berryos.audiometadata.flac.FlacMetadataWriter
import com.berryos.audiometadata.io.WritableRandomAccessSource
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
    abstract var text: TagFields
    abstract var artworks: List<Artwork>?

    open val id3Version: Id3Version?
        get() = null

    open val vorbisTag: VorbisCommentTag?
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

internal class FlacBacking(initial: FlacMetadata) : AudioFileBacking() {
    private var metadata = initial

    override val format: AudioFormat
        get() = AudioFormat.Flac

    override val audioInfo: AudioInfo?
        get() = metadata.streamInfo?.toAudioInfo()

    override var text: TagFields = VorbisTagFields(metadata.tag)

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
            ?: throw IllegalStateException("FLAC metadata could not be read back after save")
        text = VorbisTagFields(metadata.tag)
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

    override var text: TagFields = VorbisTagFields(metadata.tag)

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

    override var text: TagFields = VorbisTagFields(metadata.tag)

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
