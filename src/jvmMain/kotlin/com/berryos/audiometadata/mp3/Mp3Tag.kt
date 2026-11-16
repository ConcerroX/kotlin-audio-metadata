package com.berryos.audiometadata.mp3

import com.berryos.audiometadata.Artwork
import com.berryos.audiometadata.AudioFormat
import com.berryos.audiometadata.AudioInfo
import com.berryos.audiometadata.AudioMetadata
import com.berryos.audiometadata.Id3Version
import com.berryos.audiometadata.ReplayGain
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.TagField
import org.jaudiotagger.tag.id3.AbstractID3v2Frame
import org.jaudiotagger.tag.id3.AbstractID3v2Tag
import org.jaudiotagger.tag.id3.framebody.AbstractFrameBodyTextInfo
import org.jaudiotagger.tag.id3.framebody.FrameBodyAPIC
import org.jaudiotagger.tag.id3.framebody.FrameBodyTXXX
import org.jaudiotagger.tag.reference.GenreTypes

/**
 * Mutable view of an ID3v2 tag.
 *
 * Only the edited fields are re-encoded; every other frame stays in the tag and is written back
 * by jaudiotagger unchanged.
 */
class Mp3Tag internal constructor(
    val version: Id3Version,
    internal val tag: AbstractID3v2Tag,
    internal val originalTagSize: Int,
) {
    var title: String
        get() = text(FieldKey.TITLE)
        set(value) = setText(FieldKey.TITLE, value)

    var artist: String
        get() = text(FieldKey.ARTIST)
        set(value) = setText(FieldKey.ARTIST, value)

    var album: String
        get() = text(FieldKey.ALBUM)
        set(value) = setText(FieldKey.ALBUM, value)

    var albumArtist: String
        get() = text(FieldKey.ALBUM_ARTIST)
        set(value) = setText(FieldKey.ALBUM_ARTIST, value)

    var trackNumber: String
        get() = text(FieldKey.TRACK)
        set(value) = setText(FieldKey.TRACK, value)

    var trackTotal: String
        get() = text(FieldKey.TRACK_TOTAL)
        set(value) = setText(FieldKey.TRACK_TOTAL, value)

    var discNumber: String
        get() = text(FieldKey.DISC_NO)
        set(value) = setText(FieldKey.DISC_NO, value)

    var discTotal: String
        get() = text(FieldKey.DISC_TOTAL)
        set(value) = setText(FieldKey.DISC_TOTAL, value)

    var year: String
        get() = text(FieldKey.YEAR)
        set(value) = setText(FieldKey.YEAR, value)

    var genre: String
        get() = text(FieldKey.GENRE)
        set(value) = setText(FieldKey.GENRE, value)

    var composer: String
        get() = text(FieldKey.COMPOSER)
        set(value) = setText(FieldKey.COMPOSER, value)

    var comment: String
        get() = text(FieldKey.COMMENT)
        set(value) = setText(FieldKey.COMMENT, value)

    val replayGain: ReplayGain?
        get() {
            val values = mutableMapOf<String, String>()
            val frames = tag.fields
            while (frames.hasNext()) {
                val body = (frames.next() as? AbstractID3v2Frame)?.body
                if (body is FrameBodyTXXX && body.description.startsWith(REPLAYGAIN_PREFIX)) {
                    values[body.description] = body.text
                }
            }
            if (values.isEmpty()) return null
            return ReplayGain(
                trackGain = values["REPLAYGAIN_TRACK_GAIN"],
                trackPeak = values["REPLAYGAIN_TRACK_PEAK"],
                albumGain = values["REPLAYGAIN_ALBUM_GAIN"],
                albumPeak = values["REPLAYGAIN_ALBUM_PEAK"],
            )
        }

    val artworks: List<Artwork>
        get() {
            val result = ArrayList<Artwork>()
            val frames = tag.fields
            while (frames.hasNext()) {
                val body = (frames.next() as? AbstractID3v2Frame)?.body
                if (body is FrameBodyAPIC && !body.isImageUrl) {
                    val data = body.imageData
                    if (data != null && data.isNotEmpty()) {
                        result += Artwork(
                            data = data,
                            mimeType = body.mimeType?.ifEmpty { null },
                            pictureType = body.pictureType,
                            description = body.description?.ifEmpty { null },
                        )
                    }
                }
            }
            return result
        }

    fun toAudioMetadata(audioInfo: AudioInfo? = null): AudioMetadata {
        return AudioMetadata(
            format = AudioFormat.Mp3,
            id3Version = version,
            title = text(FieldKey.TITLE).ifEmpty { null },
            artists = values(FieldKey.ARTIST),
            album = text(FieldKey.ALBUM).ifEmpty { null },
            albumArtist = text(FieldKey.ALBUM_ARTIST).ifEmpty { null },
            trackNumber = text(FieldKey.TRACK).toIntOrNull(),
            trackTotal = text(FieldKey.TRACK_TOTAL).toIntOrNull(),
            discNumber = text(FieldKey.DISC_NO).toIntOrNull(),
            discTotal = text(FieldKey.DISC_TOTAL).toIntOrNull(),
            year = text(FieldKey.YEAR).ifEmpty { null },
            genres = genres(),
            composers = values(FieldKey.COMPOSER),
            comments = values(FieldKey.COMMENT),
            replayGain = replayGain,
            audioInfo = audioInfo,
        )
    }

    private fun text(key: FieldKey): String = tag.getFirst(key).orEmpty()

    private fun setText(key: FieldKey, value: String) {
        if (value.isEmpty()) {
            tag.deleteField(key)
        } else {
            tag.setField(key, value)
        }
    }

    private fun values(key: FieldKey): List<String> = tag.getFields(key)
        .flatMap(::fieldValues)
        .filter(String::isNotEmpty)

    // TCON may reference ID3v1 genres by number, e.g. "(17)" for Rock; resolve them for display.
    private fun genres(): List<String> = values(FieldKey.GENRE).flatMap(::resolveGenreReferences)

    private fun resolveGenreReferences(value: String): List<String> {
        val matches = GENRE_REFERENCE.findAll(value).toList()
        if (matches.isEmpty()) return listOf(value)
        val resolved = matches.mapNotNull { match ->
            match.groupValues[1].toIntOrNull()?.let(GenreTypes.getInstanceOf()::getValueForId)
        }
        return resolved.ifEmpty { listOf(value) }
    }

    private fun fieldValues(field: TagField): List<String> {
        val body = (field as? AbstractID3v2Frame)?.body
        return if (body is AbstractFrameBodyTextInfo) body.values else listOf(field.toString())
    }
}

private const val REPLAYGAIN_PREFIX = "REPLAYGAIN_"

private val GENRE_REFERENCE = Regex("\\((\\d+)\\)")

/** Result of reading an MP3 file: the ID3v2 tag (if any), the audio offset and the ID3v1 tag. */
class Mp3Metadata internal constructor(
    val tag: Mp3Tag?,
    internal val audioStartOffset: Long,
    val id3v1: Id3v1Metadata?,
    val audioInfo: AudioInfo? = null,
) {
    fun toAudioMetadata(): AudioMetadata = tag?.toAudioMetadata(audioInfo)
        ?: id3v1?.toAudioMetadata(audioInfo)
        ?: AudioMetadata(format = AudioFormat.Mp3, audioInfo = audioInfo)
}

/** Fields of a trailing ID3v1/ID3v1.1 tag. */
data class Id3v1Metadata(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val year: String? = null,
    val comment: String? = null,
    val trackNumber: Int? = null,
    val genreIndex: Int? = null,
) {
    fun toAudioMetadata(audioInfo: AudioInfo? = null): AudioMetadata = AudioMetadata(
        format = AudioFormat.Mp3,
        title = title,
        artists = listOfNotNull(artist),
        album = album,
        year = year,
        comments = listOfNotNull(comment),
        trackNumber = trackNumber,
        audioInfo = audioInfo,
    )
}
