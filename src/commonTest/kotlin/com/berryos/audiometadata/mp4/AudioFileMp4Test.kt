package com.berryos.audiometadata.mp4

import com.berryos.audiometadata.Artwork
import com.berryos.audiometadata.AudioFile
import com.berryos.audiometadata.AudioFormat
import com.berryos.audiometadata.ExperimentalAudioMetadataApi
import com.berryos.audiometadata.io.ByteArraySource
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalAudioMetadataApi::class)
class AudioFileMp4Test {
    @Test
    fun readsItunesFields() {
        val file = assertNotNull(AudioFile.read(sourceWith(mp4File())))

        assertEquals(AudioFormat.MP4, file.format)
        assertEquals("Song", file.title)
        assertEquals(listOf("Artist"), file.artists)
        assertEquals("Album", file.album)
        assertEquals("Album Artist", file.albumArtist)
        assertEquals(3, file.trackNumber)
        assertEquals(12, file.trackTotal)
        assertEquals(1, file.discNumber)
        assertEquals(2, file.discTotal)
        assertEquals("1999", file.year)
        assertEquals(listOf("Rock"), file.genres)
        assertEquals(44_100, file.audioInfo?.sampleRateHz)
        assertNull(file.artworks)
        assertNotNull(file.mp4)
    }

    @Test
    fun savesEditsAndKeepsSamples() {
        val source = sourceWith(mp4File())
        val file = assertNotNull(AudioFile.read(source))
        val offsetsBefore = readChunkOffsets(source)
        val samples = offsetsBefore.map { offset -> readAt(source, offset, 16) }

        file.title = "A much longer title than before"
        file.artists = listOf("New Artist")
        file.save(source)

        val reread = assertNotNull(AudioFile.read(source))
        assertEquals("A much longer title than before", reread.title)
        assertEquals(listOf("New Artist"), reread.artists)
        assertEquals("Album", reread.album)
        val offsetsAfter = readChunkOffsets(source)
        assertEquals(offsetsBefore.size, offsetsAfter.size)
        samples.forEachIndexed { index, bytes ->
            assertContentEquals(bytes, readAt(source, offsetsAfter[index], bytes.size))
        }
    }

    @Test
    fun preservesPicturesThatWereNotLoaded() {
        val image = "JPEG-DATA".encodeToByteArray()
        val source = sourceWith(mp4File(artwork = image))
        val file = assertNotNull(AudioFile.read(source))
        assertNull(file.artworks)

        file.title = "Edited"
        file.save(source)

        val reread = assertNotNull(AudioFile.read(source, includeArtworks = true))
        val artworks = assertNotNull(reread.artworks)
        assertEquals(1, artworks.size)
        assertContentEquals(image, artworks.first().data)
    }

    @Test
    fun replacesArtworks() {
        val source = sourceWith(mp4File())
        val file = assertNotNull(AudioFile.read(source, includeArtworks = true))
        assertTrue(assertNotNull(file.artworks).isEmpty())

        val image = "NEW-PNG".encodeToByteArray()
        file.artworks = listOf(Artwork(image, "image/png", 3))
        file.save(source)

        val reread = assertNotNull(AudioFile.read(source, includeArtworks = true))
        assertContentEquals(image, assertNotNull(reread.artworks).first().data)
    }

    @Test
    fun readsAndWritesLyrics() {
        val source = sourceWith(mp4File(extraItems = textItem("©lyr", "First line\nSecond line")))
        val file = assertNotNull(AudioFile.read(source))
        assertEquals("First line\nSecond line", file.lyrics)

        file.lyrics = "New lyrics"
        file.save(source)

        val reread = assertNotNull(AudioFile.read(source))
        assertEquals("New lyrics", reread.lyrics)
        assertEquals("New lyrics", reread.toAudioMetadata().lyrics)
    }

    private fun sourceWith(bytes: ByteArray): ByteArraySource =
        ByteArraySource().also { it.writeAt(0, bytes) }

    private fun readAt(source: ByteArraySource, offset: Long, count: Int): ByteArray {
        val bytes = ByteArray(count)
        var read = 0
        while (read < count) {
            read += source.readAt(offset + read, bytes, read, count - read)
        }
        return bytes
    }
}
