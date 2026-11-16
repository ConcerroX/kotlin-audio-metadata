package com.berryos.audiometadata.id3

import com.berryos.audiometadata.io.RandomAccessSource
import com.berryos.audiometadata.io.readFully

/** Fields of a trailing ID3v1/ID3v1.1 tag. */
class Id3v1Tag internal constructor(
    val title: String?,
    val artist: String?,
    val album: String?,
    val year: String?,
    val comment: String?,
    val trackNumber: Int?,
    val genreIndex: Int?,
)

/** Reads the fixed 128-byte ID3v1 tag stored at the end of an audio file. */
object Id3v1Reader {
    fun read(source: RandomAccessSource): Id3v1Tag? {
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
}
