package com.berryos.audiometadata.id3

/**
 * Text encodings used by ID3v2 frames.
 *
 * Values 0 to 3 follow the ID3v2.3/2.4 text encoding byte; UTF-8 only exists in ID3v2.4.
 */
internal object Id3Text {
    const val LATIN1 = 0
    const val UTF16 = 1
    const val UTF16BE = 2
    const val UTF8 = 3

    private val VALUE_SEPARATOR: Char = 0.toChar()
    private val BYTE_ORDER_MARK: Char = 0xFEFF.toChar()

    /** Decodes [bytes] between [start] and [end] using the frame text encoding byte [encoding]. */
    fun decode(encoding: Int, bytes: ByteArray, start: Int = 0, end: Int = bytes.size): String =
        when (encoding) {
            UTF16 -> decodeUtf16(bytes, start, end, littleEndianByDefault = true)
            UTF16BE -> decodeUtf16(bytes, start, end, littleEndianByDefault = false)
            UTF8 -> bytes.decodeToString(start, end)
            else -> decodeLatin1(bytes, start, end)
        }

    /** Splits a decoded text frame body into its null-separated values. */
    fun splitValues(text: String): List<String> = text
        .split(VALUE_SEPARATOR)
        .map { value -> value.trimStart(BYTE_ORDER_MARK) }
        .filter(String::isNotEmpty)

    /** Width of the string terminator for [encoding] in bytes. */
    fun terminatorLength(encoding: Int): Int =
        if (encoding == UTF16 || encoding == UTF16BE) 2 else 1

    /**
     * Returns the index of the first null terminator in [bytes] or [end] when none is present.
     *
     * UTF-16 terminators are two bytes; both aligned and misaligned pairs are accepted because
     * some writers do not keep the description aligned to the start of the string.
     */
    fun findTerminator(encoding: Int, bytes: ByteArray, start: Int, end: Int = bytes.size): Int {
        if (encoding == UTF16 || encoding == UTF16BE) {
            var index = start
            while (index + 1 < end) {
                if (bytes[index].toInt() == 0 && bytes[index + 1].toInt() == 0) return index
                index++
            }
            return end
        }
        var index = start
        while (index < end) {
            if (bytes[index].toInt() == 0) return index
            index++
        }
        return end
    }

    private fun decodeLatin1(bytes: ByteArray, start: Int, end: Int): String {
        val builder = StringBuilder(end - start)
        for (index in start until end) {
            builder.append((bytes[index].toInt() and 0xFF).toChar())
        }
        return builder.toString()
    }

    private fun decodeUtf16(bytes: ByteArray, start: Int, end: Int, littleEndianByDefault: Boolean): String {
        var offset = start
        var littleEndian = littleEndianByDefault
        if (end - offset >= 2) {
            val first = bytes[offset].toInt() and 0xFF
            val second = bytes[offset + 1].toInt() and 0xFF
            if (first == 0xFF && second == 0xFE) {
                littleEndian = true
                offset += 2
            } else if (first == 0xFE && second == 0xFF) {
                littleEndian = false
                offset += 2
            }
        }
        val builder = StringBuilder((end - offset) / 2)
        while (offset + 1 < end) {
            val first = bytes[offset].toInt() and 0xFF
            val second = bytes[offset + 1].toInt() and 0xFF
            val codeUnit = if (littleEndian) (second shl 8) or first else (first shl 8) or second
            builder.append(codeUnit.toChar())
            offset += 2
        }
        return builder.toString()
    }
}
