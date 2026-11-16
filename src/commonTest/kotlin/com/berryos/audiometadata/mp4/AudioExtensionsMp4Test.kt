package com.berryos.audiometadata.mp4

import com.berryos.audiometadata.AudioFile
import com.berryos.audiometadata.ExperimentalAudioMetadataApi
import com.berryos.audiometadata.io.ByteArraySource
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(ExperimentalAudioMetadataApi::class)
class AudioExtensionsMp4Test {
    @Test
    fun readsAndWritesCommonExtensions() {
        val source = sourceWith(mp4File())
        val file = assertNotNull(AudioFile.read(source))
        val offsetsBefore = readChunkOffsets(source)
        val samples = offsetsBefore.map { offset -> readAt(source, offset, 16) }

        file.extensions.titleSort = "Song, A"
        file.extensions.artistSort = "Artist, The"
        file.extensions.albumSort = "Album, The"
        file.extensions.albumArtistSort = "Artist, The"
        file.extensions.compilation = true
        file.extensions.bpm = 128
        file.save(source)

        val reread = assertNotNull(AudioFile.read(source))
        assertEquals("Song, A", reread.extensions.titleSort)
        assertEquals("Artist, The", reread.extensions.artistSort)
        assertEquals("Album, The", reread.extensions.albumSort)
        assertEquals("Artist, The", reread.extensions.albumArtistSort)
        assertEquals(true, reread.extensions.compilation)
        assertEquals(128, reread.extensions.bpm)
        val offsetsAfter = readChunkOffsets(source)
        samples.forEachIndexed { index, bytes ->
            assertContentEquals(bytes, readAt(source, offsetsAfter[index], bytes.size))
        }
    }

    @Test
    fun removesExtensionsWhenCleared() {
        val source = sourceWith(
            mp4File(
                titleSort = "Sorted",
                compilation = true,
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
