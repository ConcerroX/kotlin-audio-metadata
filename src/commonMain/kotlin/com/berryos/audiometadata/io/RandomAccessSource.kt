package com.berryos.audiometadata.io

/**
 * Read access to a file-like byte sequence without a sequential cursor.
 *
 * Implementations are not thread-safe unless documented otherwise.
 */
public interface RandomAccessSource {
    /** Current length of the underlying data in bytes. */
    public val size: Long

    /**
     * Reads up to [length] bytes starting at [offset].
     *
     * Returns the number of bytes actually read, or 0 at end of data.
     */
    public fun readAt(
        offset: Long,
        destination: ByteArray,
        destinationOffset: Int = 0,
        length: Int = destination.size - destinationOffset,
    ): Int

    public fun close()
}

/** Random-access source that can also replace bytes and change its length. */
public interface WritableRandomAccessSource : RandomAccessSource {
    /** Writes [length] bytes at [offset], extending the data when needed. */
    public fun writeAt(
        offset: Long,
        source: ByteArray,
        sourceOffset: Int = 0,
        length: Int = source.size - sourceOffset,
    )

    /** Shortens the data to [size] bytes; never extends it. */
    public fun truncate(size: Long)

    /** Forces pending writes to the underlying storage where applicable. */
    public fun flush()
}

/** Runs [block] with this source and closes it afterwards, even when the block throws. */
public inline fun <T, R : RandomAccessSource> R.use(block: (R) -> T): T {
    try {
        return block(this)
    } finally {
        close()
    }
}
