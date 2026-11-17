package com.berryos.audiometadata.vorbis

import com.berryos.audiometadata.Artwork
import com.berryos.audiometadata.AudioFile
import com.berryos.audiometadata.AudioFormat
import com.berryos.audiometadata.ExperimentalAudioMetadataApi
import com.berryos.audiometadata.encodeBase64
import com.berryos.audiometadata.flacPicture
import com.berryos.audiometadata.io.ByteArraySource
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalAudioMetadataApi::class)
class AudioFileOggTest {
    @Test
    fun readsVorbisFields() {
        val file = assertNotNull(AudioFile.read(sourceWith(vorbisFile("Song", "Artist"))))

        assertEquals(AudioFormat.Ogg, file.format)
        assertEquals("Song", file.title)
        assertEquals(listOf("Artist"), file.artists)
        assertEquals(44_100, file.audioInfo?.sampleRateHz)
        assertNull(file.artworks)
    }

    @Test
    fun savesHighAndLowLevelEdits() {
        val source = sourceWith(vorbisFile("Song", "Artist"))
        val file = assertNotNull(AudioFile.read(source))

        file.title = "Edited"
        file.vorbis?.add("MUSICBRAINZ_TRACKID", "track-id")
        file.save(source)

        val reread = assertNotNull(AudioFile.read(source))
        assertEquals("Edited", reread.title)
        assertEquals("track-id", assertNotNull(reread.vorbis).value("MUSICBRAINZ_TRACKID"))
        assertContentEquals(AUDIO_TAIL, audioTail(source))
    }

    @Test
    fun readsAndWritesLyrics() {
        val source = sourceWith(vorbisFile("Song", "Artist"))
        val file = assertNotNull(AudioFile.read(source))

        file.lyrics = "Words"
        file.save(source)

        val reread = assertNotNull(AudioFile.read(source))
        assertEquals("Words", reread.lyrics)
        assertEquals("Words", assertNotNull(reread.vorbis).value("LYRICS"))
        assertContentEquals(AUDIO_TAIL, audioTail(source))
    }

    @Test
    fun preservesPicturesThatWereNotLoaded() {
        val image = "PNG-DATA".encodeToByteArray()
        val tag = VorbisCommentTag.create().apply {
            title = "Song"
            set("METADATA_BLOCK_PICTURE", encodeBase64(flacPicture(image)))
        }
        val source = sourceWith(vorbisFile(tag))
        val file = assertNotNull(AudioFile.read(source))
        assertNull(file.artworks)

        file.title = "Edited"
        file.save(source)

        val reread = assertNotNull(AudioFile.read(source, includeArtworks = true))
        val artworks = assertNotNull(reread.artworks)
        assertEquals(1, artworks.size)
        assertContentEquals(image, artworks.first().data)
        assertContentEquals(AUDIO_TAIL, audioTail(source))
    }

    @Test
    fun replacesArtworks() {
        val source = sourceWith(vorbisFile("Song", "Artist"))
        val file = assertNotNull(AudioFile.read(source, includeArtworks = true))
        assertTrue(assertNotNull(file.artworks).isEmpty())

        val image = "NEW-PNG".encodeToByteArray()
        file.artworks = listOf(Artwork(image, "image/png", 3))
        file.save(source)

        val reread = assertNotNull(AudioFile.read(source, includeArtworks = true))
        assertContentEquals(image, assertNotNull(reread.artworks).first().data)
    }

    private fun sourceWith(bytes: ByteArray): ByteArraySource =
        ByteArraySource().also { it.writeAt(0, bytes) }

    private fun audioTail(source: ByteArraySource): ByteArray {
        val bytes = source.toByteArray()
        return bytes.copyOfRange(bytes.size - AUDIO_TAIL.size, bytes.size)
    }
}

private val AUDIO_TAIL = "AUDIO-PAYLOAD-MARKER".encodeToByteArray()
