package com.berryos.audiometadata

import com.berryos.audiometadata.io.ByteArraySource
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class AudioFileErrorTest {
    @Test
    fun returnsNullForUnknownData() {
        assertNull(AudioFile.read(sourceWith(ByteArray(64))))
    }

    @Test
    fun throwsForTruncatedFlac() {
        assertFailsWith<AudioMetadataException> {
            AudioFile.read(sourceWith("fLaC".encodeToByteArray()))
        }
    }

    @Test
    fun throwsForTruncatedMp4() {
        val bytes = ByteArray(4) + "ftyp".encodeToByteArray() + ByteArray(8)
        assertFailsWith<AudioMetadataException> {
            AudioFile.read(sourceWith(bytes))
        }
    }

    private fun sourceWith(bytes: ByteArray): ByteArraySource =
        ByteArraySource().also { it.writeAt(0, bytes) }
}
