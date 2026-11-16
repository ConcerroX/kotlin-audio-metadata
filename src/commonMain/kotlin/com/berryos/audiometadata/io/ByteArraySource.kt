package com.berryos.audiometadata.io

import kotlin.math.max

/** Growable in-memory implementation used by tests and in-memory consumers. */
class ByteArraySource(initialSize: Int = 0) : WritableRandomAccessSource {
    private var bytes = ByteArray(initialSize)
    private var length = initialSize.toLong()

    override val size: Long
        get() = length

    override fun readAt(offset: Long, destination: ByteArray, destinationOffset: Int, length: Int): Int {
        require(offset >= 0) { "offset must not be negative" }
        require(destinationOffset >= 0 && length >= 0 && destinationOffset + length <= destination.size) {
            "invalid destination range"
        }
        if (offset >= this.length || length == 0) return 0
        val count = minOf(length.toLong(), this.length - offset).toInt()
        bytes.copyInto(destination, destinationOffset, offset.toInt(), offset.toInt() + count)
        return count
    }

    override fun writeAt(offset: Long, source: ByteArray, sourceOffset: Int, length: Int) {
        require(offset >= 0) { "offset must not be negative" }
        require(sourceOffset >= 0 && length >= 0 && sourceOffset + length <= source.size) {
            "invalid source range"
        }
        if (length == 0) return
        ensureCapacity(offset + length)
        source.copyInto(bytes, offset.toInt(), sourceOffset, sourceOffset + length)
        this.length = max(this.length, offset + length)
    }

    override fun truncate(size: Long) {
        require(size in 0..length) { "cannot extend with truncate" }
        length = size
    }

    override fun flush() = Unit

    override fun close() = Unit

    fun toByteArray(): ByteArray = bytes.copyOf(length.toInt())

    private fun ensureCapacity(required: Long) {
        if (required <= bytes.size) return
        val grown = max(required, bytes.size * 2L).toInt()
        bytes = bytes.copyOf(max(grown, INITIAL_CAPACITY))
    }

    private companion object {
        const val INITIAL_CAPACITY = 16
    }
}
