package com.berryos.audiometadata

/** Latin-1 decoding for test fixtures; common code cannot use Charsets.ISO_8859_1. */
internal fun ByteArray.decodeLatin1(): String = buildString(size) {
    for (byte in this@decodeLatin1) append((byte.toInt() and 0xFF).toChar())
}

internal fun String.encodeLatin1(): ByteArray = ByteArray(length) { index -> this[index].code.toByte() }
