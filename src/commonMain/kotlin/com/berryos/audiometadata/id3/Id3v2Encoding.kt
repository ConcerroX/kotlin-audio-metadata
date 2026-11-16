package com.berryos.audiometadata.id3

import com.berryos.audiometadata.Artwork
import com.berryos.audiometadata.Id3Version
import com.berryos.audiometadata.guessArtworkMimeType

/** Encodes frame bodies when a tag is edited. ID3v2.4 uses UTF-8; 2.3 falls back to UTF-16. */
internal object Id3v2Encoding {
    fun textContent(version: Id3Version, values: List<String>): ByteArray {
        val encoding = chooseEncoding(version, values)
        val separator = terminator(encoding)
        val chunks = ArrayList<ByteArray>()
        values.forEachIndexed { index, value ->
            if (index > 0) chunks += separator
            chunks += encode(encoding, value)
        }
        return byteArrayOf(encoding.toByte()) + concat(chunks)
    }

    fun userTextContent(version: Id3Version, description: String, value: String): ByteArray {
        val encoding = chooseEncoding(version, listOf(description, value))
        return byteArrayOf(encoding.toByte()) + encode(encoding, description) + terminator(encoding) +
            encode(encoding, value)
    }

    fun commentContent(version: Id3Version, text: String): ByteArray {
        val encoding = chooseEncoding(version, listOf(text))
        return byteArrayOf(encoding.toByte()) + COMMENT_LANGUAGE + terminator(encoding) + encode(encoding, text)
    }

    fun pictureContent(version: Id3Version, artwork: Artwork): ByteArray {
        val description = artwork.description.orEmpty()
        val encoding = chooseEncoding(version, listOf(description))
        val mime = (artwork.mimeType ?: guessArtworkMimeType(artwork.data)).encodeToByteArray()
        return byteArrayOf(encoding.toByte()) + mime + byteArrayOf(0) +
            byteArrayOf((artwork.pictureType ?: DEFAULT_PICTURE_TYPE).toByte()) +
            encode(encoding, description) + terminator(encoding) + artwork.data
    }

    private fun chooseEncoding(version: Id3Version, values: List<String>): Int = when {
        version == Id3Version.V24 -> Id3Text.UTF8
        values.all(::isLatin1) -> Id3Text.LATIN1
        else -> Id3Text.UTF16
    }

    private fun isLatin1(value: String): Boolean = value.all { char -> char.code <= 0xFF }

    private fun encode(encoding: Int, value: String): ByteArray = when (encoding) {
        Id3Text.LATIN1 -> ByteArray(value.length) { index -> value[index].code.toByte() }
        Id3Text.UTF8 -> value.encodeToByteArray()
        else -> byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + utf16LittleEndian(value)
    }

    private fun utf16LittleEndian(value: String): ByteArray {
        val bytes = ByteArray(value.length * 2)
        value.forEachIndexed { index, char ->
            bytes[index * 2] = char.code.toByte()
            bytes[index * 2 + 1] = (char.code shr 8).toByte()
        }
        return bytes
    }

    private fun terminator(encoding: Int): ByteArray =
        if (encoding == Id3Text.UTF16 || encoding == Id3Text.UTF16BE) byteArrayOf(0, 0) else byteArrayOf(0)

    private fun concat(chunks: List<ByteArray>): ByteArray {
        val total = chunks.sumOf { it.size }
        val output = ByteArray(total)
        var offset = 0
        chunks.forEach { chunk ->
            chunk.copyInto(output, offset)
            offset += chunk.size
        }
        return output
    }
}

private val COMMENT_LANGUAGE = "eng".encodeToByteArray()
private const val DEFAULT_PICTURE_TYPE = 3
