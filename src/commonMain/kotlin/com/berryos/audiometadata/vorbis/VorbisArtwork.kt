package com.berryos.audiometadata.vorbis

import com.berryos.audiometadata.Artwork
import com.berryos.audiometadata.flac.parseFlacPicture

/** Embedded pictures stored in `METADATA_BLOCK_PICTURE`, with legacy COVERART support. */
fun VorbisCommentTag.artworks(): List<Artwork> {
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
