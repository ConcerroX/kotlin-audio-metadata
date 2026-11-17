package com.berryos.audiometadata.id3

import com.berryos.audiometadata.io.RandomAccessSource
import com.berryos.audiometadata.io.WritableRandomAccessSource
import com.berryos.audiometadata.io.readFully

/** Fields of a trailing ID3v1/ID3v1.1 tag. */
public class Id3v1Tag internal constructor(
    public val title: String?,
    public val artist: String?,
    public val album: String?,
    public val year: String?,
    public val comment: String?,
    public val trackNumber: Int?,
    public val genreIndex: Int?,
)

/** Reads the fixed 128-byte ID3v1 tag stored at the end of an audio file. */
public object Id3v1Reader {
    public fun read(source: RandomAccessSource): Id3v1Tag? {
        if (source.size < TAG_SIZE) return null
        val bytes = ByteArray(TAG_SIZE)
        source.readFully(source.size - TAG_SIZE, bytes, TAG_SIZE)
        if (bytes[0] != 'T'.code.toByte() || bytes[1] != 'A'.code.toByte() || bytes[2] != 'G'.code.toByte()) {
            return null
        }

        // ID3v1.1 stores the track number in the last two comment bytes when comment[28] is zero.
        val hasTrack = bytes[COMMENT_OFFSET + COMMENT_SIZE - 2].toInt() == 0 &&
            bytes[COMMENT_OFFSET + COMMENT_SIZE - 1].toInt() != 0
        val commentLength = if (hasTrack) COMMENT_SIZE - 2 else COMMENT_SIZE
        val genreIndex = bytes[GENRE_OFFSET].toInt() and 0xFF
        return Id3v1Tag(
            title = text(bytes, TITLE_OFFSET, TITLE_SIZE),
            artist = text(bytes, ARTIST_OFFSET, ARTIST_SIZE),
            album = text(bytes, ALBUM_OFFSET, ALBUM_SIZE),
            year = text(bytes, YEAR_OFFSET, YEAR_SIZE),
            comment = text(bytes, COMMENT_OFFSET, commentLength),
            trackNumber = if (hasTrack) bytes[COMMENT_OFFSET + COMMENT_SIZE - 1].toInt() and 0xFF else null,
            genreIndex = genreIndex.takeIf { it != UNKNOWN_GENRE },
        )
    }

    private fun text(bytes: ByteArray, offset: Int, length: Int): String? {
        var end = offset + length
        while (end > offset && (bytes[end - 1].toInt() == 0 || bytes[end - 1].toInt() == ' '.code)) end--
        if (end == offset) return null
        val builder = StringBuilder(end - offset)
        for (index in offset until end) {
            builder.append((bytes[index].toInt() and 0xFF).toChar())
        }
        return builder.toString()
    }
}

/** Converts an ID3v1 tag to an editable ID3v2 tag when a file has no ID3v2 tag. */
internal fun Id3v1Tag.toEditableTag(): Id3v2Tag {
    val tag = Id3v2Tag.create()
    tag.title = title
    if (artist != null) tag.artists = listOf(artist)
    tag.album = album
    tag.year = year
    if (comment != null) tag.comments = listOf(comment)
    tag.trackNumber = trackNumber
    tag.genres = listOfNotNull(genreIndex?.let(Id3Genres::name))
    return tag
}

/** Rewrites the trailing ID3v1 tag from the current ID3v2 fields. */
internal object Id3v1Writer {
    fun write(source: WritableRandomAccessSource, tag: Id3v2Tag) {
        if (source.size < TAG_SIZE) return
        val bytes = ByteArray(TAG_SIZE)
        "TAG".encodeToByteArray().copyInto(bytes)
        writeText(bytes, TITLE_OFFSET, TITLE_SIZE, tag.title)
        writeText(bytes, ARTIST_OFFSET, ARTIST_SIZE, tag.artists.firstOrNull())
        writeText(bytes, ALBUM_OFFSET, ALBUM_SIZE, tag.album)
        writeText(bytes, YEAR_OFFSET, YEAR_SIZE, tag.year)
        val track = tag.trackNumber?.takeIf { it in MIN_TRACK..MAX_TRACK }
        val commentLength = if (track == null) COMMENT_SIZE else COMMENT_SIZE - 2
        writeText(bytes, COMMENT_OFFSET, commentLength, tag.comments.firstOrNull())
        if (track != null) {
            bytes[COMMENT_OFFSET + COMMENT_SIZE - 2] = 0
            bytes[COMMENT_OFFSET + COMMENT_SIZE - 1] = track.toByte()
        }
        bytes[GENRE_OFFSET] = (tag.genres.firstOrNull()?.let(Id3Genres::index) ?: UNKNOWN_GENRE).toByte()
        source.writeAt(source.size - TAG_SIZE, bytes)
        source.flush()
    }

    private fun writeText(bytes: ByteArray, offset: Int, length: Int, value: String?) {
        if (value == null) return
        var written = 0
        for (char in value) {
            if (written >= length) break
            bytes[offset + written++] = if (char.code <= MAX_LATIN1) char.code.toByte() else QUESTION_MARK
        }
    }
}

private const val TAG_SIZE = 128
private const val TITLE_OFFSET = 3
private const val TITLE_SIZE = 30
private const val ARTIST_OFFSET = 33
private const val ARTIST_SIZE = 30
private const val ALBUM_OFFSET = 63
private const val ALBUM_SIZE = 30
private const val YEAR_OFFSET = 93
private const val YEAR_SIZE = 4
private const val COMMENT_OFFSET = 97
private const val COMMENT_SIZE = 30
private const val GENRE_OFFSET = 127
private const val UNKNOWN_GENRE = 255
private const val MIN_TRACK = 1
private const val MAX_TRACK = 255
private const val MAX_LATIN1 = 0xFF
private const val QUESTION_MARK = '?'.code.toByte()
