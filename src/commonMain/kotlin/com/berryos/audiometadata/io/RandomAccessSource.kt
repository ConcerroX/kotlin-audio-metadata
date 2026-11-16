package com.berryos.audiometadata.io

/**
 * Read access to a file-like byte sequence without a sequential cursor.
 *
 * Implementations are not thread-safe unless documented otherwise.
 */
interface RandomAccessSource {
    /** Current length of the underlying data in bytes. */
    val size: Long

    /**
     * Reads up to [length] bytes starting at [offset].
     *
     * Returns the number of bytes actually read, or 0 at end of data.
     */
    fun readAt(
        offset: Long,
        destination: ByteArray,
        destinationOffset: Int = 0,
        length: Int = destination.size - destinationOffset,
    ): Int

    fun close()
}

/** Random-access source that can also replace bytes and change its length. */
interface WritableRandomAccessSource : RandomAccessSource {
    /** Writes [length] bytes at [offset], extending the data when needed. */
    fun writeAt(
        offset: Long,
        source: ByteArray,
        sourceOffset: Int = 0,
        length: Int = source.size - sourceOffset,
    )

    /** Shortens the data to [size] bytes; never extends it. */
    fun truncate(size: Long)

    /** Forces pending writes to the underlying storage where applicable. */
    fun flush()
}
