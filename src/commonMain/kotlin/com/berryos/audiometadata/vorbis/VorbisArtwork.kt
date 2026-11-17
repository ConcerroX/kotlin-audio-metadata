package com.berryos.audiometadata.vorbis

import com.berryos.audiometadata.Artwork
import com.berryos.audiometadata.flac.encodeFlacPicture
import com.berryos.audiometadata.flac.parseFlacPicture

/** Embedded pictures stored in `METADATA_BLOCK_PICTURE`, with legacy COVERART support. */
public fun VorbisCommentTag.artworks(): List<Artwork> {
    val pictures = ArrayList<Artwork>()
    values("METADATA_BLOCK_PICTURE").forEach { encoded ->
        decodeBase64(encoded)?.let { bytes -> parseFlacPicture(bytes)?.let(pictures::add) }
    }
    if (pictures.isNotEmpty()) return pictures

    val mimeTypes = values("COVERARTMIME")
    val pictureTypes = values("COVERARTTYPE")
    values("COVERART").forEachIndexed { index, encoded ->
        val data = decodeBase64(encoded) ?: return@forEachIndexed
        if (data.isEmpty()) return@forEachIndexed
        pictures += Artwork(
            data = data,
            mimeType = mimeTypes.getOrNull(index)?.ifEmpty { null },
            pictureType = pictureTypes.getOrNull(index)?.toIntOrNull(),
        )
    }
    return pictures
}

/** Replaces the embedded pictures with [artworks], writing standard picture blocks. */
public fun VorbisCommentTag.setArtworks(artworks: List<Artwork>) {
    remove("METADATA_BLOCK_PICTURE")
    remove("COVERART")
    remove("COVERARTMIME")
    remove("COVERARTTYPE")
    artworks.forEach { artwork ->
        add("METADATA_BLOCK_PICTURE", encodeBase64(encodeFlacPicture(artwork)))
    }
}

internal fun decodeBase64(value: String): ByteArray? {
    val output = ArrayList<Byte>()
    var buffer = 0
    var bits = 0
    for (character in value) {
        if (character == '=') break
        if (character.isWhitespace()) continue
        val digit = when (character) {
            in 'A'..'Z' -> character.code - 'A'.code
            in 'a'..'z' -> character.code - 'a'.code + 26
            in '0'..'9' -> character.code - '0'.code + 52
            '+' -> 62
            '/' -> 63
            else -> return null
        }
        buffer = (buffer shl 6) or digit
        bits += 6
        if (bits >= 8) {
            bits -= 8
            output += ((buffer ushr bits) and 0xFF).toByte()
        }
    }
    return output.toByteArray()
}

internal fun encodeBase64(bytes: ByteArray): String {
    val table = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    val output = StringBuilder()
    var index = 0
    while (index + 2 < bytes.size) {
        val value = ((bytes[index].toInt() and 0xFF) shl 16) or
            ((bytes[index + 1].toInt() and 0xFF) shl 8) or
            (bytes[index + 2].toInt() and 0xFF)
        output.append(table[(value ushr 18) and 63])
        output.append(table[(value ushr 12) and 63])
        output.append(table[(value ushr 6) and 63])
        output.append(table[value and 63])
        index += 3
    }
    when (bytes.size - index) {
        1 -> {
            val value = (bytes[index].toInt() and 0xFF) shl 16
            output.append(table[(value ushr 18) and 63])
            output.append(table[(value ushr 12) and 63])
            output.append("==")
        }

        2 -> {
            val value = ((bytes[index].toInt() and 0xFF) shl 16) or
                ((bytes[index + 1].toInt() and 0xFF) shl 8)
            output.append(table[(value ushr 18) and 63])
            output.append(table[(value ushr 12) and 63])
            output.append(table[(value ushr 6) and 63])
            output.append('=')
        }
    }
    return output.toString()
}
