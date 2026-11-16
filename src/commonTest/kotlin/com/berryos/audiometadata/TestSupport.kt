package com.berryos.audiometadata

/** Test helpers shared by the format tests. */

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

internal fun flacPicture(
    data: ByteArray,
    mimeType: String = "image/png",
    pictureType: Int = 3,
    description: String = "cover",
    width: Int = 100,
    height: Int = 100,
    colorDepth: Int = 24,
): ByteArray {
    val mime = mimeType.encodeToByteArray()
    val descriptionBytes = description.encodeToByteArray()
    val chunks = ArrayList<ByteArray>()
    chunks += bigEndianInt(pictureType)
    chunks += bigEndianInt(mime.size)
    chunks += mime
    chunks += bigEndianInt(descriptionBytes.size)
    chunks += descriptionBytes
    chunks += bigEndianInt(width)
    chunks += bigEndianInt(height)
    chunks += bigEndianInt(colorDepth)
    chunks += bigEndianInt(0)
    chunks += bigEndianInt(data.size)
    chunks += data
    return chunks.fold(ByteArray(0)) { accumulated, chunk -> accumulated + chunk }
}

private fun bigEndianInt(value: Int): ByteArray = byteArrayOf(
    (value ushr 24).toByte(),
    (value ushr 16).toByte(),
    (value ushr 8).toByte(),
    value.toByte(),
)
