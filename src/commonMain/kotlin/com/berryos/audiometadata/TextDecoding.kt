package com.berryos.audiometadata

/**
 * Decodes RIFF `INFO` and AIFF text chunks.
 *
 * These fields carry no declared encoding: legacy writers used the local ANSI code page or
 * MacRoman, modern ones use UTF-8. Decode as UTF-8 when possible and fall back to Latin-1.
 */
internal fun decodePlainText(bytes: ByteArray): String? {
    var end = bytes.size
    while (end > 0 && (bytes[end - 1].toInt() == 0 || bytes[end - 1].toInt() == SPACE)) end--
    if (end == 0) return null
    val text = runCatching {
        bytes.decodeToString(0, end, throwOnInvalidSequence = true)
    }.getOrElse {
        buildString(end) {
            for (index in 0 until end) append((bytes[index].toInt() and 0xFF).toChar())
        }
    }
    return text.trim().takeIf(String::isNotEmpty)
}

private const val SPACE = 0x20
