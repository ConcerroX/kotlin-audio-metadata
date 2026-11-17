package com.berryos.audiometadata.flac

import com.berryos.audiometadata.Artwork
import com.berryos.audiometadata.AudioFile
import com.berryos.audiometadata.AudioFormat
import com.berryos.audiometadata.ExperimentalAudioMetadataApi
import com.berryos.audiometadata.flacPicture
import com.berryos.audiometadata.io.ByteArraySource
import com.berryos.audiometadata.vorbis.VorbisCommentTag
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalAudioMetadataApi::class)
class AudioFileFlacTest {
    @Test
    fun readsHighLevelFields() {
        val tag = VorbisCommentTag.create().apply {
            title = "Song"
            add("ARTIST", "Artist")
            add("ARTIST", "Guest")
            album = "Album"
            set("TRACKNUMBER", "3/12")
            set("DISCNUMBER", "1/2")
            date = "1999"
            genre = "Rock"
            set("REPLAYGAIN_TRACK_GAIN", "-6.53 dB")
        }
        val file = assertNotNull(AudioFile.read(sourceWith(buildFlac(tag), AUDIO)))

        assertEquals(AudioFormat.FLAC, file.format)
        assertEquals("Song", file.title)
        assertEquals(listOf("Artist", "Guest"), file.artists)
        assertEquals("Album", file.album)
        assertEquals(3, file.trackNumber)
        assertEquals(12, file.trackTotal)
        assertEquals(1, file.discNumber)
        assertEquals(2, file.discTotal)
        assertEquals("1999", file.year)
        assertEquals(listOf("Rock"), file.genres)
        assertEquals("-6.53 dB", file.replayGain?.trackGain)
        assertEquals(44_100, file.audioInfo?.sampleRateHz)
        assertNull(file.artworks)
    }

    @Test
    fun highAndLowLevelViewsShareState() {
        val source = sourceWith(buildFlac(VorbisCommentTag.create().apply { title = "Song" }), AUDIO)
        val file = assertNotNull(AudioFile.read(source))
        val vorbis = assertNotNull(file.vorbis)

        file.title = "Edited"
        assertEquals("Edited", vorbis.value("TITLE"))

        vorbis.add("MUSICBRAINZ_TRACKID", "track-id")
        file.save(source)

        val reread = assertNotNull(AudioFile.read(source))
        assertEquals("Edited", reread.title)
        assertEquals("track-id", assertNotNull(reread.vorbis).value("MUSICBRAINZ_TRACKID"))
        assertContentEquals(AUDIO, audioBytes(source))
    }

    @Test
    fun readsAndWritesLyrics() {
        val source = sourceWith(buildFlac(VorbisCommentTag.create().apply { title = "Song" }), AUDIO)
        val file = assertNotNull(AudioFile.read(source))

        file.lyrics = "First line\nSecond line"
        file.save(source)

        val reread = assertNotNull(AudioFile.read(source))
        assertEquals("First line\nSecond line", reread.lyrics)
        assertEquals("First line\nSecond line", assertNotNull(reread.vorbis).value("LYRICS"))
    }

    @Test
    fun readsLegacyUnsyncedLyricsKey() {
        val tag = VorbisCommentTag.create().apply {
            title = "Song"
            set("UNSYNCEDLYRICS", "Legacy words")
        }
        val file = assertNotNull(AudioFile.read(sourceWith(buildFlac(tag), AUDIO)))

        assertEquals("Legacy words", file.lyrics)
    }

    @Test
    fun preservesPicturesThatWereNotLoaded() {
        val image = "PNG-DATA".encodeToByteArray()
        val tag = VorbisCommentTag.create().apply { title = "Song" }
        val source = sourceWith(
            buildFlac(tag, extra = listOf(TYPE_PICTURE to flacPicture(image))),
            AUDIO,
        )
        val file = assertNotNull(AudioFile.read(source))
        assertNull(file.artworks)

        file.title = "Edited"
        file.save(source)

        val reread = assertNotNull(AudioFile.read(source, includeArtworks = true))
        val artworks = assertNotNull(reread.artworks)
        assertEquals(1, artworks.size)
        assertContentEquals(image, artworks.first().data)
        assertContentEquals(AUDIO, audioBytes(source))
    }

    @Test
    fun replacesAndClearsArtworks() {
        val source = sourceWith(buildFlac(VorbisCommentTag.create().apply { title = "Song" }), AUDIO)
        val file = assertNotNull(AudioFile.read(source, includeArtworks = true))
        assertTrue(assertNotNull(file.artworks).isEmpty())

        val image = "NEW-PNG".encodeToByteArray()
        file.artworks = listOf(Artwork(image, "image/png", 3))
        file.save(source)

        val reread = assertNotNull(AudioFile.read(source, includeArtworks = true))
        val artworks = assertNotNull(reread.artworks)
        assertEquals(1, artworks.size)
        assertContentEquals(image, artworks.first().data)

        reread.artworks = emptyList()
        reread.save(source)

        val cleared = assertNotNull(AudioFile.read(source, includeArtworks = true))
        assertTrue(assertNotNull(cleared.artworks).isEmpty())
        assertContentEquals(AUDIO, audioBytes(source))
    }

    @Test
    fun editsNumbersAndMultiValueFields() {
        val source = sourceWith(
            buildFlac(VorbisCommentTag.create().apply { set("TRACKNUMBER", "3/12") }),
            AUDIO,
        )
        val file = assertNotNull(AudioFile.read(source))

        file.artists = listOf("A", "B")
        file.genres = listOf("Rock", "Pop")
        file.trackNumber = 4
        file.trackTotal = 13
        file.save(source)

        val reread = assertNotNull(AudioFile.read(source))
        assertEquals(listOf("A", "B"), reread.artists)
        assertEquals(listOf("Rock", "Pop"), reread.genres)
        assertEquals(4, reread.trackNumber)
        assertEquals(13, reread.trackTotal)
    }

    @Test
    fun returnsNullForNonFlacData() {
        assertNull(AudioFile.read(sourceWith(ByteArray(64), AUDIO)))
    }

    private fun sourceWith(metadata: ByteArray, audio: ByteArray): ByteArraySource =
        ByteArraySource().also { it.writeAt(0, metadata + audio) }

    private fun audioBytes(source: ByteArraySource): ByteArray {
        val bytes = source.toByteArray()
        return bytes.copyOfRange(bytes.size - AUDIO.size, bytes.size)
    }
}
