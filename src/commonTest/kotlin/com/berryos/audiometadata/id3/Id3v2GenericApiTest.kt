package com.berryos.audiometadata.id3

import com.berryos.audiometadata.Id3Version
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class Id3v2GenericApiTest {
    @Test
    fun writesFramesWithoutTypedProperties() {
        val tag = read(buildTag(version = 3, frames = listOf(frame(3, "TIT2", text("Title")))))

        tag.setTextFrame("TPUB", listOf("Publisher"))
        tag.setFrame("PRIV", byteArrayOf(1, 2, 3))

        val reread = read(Id3v2Writer.encode(tag))
        assertEquals("Publisher", reread.firstText("TPUB"))
        assertEquals(listOf(1, 2, 3), reread.framesOf("PRIV").single().content.map { it.toInt() })
    }

    @Test
    fun replacesAndRemovesFrames() {
        val tag = read(buildTag(version = 4, frames = listOf(frame(4, "TPUB", text("Old")))))

        tag.setTextFrame("TPUB", listOf("New"))
        assertEquals("New", tag.firstText("TPUB"))

        tag.removeFrame("TPUB")
        assertTrue(tag.framesOf("TPUB").isEmpty())
        assertTrue(read(Id3v2Writer.encode(tag)).framesOf("TPUB").isEmpty())
    }

    @Test
    fun addsRepeatedTextFrames() {
        val tag = read(buildTag(version = 4, frames = emptyList()))

        tag.addFrame("TPE1", text("One"))
        tag.addFrame("TPE1", text("Two"))

        assertEquals(listOf("One", "Two"), read(Id3v2Writer.encode(tag)).artists)
    }

    @Test
    fun mapsFourCharacterIdsOnV22() {
        val tag = read(buildTag(version = 2, frames = emptyList()))
        assertEquals(Id3Version.V22, tag.version)

        tag.setTextFrame("TPUB", listOf("Publisher"))

        assertEquals("Publisher", read(Id3v2Writer.encode(tag)).firstText("TPB"))
    }

    @Test
    fun rejectsFourCharacterIdsWithoutV22Mapping() {
        val tag = read(buildTag(version = 2, frames = emptyList()))

        assertFailsWith<IllegalArgumentException> {
            tag.setTextFrame("TDRL", listOf("2010-01-01"))
        }
    }

    @Test
    fun rejectsThreeCharacterIdsOnLaterVersions() {
        val tag = read(buildTag(version = 3, frames = emptyList()))

        assertFailsWith<IllegalArgumentException> {
            tag.setTextFrame("TPB", listOf("Publisher"))
        }
    }

    @Test
    fun readsAndWritesLyrics() {
        val tag = read(buildTag(version = 3, frames = emptyList()))

        tag.lyrics = "First line\nSecond line"

        val reread = read(Id3v2Writer.encode(tag))
        assertEquals("First line\nSecond line", reread.lyrics)
        reread.lyrics = null
        assertEquals(null, read(Id3v2Writer.encode(reread)).lyrics)
    }

    @Test
    fun mapsLyricsFrameOnV22() {
        val tag = read(buildTag(version = 2, frames = emptyList()))

        tag.lyrics = "Words"

        assertEquals("Words", read(Id3v2Writer.encode(tag)).lyrics)
    }

    private fun read(bytes: ByteArray): Id3v2Tag = assertNotNull(Id3v2Reader.read(bytes))

    private fun text(value: String): ByteArray = byteArrayOf(0) + latin1Bytes(value)
}
