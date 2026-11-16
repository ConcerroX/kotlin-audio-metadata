package com.berryos.audiometadata.mp3

import com.berryos.audiometadata.id3.Id3v1Reader
import com.berryos.audiometadata.id3.Id3v2Reader
import com.berryos.audiometadata.io.RandomAccessSource

/** Reads the ID3v2 tag, the trailing ID3v1 tag and the audio stream properties of an MP3 file. */
internal object Mp3MetadataReader {
    fun read(source: RandomAccessSource): Mp3Metadata? {
        val tag = Id3v2Reader.read(source)
        val id3v1 = Id3v1Reader.read(source)
        if (tag == null && id3v1 == null) return null
        val audioStart = tag?.let {
            HEADER_SIZE.toLong() + it.declaredSize + if (it.hasFooter) FOOTER_SIZE.toLong() else 0L
        } ?: 0L
        return Mp3Metadata(tag, audioStart, id3v1, Mp3AudioInfoReader.read(source, audioStart))
    }

    private const val HEADER_SIZE = 10
    private const val FOOTER_SIZE = 20
}
