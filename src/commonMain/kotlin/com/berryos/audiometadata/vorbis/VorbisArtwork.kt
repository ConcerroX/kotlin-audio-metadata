package com.berryos.audiometadata.vorbis

import com.berryos.audiometadata.Artwork
import com.berryos.audiometadata.flac.encodeFlacPicture
import com.berryos.audiometadata.flac.parseFlacPicture
import kotlin.io.encoding.Base64

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

internal fun decodeBase64(value: String): ByteArray? =
    runCatching { Base64.decode(value.filterNot(Char::isWhitespace)) }.getOrNull()

internal fun encodeBase64(bytes: ByteArray): String = Base64.encode(bytes)
