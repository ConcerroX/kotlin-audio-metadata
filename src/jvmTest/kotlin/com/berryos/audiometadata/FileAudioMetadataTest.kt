package com.berryos.audiometadata

import com.berryos.audiometadata.mp4.mp4File
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileAudioMetadataTest {
    @Test
    fun readsAndUpdatesExistingFile() = withTempDirectory { directory ->
        val file = File(directory, "test.m4a").apply { writeBytes(mp4File()) }

        val audio = assertNotNull(file.readAudioFile())
        assertEquals("Song", audio.title)
        assertEquals("Song", assertNotNull(file.readAudioMetadata()).title)

        assertTrue(file.updateAudioFile { it.title = "New title" })
        assertEquals("New title", assertNotNull(file.readAudioFile()).title)
    }

    @Test
    fun ignoresMissingAndUnknownFiles() = withTempDirectory { directory ->
        val missing = File(directory, "missing.m4a")
        assertNull(missing.readAudioFile())
        assertFalse(missing.updateAudioFile { it.title = "Ignored" })

        val unknown = File(directory, "unknown.bin").apply { writeBytes(ByteArray(32) { 0x55 }) }
        assertNull(unknown.readAudioFile())
        assertFalse(unknown.updateAudioFile { it.title = "Ignored" })
    }

    private fun withTempDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("audio-file").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}
