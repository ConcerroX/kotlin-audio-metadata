package com.berryos.audiometadata.mp3

import com.berryos.audiometadata.AudioFile
import com.berryos.audiometadata.AudioMetadataReader
import com.berryos.audiometadata.io.ByteArraySource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class Id3v1RoundTripTest {
    @Test
    fun exposesId3v1FieldsThroughAudioFile() {
        val source = sourceWith(AUDIO + id3v1Tag("Title", "Artist", "Album", "1998", 5, 17))
        val file = assertNotNull(AudioFile.read(source))

        assertEquals("Title", file.title)
        assertEquals(listOf("Artist"), file.artists)
        assertEquals("Album", file.album)
        assertEquals("1998", file.year)
        assertEquals(5, file.trackNumber)
        assertEquals(listOf("Rock"), file.genres)
    }

    @Test
    fun writesId3v2AndUpdatesExistingId3v1() {
        val source = sourceWith(AUDIO + id3v1Tag("Title", "Artist", "Album", "1998", 5, 17))
        val file = assertNotNull(AudioFile.read(source))

        file.title = "New Title"
        file.genres = listOf("Jazz")
        file.trackNumber = 8
        file.save(source)

        val reread = assertNotNull(AudioFile.read(source))
        assertEquals("New Title", reread.title)
        assertEquals(8, reread.trackNumber)
        assertEquals(listOf("Jazz"), reread.genres)
        assertEquals("New Title", assertNotNull(AudioMetadataReader.read(source)).title)

        val bytes = ByteArray(source.size.toInt())
        source.readAt(0, bytes)
        val tail = bytes.copyOfRange(bytes.size - ID3V1_SIZE, bytes.size)
        assertEquals("New Title", latin1Text(tail, 3, 30))
        assertEquals(8, tail[126].toInt() and 0xFF)
        assertEquals(8, tail[127].toInt() and 0xFF)
    }

    private fun sourceWith(bytes: ByteArray): ByteArraySource =
        ByteArraySource().also { it.writeAt(0, bytes) }

    private fun id3v1Tag(
        title: String,
        artist: String,
        album: String,
        year: String,
        track: Int,
        genre: Int,
    ): ByteArray {
        val bytes = ByteArray(ID3V1_SIZE)
        "TAG".encodeToByteArray().copyInto(bytes)
        latin1(title).copyInto(bytes, 3)
        latin1(artist).copyInto(bytes, 33)
        latin1(album).copyInto(bytes, 63)
        latin1(year).copyInto(bytes, 93)
        bytes[125] = 0
        bytes[126] = track.toByte()
        bytes[127] = genre.toByte()
        return bytes
    }

    private fun latin1(value: String): ByteArray = ByteArray(value.length) { index -> value[index].code.toByte() }

    private fun latin1Text(bytes: ByteArray, offset: Int, length: Int): String {
        var end = offset + length
        while (end > offset && (bytes[end - 1].toInt() == 0 || bytes[end - 1].toInt() == ' '.code)) end--
        return buildString { for (index in offset until end) append((bytes[index].toInt() and 0xFF).toChar()) }
    }

    private companion object {
        val AUDIO = ByteArray(256) { index -> (index * 7).toByte() }
        const val ID3V1_SIZE = 128
    }
}
