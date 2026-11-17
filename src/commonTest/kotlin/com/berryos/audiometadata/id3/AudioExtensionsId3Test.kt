package com.berryos.audiometadata.id3

import com.berryos.audiometadata.AudioFile
import com.berryos.audiometadata.ExperimentalAudioMetadataApi
import com.berryos.audiometadata.io.ByteArraySource
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(ExperimentalAudioMetadataApi::class)
class AudioExtensionsId3Test {
    @Test
    fun readsAndWritesCommonExtensions() {
        val source = sourceWith(buildTag(3, frames = listOf(textFrame(3, "TIT2", 0, "Song"))))
        val file = assertNotNull(AudioFile.read(source))

        file.extensions.titleSort = "Song, A"
        file.extensions.artistSort = "Artist, The"
        file.extensions.albumSort = "Album, The"
        file.extensions.albumArtistSort = "Artist, The"
        file.extensions.compilation = true
        file.extensions.bpm = 128
        file.extensions.isrc = "US-XXX-12-34567"
        file.extensions.originalDate = "1990-01-01"
        file.extensions.r128TrackGain = "-1500"
        file.extensions.r128AlbumGain = "-1600"
        file.save(source)

        val reread = assertNotNull(AudioFile.read(source))
        assertEquals("Song, A", reread.extensions.titleSort)
        assertEquals("Artist, The", reread.extensions.artistSort)
        assertEquals("Album, The", reread.extensions.albumSort)
        assertEquals("Artist, The", reread.extensions.albumArtistSort)
        assertEquals(true, reread.extensions.compilation)
        assertEquals(128, reread.extensions.bpm)
        assertEquals("US-XXX-12-34567", reread.extensions.isrc)
        assertEquals("1990-01-01", reread.extensions.originalDate)
        assertEquals("-1500", reread.extensions.r128TrackGain)
        assertEquals("-1600", reread.extensions.r128AlbumGain)
        assertContentEquals(AUDIO_TAIL, audioTail(source))
    }

    @Test
    fun removesExtensionsWhenCleared() {
        val source = sourceWith(
            buildTag(
                4,
                frames = listOf(
                    textFrame(4, "TIT2", 3, "Song"),
                    textFrame(4, "TSOT", 3, "Sorted"),
                    textFrame(4, "TCMP", 3, "1"),
                ),
            ),
        )
        val file = assertNotNull(AudioFile.read(source))
        assertEquals("Sorted", file.extensions.titleSort)
        assertEquals(true, file.extensions.compilation)

        file.extensions.titleSort = null
        file.extensions.compilation = null
        file.save(source)

        val reread = assertNotNull(AudioFile.read(source))
        assertNull(reread.extensions.titleSort)
        assertNull(reread.extensions.compilation)
    }

    @Test
    fun readsAndWritesMusicBrainzIds() {
        val source = sourceWith(buildTag(3, frames = listOf(textFrame(3, "TIT2", 0, "Song"))))
        val file = assertNotNull(AudioFile.read(source))

        file.extensions.musicBrainz.recordingId = TRACK_ID
        file.extensions.musicBrainz.releaseTrackId = RELEASE_TRACK_ID
        file.extensions.musicBrainz.albumId = ALBUM_ID
        file.extensions.musicBrainz.artistId = ARTIST_ID
        file.extensions.musicBrainz.albumArtistId = ALBUM_ARTIST_ID
        file.extensions.musicBrainz.releaseGroupId = RELEASE_GROUP_ID
        file.extensions.musicBrainz.workId = WORK_ID
        file.save(source)

        val musicBrainz = assertNotNull(AudioFile.read(source)).extensions.musicBrainz
        assertEquals(TRACK_ID, musicBrainz.recordingId)
        assertEquals(RELEASE_TRACK_ID, musicBrainz.releaseTrackId)
        assertEquals(ALBUM_ID, musicBrainz.albumId)
        assertEquals(ARTIST_ID, musicBrainz.artistId)
        assertEquals(ALBUM_ARTIST_ID, musicBrainz.albumArtistId)
        assertEquals(RELEASE_GROUP_ID, musicBrainz.releaseGroupId)
        assertEquals(WORK_ID, musicBrainz.workId)
    }

    private fun sourceWith(metadata: ByteArray): ByteArraySource =
        ByteArraySource().also { it.writeAt(0, metadata + AUDIO_TAIL) }

    private fun audioTail(source: ByteArraySource): ByteArray {
        val bytes = source.toByteArray()
        return bytes.copyOfRange(bytes.size - AUDIO_TAIL.size, bytes.size)
    }
}

private val AUDIO_TAIL = ByteArray(256) { index -> (index * 11 % 251).toByte() }

private const val TRACK_ID = "11111111-1111-1111-1111-111111111111"
private const val RELEASE_TRACK_ID = "22222222-2222-2222-2222-222222222222"
private const val ALBUM_ID = "33333333-3333-3333-3333-333333333333"
private const val ARTIST_ID = "44444444-4444-4444-4444-444444444444"
private const val ALBUM_ARTIST_ID = "55555555-5555-5555-5555-555555555555"
private const val RELEASE_GROUP_ID = "66666666-6666-6666-6666-666666666666"
private const val WORK_ID = "77777777-7777-7777-7777-777777777777"
