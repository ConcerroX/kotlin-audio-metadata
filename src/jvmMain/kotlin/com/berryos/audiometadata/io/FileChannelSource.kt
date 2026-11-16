package com.berryos.audiometadata.io

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/** File-backed source using positional channel reads/writes; usable on JVM and Android. */
class FileChannelSource(file: File, writable: Boolean = true) : WritableRandomAccessSource {
    private val channel: FileChannel = if (writable) {
        FileChannel.open(
            file.toPath(),
            StandardOpenOption.READ,
            StandardOpenOption.WRITE,
            StandardOpenOption.CREATE,
        )
    } else {
        FileChannel.open(file.toPath(), StandardOpenOption.READ)
    }

    override val size: Long
        get() = channel.size()

    override fun readAt(offset: Long, destination: ByteArray, destinationOffset: Int, length: Int): Int {
        require(offset >= 0) { "offset must not be negative" }
        require(destinationOffset >= 0 && length >= 0 && destinationOffset + length <= destination.size) {
            "invalid destination range"
        }
        if (length == 0) return 0
        val buffer = ByteBuffer.wrap(destination, destinationOffset, length)
        var position = offset
        var total = 0
        while (buffer.hasRemaining()) {
            val read = channel.read(buffer, position)
            if (read <= 0) break
            position += read
            total += read
        }
        return total
    }

    override fun writeAt(offset: Long, source: ByteArray, sourceOffset: Int, length: Int) {
        require(offset >= 0) { "offset must not be negative" }
        require(sourceOffset >= 0 && length >= 0 && sourceOffset + length <= source.size) {
            "invalid source range"
        }
        val buffer = ByteBuffer.wrap(source, sourceOffset, length)
        var position = offset
        while (buffer.hasRemaining()) {
            val written = channel.write(buffer, position)
            if (written <= 0) throw IOException("Unable to write at $position")
            position += written
        }
    }

    override fun truncate(size: Long) {
        require(size >= 0) { "size must not be negative" }
        channel.truncate(size)
    }

    override fun flush() {
        channel.force(true)
    }

    override fun close() {
        channel.close()
    }
}
