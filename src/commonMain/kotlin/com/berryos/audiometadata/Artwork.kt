package com.berryos.audiometadata

/** An embedded picture. [pictureType] follows the ID3/FLAC picture type numbering. */
public class Artwork(
    /** Encoded image bytes; treat as read-only. */
    public val data: ByteArray,
    public val mimeType: String? = null,
    public val pictureType: Int? = null,
    public val description: String? = null,
    public val width: Int? = null,
    public val height: Int? = null,
    public val colorDepth: Int? = null,
)

internal fun guessArtworkMimeType(data: ByteArray): String = when {
    data.size >= 3 && data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte() && data[2] == 0xFF.toByte() -> "image/jpeg"
    data.size >= 8 && data[0] == 0x89.toByte() && data[1] == 0x50.toByte() &&
        data[2] == 0x4E.toByte() && data[3] == 0x47.toByte() -> "image/png"
    else -> "application/octet-stream"
}
