package com.berryos.audiometadata.opus

import com.berryos.audiometadata.AudioFile
import com.berryos.audiometadata.AudioFormat
import com.berryos.audiometadata.ExperimentalAudioMetadataApi
import com.berryos.audiometadata.io.ByteArraySource
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@OptIn(ExperimentalAudioMetadataApi::class)
class AudioFileOpusTest {
    @Test
    fun readsAndSavesOpusTags() {
        val source = ByteArraySource().also { it.writeAt(0, opusFile("Song", "Artist")) }
        val file = assertNotNull(AudioFile.read(source))

        assertEquals(AudioFormat.Opus, file.format)
        assertEquals("Song", file.title)
        assertEquals(listOf("Artist"), file.artists)
        assertEquals(48_000, file.audioInfo?.sampleRateHz)

        file.title = "Edited"
        file.vorbis?.add("MUSICBRAINZ_TRACKID", "track-id")
        file.save(source)

        val reread = assertNotNull(AudioFile.read(source))
        assertEquals("Edited", reread.title)
        assertEquals("track-id", assertNotNull(reread.vorbis).value("MUSICBRAINZ_TRACKID"))
        val bytes = source.toByteArray()
        assertContentEquals(AUDIO_TAIL, bytes.copyOfRange(bytes.size - AUDIO_TAIL.size, bytes.size))
    }
}

private val AUDIO_TAIL = "AUDIO-PAYLOAD-MARKER".encodeToByteArray()
