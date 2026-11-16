package com.berryos.audiometadata.io

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class ByteArraySourceTest {
    @Test
    fun writesReadsAndExtends() {
        val source = ByteArraySource()
        source.writeAt(2, byteArrayOf(1, 2, 3))
        assertEquals(5, source.size)
        source.writeAt(0, byteArrayOf(9, 9, 9, 9, 9, 9))

        val buffer = ByteArray(4)
        val read = source.readAt(1, buffer)

        assertEquals(4, read)
        assertContentEquals(byteArrayOf(9, 9, 9, 9), buffer)
        assertContentEquals(byteArrayOf(9, 9, 9, 9, 9, 9), source.toByteArray())
    }

    @Test
    fun readsPastEndAsEmpty() {
        val source = ByteArraySource(3)
        val buffer = ByteArray(2)

        assertEquals(0, source.readAt(3, buffer))
    }

    @Test
    fun truncateShortensWithoutExtending() {
        val source = ByteArraySource(6)
        source.truncate(2)
        assertEquals(2, source.size)

        val result = runCatching { source.truncate(5) }
        assertEquals(true, result.isFailure)
    }
}
