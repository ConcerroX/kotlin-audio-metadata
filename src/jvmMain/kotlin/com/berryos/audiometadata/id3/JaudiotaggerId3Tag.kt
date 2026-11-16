package com.berryos.audiometadata.id3

import com.berryos.audiometadata.Artwork
import com.berryos.audiometadata.AudioFormat
import com.berryos.audiometadata.AudioInfo
import com.berryos.audiometadata.AudioMetadata
import com.berryos.audiometadata.Id3Version
import com.berryos.audiometadata.ReplayGain
import com.berryos.audiometadata.guessArtworkMimeType
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.TagField
import org.jaudiotagger.tag.id3.AbstractID3v2Frame
import org.jaudiotagger.tag.id3.AbstractID3v2Tag
import org.jaudiotagger.tag.id3.ID3v22Tag
import org.jaudiotagger.tag.id3.ID3v23Frame
import org.jaudiotagger.tag.id3.ID3v23Tag
import org.jaudiotagger.tag.id3.ID3v24Frame
import org.jaudiotagger.tag.id3.ID3v24Tag
import org.jaudiotagger.tag.id3.framebody.AbstractFrameBodyTextInfo
import org.jaudiotagger.tag.id3.framebody.FrameBodyAPIC
import org.jaudiotagger.tag.id3.framebody.FrameBodyCOMM
import org.jaudiotagger.tag.id3.framebody.FrameBodyTXXX
import org.jaudiotagger.tag.reference.GenreTypes

/**
 * Temporary [Id3Tag] implementation backed by jaudiotagger.
 *
 * Used by the JVM containers until the spec-driven implementation takes over; the raw tag is kept
 * accessible for their writers.
 */
internal class JaudiotaggerId3Tag internal constructor(
    override val version: Id3Version,
    internal val tag: AbstractID3v2Tag,
    internal val originalTagSize: Int,
) : Id3Tag {
    override var title: String?
        get() = text(FieldKey.TITLE).ifEmpty { null }
        set(value) = setText(FieldKey.TITLE, value.orEmpty())

    override var artists: List<String>
        get() = values(FieldKey.ARTIST)
        set(value) = setValues(FieldKey.ARTIST, value)

    override var album: String?
        get() = text(FieldKey.ALBUM).ifEmpty { null }
        set(value) = setText(FieldKey.ALBUM, value.orEmpty())

    override var albumArtist: String?
        get() = text(FieldKey.ALBUM_ARTIST).ifEmpty { null }
        set(value) = setText(FieldKey.ALBUM_ARTIST, value.orEmpty())

    override var trackNumber: Int?
        get() = text(FieldKey.TRACK).toIntOrNull()
        set(value) = setText(FieldKey.TRACK, value?.toString().orEmpty())

    override var trackTotal: Int?
        get() = text(FieldKey.TRACK_TOTAL).toIntOrNull()
        set(value) = setText(FieldKey.TRACK_TOTAL, value?.toString().orEmpty())

    override var discNumber: Int?
        get() = text(FieldKey.DISC_NO).toIntOrNull()
        set(value) = setText(FieldKey.DISC_NO, value?.toString().orEmpty())

    override var discTotal: Int?
        get() = text(FieldKey.DISC_TOTAL).toIntOrNull()
        set(value) = setText(FieldKey.DISC_TOTAL, value?.toString().orEmpty())

    override var year: String?
        get() = text(FieldKey.YEAR).ifEmpty { null }
        set(value) = setText(FieldKey.YEAR, value.orEmpty())

    override var genres: List<String>
        get() = genres()
        set(value) = setValues(FieldKey.GENRE, value)

    override var composers: List<String>
        get() = values(FieldKey.COMPOSER)
        set(value) = setValues(FieldKey.COMPOSER, value)

    override var comments: List<String>
        get() = values(FieldKey.COMMENT)
        set(value) = setValues(FieldKey.COMMENT, value)

    override var replayGain: ReplayGain?
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
        set(value) {
            if (version == Id3Version.V22) return
            val kept = tag.getFields("TXXX").filterNot { field ->
                val body = (field as? AbstractID3v2Frame)?.body
                body is FrameBodyTXXX && body.description.startsWith(REPLAYGAIN_PREFIX)
            }
            tag.removeFrame("TXXX")
            kept.forEach { field -> tag.addField(field) }
            if (value == null) return
            val entries = mapOf(
                "REPLAYGAIN_TRACK_GAIN" to value.trackGain,
                "REPLAYGAIN_TRACK_PEAK" to value.trackPeak,
                "REPLAYGAIN_ALBUM_GAIN" to value.albumGain,
                "REPLAYGAIN_ALBUM_PEAK" to value.albumPeak,
            )
            entries.forEach { (description, text) ->
                if (text != null) createTxxxFrame(description, text)?.let(tag::addField)
            }
        }

    override val artworks: List<Artwork>
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

    override fun setArtworks(artworks: List<Artwork>) {
        tag.deleteArtworkField()
        if (version == Id3Version.V22) return
        artworks.forEach { artwork ->
            val frame = when (version) {
                Id3Version.V23 -> ID3v23Frame("APIC")
                Id3Version.V24 -> ID3v24Frame("APIC")
                Id3Version.V22 -> return@forEach
            }
            val body = frame.body as FrameBodyAPIC
            body.mimeType = artwork.mimeType ?: guessArtworkMimeType(artwork.data)
            body.setPictureType((artwork.pictureType ?: DEFAULT_PICTURE_TYPE).toByte())
            body.description = artwork.description.orEmpty()
            body.imageData = artwork.data
            tag.addField(frame)
        }
    }

    override fun toAudioMetadata(format: AudioFormat, audioInfo: AudioInfo?): AudioMetadata = AudioMetadata(
        format = format,
        id3Version = version,
        title = title,
        artists = artists,
        album = album,
        albumArtist = albumArtist,
        trackNumber = trackNumber,
        trackTotal = trackTotal,
        discNumber = discNumber,
        discTotal = discTotal,
        year = year,
        genres = genres,
        composers = composers,
        comments = comments,
        replayGain = replayGain,
        audioInfo = audioInfo,
    )

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

    private fun setValues(key: FieldKey, values: List<String>) {
        tag.deleteField(key)
        // Repeated single-value adds are appended as null-separated values, which works for both
        // ID3v2.3 and ID3v2.4; the vararg overload keeps only the first value on v2.3.
        values.forEach { value -> tag.addField(key, value) }
    }

    // TCON may reference ID3v1 genres by number, e.g. "(17)" for Rock; resolve them for display.
    private fun genres(): List<String> = values(FieldKey.GENRE).flatMap(::resolveGenreReferences)

    private fun createTxxxFrame(description: String, text: String): AbstractID3v2Frame? {
        val frame = when (version) {
            Id3Version.V22 -> return null
            Id3Version.V23 -> ID3v23Frame("TXXX")
            Id3Version.V24 -> ID3v24Frame("TXXX")
        }
        val body = frame.body as FrameBodyTXXX
        body.description = description
        body.text = text
        return frame
    }

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
        return when {
            body is AbstractFrameBodyTextInfo -> body.values
            body is FrameBodyCOMM -> listOf(body.text)
            else -> listOf(field.toString())
        }
    }

    companion object {
        /** Creates an empty tag of [version], used when a container has no ID3 chunk yet. */
        fun create(version: Id3Version = Id3Version.V23): JaudiotaggerId3Tag {
            val tag = when (version) {
                Id3Version.V22 -> ID3v22Tag()
                Id3Version.V23 -> ID3v23Tag()
                Id3Version.V24 -> ID3v24Tag()
            }
            return JaudiotaggerId3Tag(version, tag, 0)
        }
    }
}

private const val REPLAYGAIN_PREFIX = "REPLAYGAIN_"
private const val DEFAULT_PICTURE_TYPE = 3

private val GENRE_REFERENCE = Regex("\\((\\d+)\\)")
