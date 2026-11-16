package com.berryos.audiometadata.ogg

import com.berryos.audiometadata.io.RandomAccessSource
import com.berryos.audiometadata.io.WritableRandomAccessSource
import com.berryos.audiometadata.io.moveTail

/** First packet found on an Ogg page and whether more packets follow it on the last page. */
internal class OggPacket(val bytes: ByteArray, val lastPageIndex: Int, val trailingBytes: Boolean)

internal fun walkOggPages(source: RandomAccessSource): List<OggPage> {
    val pages = ArrayList<OggPage>()
    var offset = 0L
    while (offset < source.size) {
        val page = OggPages.read(source, offset) ?: break
        pages += page
        offset += page.size
    }
    return pages
}

/** Collects the first packet starting at [startPageIndex], following continued pages. */
internal fun findOggPacket(source: RandomAccessSource, pages: List<OggPage>, startPageIndex: Int): OggPacket? {
    if (startPageIndex >= pages.size) return null
    val chunks = ArrayList<ByteArray>()
    for (index in startPageIndex until pages.size) {
        val page = pages[index]
        val payload = OggPages.readPayload(source, page)
        var consumed = 0
        for (segment in page.segmentTable) {
            if (consumed + segment > payload.size) return null
            chunks += payload.copyOfRange(consumed, consumed + segment)
            consumed += segment
            if (segment < 255) {
                return OggPacket(concatenate(chunks), index, trailingBytes = consumed < payload.size)
            }
        }
    }
    return null
}

/**
 * Replaces the packet that [packet] describes with [newPacket], then renumbers and re-checksums
 * every following page.
 */
internal fun replaceOggPacket(
    source: WritableRandomAccessSource,
    pages: List<OggPage>,
    packet: OggPacket,
    newPacket: ByteArray,
) {
    check(!packet.trailingBytes) { "Ogg page contains packets after the tag packet" }
    val head = pages.first()
    val lastTagPage = pages[packet.lastPageIndex]
    val audioStart = lastTagPage.offset + lastTagPage.size

    val newPages = OggPages.pagePacket(newPacket, head.serialNumber, 1)
    val newAudioStart = head.size.toLong() + newPages.sumOf { it.size.toLong() }
    source.moveTail(audioStart, newAudioStart)

    var writeOffset = head.size.toLong()
    newPages.forEach { page ->
        source.writeAt(writeOffset, page)
        writeOffset += page.size
    }

    var sequence = newPages.size + 1
    var offset = newAudioStart
    while (offset < source.size) {
        val size = OggPages.renumberPage(source, offset, sequence++)
        if (size <= 0) break
        offset += size
    }
    source.flush()
}

private fun concatenate(chunks: List<ByteArray>): ByteArray {
    val total = chunks.sumOf { it.size }
    val result = ByteArray(total)
    var offset = 0
    chunks.forEach { chunk ->
        chunk.copyInto(result, offset)
        offset += chunk.size
    }
    return result
}
