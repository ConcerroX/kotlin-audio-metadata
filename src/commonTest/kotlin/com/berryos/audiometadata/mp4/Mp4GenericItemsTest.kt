package com.berryos.audiometadata.mp4

import com.berryos.audiometadata.AudioFile
import com.berryos.audiometadata.ExperimentalAudioMetadataApi
import com.berryos.audiometadata.io.ByteArraySource
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalAudioMetadataApi::class)
class Mp4GenericItemsTest {
    @Test
    fun readsUnmanagedItems() {
        val source = sourceWith(
            mp4File(extraItems = textItem("©wrk", "Work") + freeformItem("LABEL", "Label")),
        )
        val file = assertNotNull(AudioFile.read(source))
        val mp4 = assertNotNull(file.mp4)

        val work = mp4.item("©wrk").single()
        assertEquals("Work", work.data.decodeToString())
        assertEquals(Mp4Item.TYPE_UTF8, work.dataType)
        assertEquals("Label", mp4.freeformText("LABEL"))
    }

    @Test
    fun writesAndRemovesItems() {
        val source = sourceWith(mp4File(extraItems = textItem("©wrk", "Work") + freeformItem("LABEL", "Label")))
        val file = assertNotNull(AudioFile.read(source))
        val mp4 = assertNotNull(file.mp4)

        mp4.setItem("ownr", Mp4Item.TYPE_UTF8, "Owner".encodeToByteArray())
        mp4.setFreeform("MOOD", "Calm")
        mp4.removeItem("©wrk")
        file.save(source)

        val reread = assertNotNull(AudioFile.read(source))
        val updated = assertNotNull(reread.mp4)
        assertEquals("Owner", updated.item("ownr").single().data.decodeToString())
        assertEquals("Calm", updated.freeformText("MOOD"))
        assertEquals("Label", updated.freeformText("LABEL"))
        assertTrue(updated.item("©wrk").isEmpty())
    }

    @Test
    fun preservesUnknownBinaryItems() {
        val source = sourceWith(mp4File(extraItems = dataItem("apID", Mp4Item.TYPE_INTEGER, byteArrayOf(1, 2, 3))))
        val file = assertNotNull(AudioFile.read(source))
        file.title = "Changed title"
        file.save(source)

        val reread = assertNotNull(AudioFile.read(source))
        val item = assertNotNull(reread.mp4).item("apID").single()
        assertEquals(Mp4Item.TYPE_INTEGER, item.dataType)
        assertContentEquals(byteArrayOf(1, 2, 3), item.data)
    }

    @Test
    fun removesFreeformEntries() {
        val source = sourceWith(mp4File(extraItems = freeformItem("LABEL", "Label")))
        val file = assertNotNull(AudioFile.read(source))
        assertNotNull(file.mp4).removeFreeform("LABEL")
        file.save(source)

        val reread = assertNotNull(AudioFile.read(source))
        assertEquals(null, assertNotNull(reread.mp4).freeformText("LABEL"))
    }

    @Test
    fun rejectsFreeformTypeInSetItem() {
        val source = sourceWith(mp4File())
        val mp4 = assertNotNull(assertNotNull(AudioFile.read(source)).mp4)

        val failure = runCatching { mp4.setItem("----", Mp4Item.TYPE_UTF8, byteArrayOf()) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    private fun sourceWith(bytes: ByteArray): ByteArraySource =
        ByteArraySource().also { it.writeAt(0, bytes) }
}
