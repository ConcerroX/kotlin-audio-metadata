package com.berryos.audiometadata

import com.berryos.audiometadata.io.ByteArraySource
import com.berryos.audiometadata.mp4.mp4File
import com.berryos.audiometadata.vorbis.vorbisFile
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class AudioMetadataReaderTest {
    @Test
    fun readsArtworkThroughUnifiedReader() {
        val image = byteArrayOf(9, 8, 7, 6)
        val source = ByteArraySource().also { it.writeAt(0, mp4File(artwork = image)) }

        val artworks = AudioArtworkReader.read(source)

        assertEquals(1, artworks.size)
        assertContentEquals(image, artworks.first().data)
    }

    @Test
    fun readsOggVorbisThroughUnifiedReader() {
        val source = ByteArraySource().also { it.writeAt(0, vorbisFile("Title", "Artist")) }

        val metadata = assertNotNull(AudioMetadataReader.read(source))

        assertEquals(AudioFormat.OGG, metadata.format)
        assertEquals("Title", metadata.title)
        assertEquals(listOf("Artist"), metadata.artists)
    }

    @Test
    fun fallsBackToId3v1WhenNoId3v2HeaderExists() {
        val audio = ByteArray(256) { it.toByte() }
        val source = ByteArraySource().also { it.writeAt(0, audio + id3v1Tag("Title", "Artist")) }

        val metadata = assertNotNull(AudioMetadataReader.read(source))

        assertEquals("Title", metadata.title)
        assertEquals(listOf("Artist"), metadata.artists)
    }

    @Test
    fun returnsNullForUnknownData() {
        val source = ByteArraySource().also { it.writeAt(0, ByteArray(64)) }

        assertEquals(null, AudioMetadataReader.read(source))
    }
}

private fun id3v1Tag(title: String, artist: String): ByteArray {
    val bytes = ByteArray(128)
    "TAG".encodeToByteArray().copyInto(bytes)
    title.encodeToByteArray().copyInto(bytes, 3)
    artist.encodeToByteArray().copyInto(bytes, 33)
    return bytes
}
