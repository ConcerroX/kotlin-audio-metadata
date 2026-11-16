package com.berryos.audiometadata.id3

import com.berryos.audiometadata.io.ByteArraySource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class Id3v1ReaderTest {
    @Test
    fun readsV10Tag() {
        val tag = read(
            id3v1File(
                title = "Song",
                artist = "Artist",
                album = "Album",
                year = "1997",
                comment = "Comment",
                genre = 17,
            ),
        )

        assertEquals("Song", tag.title)
        assertEquals("Artist", tag.artist)
        assertEquals("Album", tag.album)
        assertEquals("1997", tag.year)
        assertEquals("Comment", tag.comment)
        assertNull(tag.trackNumber)
        assertEquals(17, tag.genreIndex)
    }

    @Test
    fun readsV11TrackNumber() {
        val tag = read(id3v1File(title = "Song", comment = "Comment", track = 7))

        assertEquals("Comment", tag.comment)
        assertEquals(7, tag.trackNumber)
        assertNull(tag.genreIndex)
    }

    @Test
    fun returnsNullWhenTagIsMissing() {
        val source = ByteArraySource().also { it.writeAt(0, ByteArray(160)) }
        assertNull(Id3v1Reader.read(source))
    }

    private fun read(bytes: ByteArray): Id3v1Tag {
        val source = ByteArraySource().also { it.writeAt(0, bytes) }
        return assertNotNull(Id3v1Reader.read(source))
    }
}

internal fun id3v1File(
    title: String = "",
    artist: String = "",
    album: String = "",
    year: String = "",
    comment: String = "",
    track: Int? = null,
    genre: Int = 255,
): ByteArray {
    val bytes = ByteArray(128)
    "TAG".encodeToByteArray().copyInto(bytes)
    latin1Bytes(title).copyInto(bytes, 3, endIndex = minOf(title.length, 30))
    latin1Bytes(artist).copyInto(bytes, 33, endIndex = minOf(artist.length, 30))
    latin1Bytes(album).copyInto(bytes, 63, endIndex = minOf(album.length, 30))
    latin1Bytes(year).copyInto(bytes, 93, endIndex = minOf(year.length, 4))
    if (track == null) {
        latin1Bytes(comment).copyInto(bytes, 97, endIndex = minOf(comment.length, 30))
    } else {
        latin1Bytes(comment).copyInto(bytes, 97, endIndex = minOf(comment.length, 28))
        bytes[125] = 0
        bytes[126] = track.toByte()
    }
    bytes[127] = genre.toByte()
    return ByteArray(32) + bytes
}
