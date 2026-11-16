package com.berryos.audiometadata.flac

import com.berryos.audiometadata.AudioFile
import com.berryos.audiometadata.ExperimentalAudioMetadataApi
import com.berryos.audiometadata.io.ByteArraySource
import com.berryos.audiometadata.vorbis.VorbisCommentTag
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(ExperimentalAudioMetadataApi::class)
class AudioExtensionsFlacTest {
    @Test
    fun readsAndWritesCommonExtensions() {
        val source = sourceWith(buildFlac(VorbisCommentTag.create().apply { title = "Song" }))
        val file = assertNotNull(AudioFile.read(source))

        file.extensions.titleSort = "Song, A"
        file.extensions.artistSort = "Artist, The"
        file.extensions.albumSort = "Album, The"
        file.extensions.albumArtistSort = "Artist, The"
        file.extensions.compilation = true
        file.extensions.bpm = 128
        file.extensions.isrc = "US-XXX-12-34567"
        file.save(source)

        val reread = assertNotNull(AudioFile.read(source))
        assertEquals("Song, A", reread.extensions.titleSort)
        assertEquals("Artist, The", reread.extensions.artistSort)
        assertEquals("Album, The", reread.extensions.albumSort)
        assertEquals("Artist, The", reread.extensions.albumArtistSort)
        assertEquals(true, reread.extensions.compilation)
        assertEquals(128, reread.extensions.bpm)
        assertEquals("US-XXX-12-34567", reread.extensions.isrc)
        assertContentEquals(AUDIO, audioTail(source))
    }

    @Test
    fun removesExtensionsWhenCleared() {
        val source = sourceWith(
            buildFlac(
                VorbisCommentTag.create().apply {
                    set("TITLESORT", "Sorted")
                    set("COMPILATION", "1")
                },
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

    private fun sourceWith(metadata: ByteArray): ByteArraySource =
        ByteArraySource().also { it.writeAt(0, metadata + AUDIO) }

    private fun audioTail(source: ByteArraySource): ByteArray {
        val bytes = source.toByteArray()
        return bytes.copyOfRange(bytes.size - AUDIO.size, bytes.size)
    }
}
