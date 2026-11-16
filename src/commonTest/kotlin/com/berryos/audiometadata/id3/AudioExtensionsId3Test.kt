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
        file.save(source)

        val reread = assertNotNull(AudioFile.read(source))
        assertEquals("Song, A", reread.extensions.titleSort)
        assertEquals("Artist, The", reread.extensions.artistSort)
        assertEquals("Album, The", reread.extensions.albumSort)
        assertEquals("Artist, The", reread.extensions.albumArtistSort)
        assertEquals(true, reread.extensions.compilation)
        assertEquals(128, reread.extensions.bpm)
        assertEquals("US-XXX-12-34567", reread.extensions.isrc)
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

    private fun sourceWith(metadata: ByteArray): ByteArraySource =
        ByteArraySource().also { it.writeAt(0, metadata + AUDIO_TAIL) }

    private fun audioTail(source: ByteArraySource): ByteArray {
        val bytes = source.toByteArray()
        return bytes.copyOfRange(bytes.size - AUDIO_TAIL.size, bytes.size)
    }
}

private val AUDIO_TAIL = ByteArray(256) { index -> (index * 11 % 251).toByte() }
