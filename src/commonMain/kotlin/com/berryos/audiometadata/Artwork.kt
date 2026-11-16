package com.berryos.audiometadata

/** An embedded picture. [pictureType] follows the ID3/FLAC picture type numbering. */
class Artwork(
    val data: ByteArray,
    val mimeType: String? = null,
    val pictureType: Int? = null,
    val description: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val colorDepth: Int? = null,
)
