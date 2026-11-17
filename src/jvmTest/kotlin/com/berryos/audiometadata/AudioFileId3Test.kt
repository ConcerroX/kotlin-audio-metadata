package com.berryos.audiometadata

import com.berryos.audiometadata.aiff.AiffMetadata
import com.berryos.audiometadata.aiff.AiffMetadataReader
import com.berryos.audiometadata.io.FileChannelSource
import com.berryos.audiometadata.io.RandomAccessSource
import com.berryos.audiometadata.mp3.Mp3Metadata
import com.berryos.audiometadata.mp3.Mp3MetadataReader
import com.berryos.audiometadata.wav.WavMetadata
import com.berryos.audiometadata.wav.WavMetadataReader
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@OptIn(ExperimentalAudioMetadataApi::class)
class AudioFileId3Test {
    @Test
    fun mp3EditsAndKeepsAudio() = withFixture("test23.mp3") { copy ->
        val source = FileChannelSource(copy)
        try {
            val file = assertNotNull(AudioFile.read(source))
            assertEquals(AudioFormat.MP3, file.format)
            assertNotNull(file.id3)
            val mp3Before = assertNotNull(Mp3MetadataReader.read(source))
            val tailBefore = readRange(source, mp3Before.audioStartOffset, audioLength(source, mp3Before))

            file.title = MARKER
            file.replayGain = ReplayGain(trackGain = "-6.00 dB", trackPeak = "0.98")
            file.save(source)

            val reread = assertNotNull(AudioFile.read(source))
            assertEquals(MARKER, reread.title)
            assertEquals("-6.00 dB", reread.replayGain?.trackGain)
            assertEquals("0.98", reread.replayGain?.trackPeak)
            val mp3After = assertNotNull(Mp3MetadataReader.read(source))
            assertContentEquals(tailBefore, readRange(source, mp3After.audioStartOffset, audioLength(source, mp3After)))
        } finally {
            source.close()
        }
    }

    @Test
    fun wavEditsAndKeepsData() = withFixture("test.wav") { copy ->
        val source = FileChannelSource(copy)
        try {
            val before = assertNotNull(WavMetadataReader.read(source))
            val data = chunkPayload(source, before, "data")
            val file = assertNotNull(AudioFile.read(source))
            assertEquals(AudioFormat.WAV, file.format)

            file.title = MARKER
            file.artists = listOf("First", "Second")
            file.save(source)

            val reread = assertNotNull(AudioFile.read(source))
            assertEquals(MARKER, reread.title)
            assertEquals(listOf("First", "Second"), reread.artists)
            assertContentEquals(data, chunkPayload(source, assertNotNull(WavMetadataReader.read(source)), "data"))
        } finally {
            source.close()
        }
    }

    @Test
    fun aiffEditsAndKeepsSoundData() = withFixture("test119.aif") { copy ->
        val source = FileChannelSource(copy)
        try {
            val before = assertNotNull(AiffMetadataReader.read(source))
            val sound = chunkPayload(source, before, "SSND")
            val file = assertNotNull(AudioFile.read(source))
            assertEquals(AudioFormat.AIFF, file.format)

            file.title = MARKER
            file.save(source)

            val reread = assertNotNull(AudioFile.read(source))
            assertEquals(MARKER, reread.title)
            assertContentEquals(sound, chunkPayload(source, assertNotNull(AiffMetadataReader.read(source)), "SSND"))
        } finally {
            source.close()
        }
    }

    private fun chunkPayload(source: RandomAccessSource, metadata: WavMetadata, id: String): ByteArray {
        val chunk = metadata.chunks.first { it.id == id }
        return readRange(source, chunk.payloadOffset, chunk.size.toInt())
    }

    private fun chunkPayload(source: RandomAccessSource, metadata: AiffMetadata, id: String): ByteArray {
        val chunk = metadata.chunks.first { it.id == id }
        return readRange(source, chunk.payloadOffset, chunk.size.toInt())
    }

    private fun readRange(source: RandomAccessSource, offset: Long, count: Int): ByteArray {
        val bytes = ByteArray(count)
        var read = 0
        while (read < count) {
            read += source.readAt(offset + read, bytes, read, count - read)
        }
        return bytes
    }

    private fun audioLength(source: RandomAccessSource, metadata: Mp3Metadata): Int =
        (source.size - metadata.audioStartOffset - if (metadata.id3v1 != null) ID3V1_SIZE.toLong() else 0L).toInt()

    private fun withFixture(name: String, block: (File) -> Unit) {
        val fixture = File(TESTDATA, name)
        assumeTrue("$name is not available", fixture.isFile)
        val directory = Files.createTempDirectory("audio-file").toFile()
        try {
            val copy = File(directory, name)
            fixture.copyTo(copy, overwrite = true)
            block(copy)
        } finally {
            directory.deleteRecursively()
        }
    }
}

private val TESTDATA = File("testdata")
private const val MARKER = "kotlin-audio-metadata"
private const val ID3V1_SIZE = 128
