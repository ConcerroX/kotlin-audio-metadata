package com.berryos.audiometadata

/** Shared byte-level helpers for the RIFF, IFF, Ogg and MP4 parsers. */
internal fun ByteArray.hasAsciiAt(offset: Int, value: String): Boolean {
    if (offset < 0 || size < offset + value.length) return false
    for (index in value.indices) {
        if (this[offset + index] != value[index].code.toByte()) return false
    }
    return true
}

/** Reads [length] bytes as Latin-1 characters; used for four-character chunk and atom names. */
internal fun ByteArray.asciiAt(offset: Int, length: Int): String = buildString(length) {
    repeat(length) { index -> append((this@asciiAt[offset + index].toInt() and 0xFF).toChar()) }
}

internal fun ByteArray.readUInt16Le(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)

internal fun ByteArray.readUInt32Le(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or
        ((this[offset + 1].toInt() and 0xFF) shl 8) or
        ((this[offset + 2].toInt() and 0xFF) shl 16) or
        ((this[offset + 3].toInt() and 0xFF) shl 24)

internal fun ByteArray.readUInt64Le(offset: Int): Long {
    var value = 0L
    for (index in 0 until 8) {
        value = value or ((this[offset + index].toLong() and 0xFF) shl (index * 8))
    }
    return value
}

internal fun ByteArray.writeUInt16Le(offset: Int, value: Int) {
    this[offset] = value.toByte()
    this[offset + 1] = (value ushr 8).toByte()
}

internal fun ByteArray.writeUInt32Le(offset: Int, value: Int) {
    this[offset] = value.toByte()
    this[offset + 1] = (value ushr 8).toByte()
    this[offset + 2] = (value ushr 16).toByte()
    this[offset + 3] = (value ushr 24).toByte()
}

internal fun ByteArray.writeUInt64Le(offset: Int, value: Long) {
    for (index in 0 until 8) {
        this[offset + index] = (value ushr (index * 8)).toByte()
    }
}

internal fun ByteArray.readUInt16Be(offset: Int): Int =
    ((this[offset].toInt() and 0xFF) shl 8) or (this[offset + 1].toInt() and 0xFF)

internal fun ByteArray.readUInt32Be(offset: Int): Int =
    ((this[offset].toInt() and 0xFF) shl 24) or
        ((this[offset + 1].toInt() and 0xFF) shl 16) or
        ((this[offset + 2].toInt() and 0xFF) shl 8) or
        (this[offset + 3].toInt() and 0xFF)

internal fun ByteArray.readUInt64Be(offset: Int): Long {
    var value = 0L
    for (index in 0 until 8) {
        value = (value shl 8) or (this[offset + index].toLong() and 0xFF)
    }
    return value
}

internal fun ByteArray.writeUInt16Be(offset: Int, value: Int) {
    this[offset] = (value ushr 8).toByte()
    this[offset + 1] = value.toByte()
}

internal fun ByteArray.writeUInt32Be(offset: Int, value: Int) {
    this[offset] = (value ushr 24).toByte()
    this[offset + 1] = (value ushr 16).toByte()
    this[offset + 2] = (value ushr 8).toByte()
    this[offset + 3] = value.toByte()
}

internal fun ByteArray.writeUInt64Be(offset: Int, value: Long) {
    for (index in 0 until 8) {
        this[offset + index] = (value ushr (56 - index * 8)).toByte()
    }
}
