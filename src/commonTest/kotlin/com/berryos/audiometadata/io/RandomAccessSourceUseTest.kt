package com.berryos.audiometadata.io

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RandomAccessSourceUseTest {
    @Test
    fun closesSourceAfterBlock() {
        val source = CloseTrackingSource()

        val result = source.use { it.size }

        assertEquals(0L, result)
        assertTrue(source.closed)
    }

    @Test
    fun closesSourceWhenBlockThrows() {
        val source = CloseTrackingSource()

        assertFailsWith<IllegalStateException> {
            source.use { error("boom") }
        }

        assertTrue(source.closed)
    }

    private class CloseTrackingSource : RandomAccessSource {
        var closed = false

        override val size: Long
            get() = 0

        override fun readAt(offset: Long, destination: ByteArray, destinationOffset: Int, length: Int): Int = 0

        override fun close() {
            closed = true
        }
    }
}
