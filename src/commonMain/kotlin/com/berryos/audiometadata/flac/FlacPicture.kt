package com.berryos.audiometadata.flac

import com.berryos.audiometadata.Artwork

/**
 * Parses the FLAC PICTURE block body, which is also the payload of the Vorbis
 * `METADATA_BLOCK_PICTURE` comment.
 */
internal fun parseFlacPicture(bytes: ByteArray): Artwork? {
    var offset = 0

    fun readInt(): Int? {
        if (offset + 4 > bytes.size) return null
        val value = ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
        offset += 4
        return value
    }

    fun readString(length: Int): String? {
        if (length < 0 || offset + length > bytes.size) return null
        val value = bytes.decodeToString(offset, offset + length)
        offset += length
        return value
    }

    val pictureType = readInt() ?: return null
    val mimeLength = readInt() ?: return null
    val mimeType = readString(mimeLength) ?: return null
    val descriptionLength = readInt() ?: return null
    val description = readString(descriptionLength) ?: return null
    val width = readInt() ?: return null
    val height = readInt() ?: return null
    val colorDepth = readInt() ?: return null
    readInt() ?: return null // number of colors, unused
    val dataLength = readInt() ?: return null
    if (dataLength < 0 || offset + dataLength > bytes.size) return null
    val data = bytes.copyOfRange(offset, offset + dataLength)
    if (mimeType == "-->" || data.isEmpty()) return null
    return Artwork(
        data = data,
        mimeType = mimeType.ifEmpty { null },
        pictureType = pictureType,
        description = description.ifEmpty { null },
        width = width,
        height = height,
        colorDepth = colorDepth,
    )
}
