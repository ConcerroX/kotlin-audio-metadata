package com.berryos.audiometadata.ogg

import com.berryos.audiometadata.io.RandomAccessSource
import com.berryos.audiometadata.io.WritableRandomAccessSource
import com.berryos.audiometadata.io.moveTail

/** First packet found on an Ogg page and whether more packets follow it on the last page. */
internal class OggPacket(val bytes: ByteArray, val lastPageIndex: Int, val trailingBytes: Boolean)

/** Consecutive packets and the page where the last one ends. */
internal class OggStreamHeader(val packets: List<ByteArray>, val lastPageIndex: Int, val trailingBytes: Boolean)

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
internal fun findOggPacket(source: RandomAccessSource, pages: List<OggPage>, startPageIndex: Int): OggPacket? =
    findOggPackets(source, pages, startPageIndex, 1)?.let { header ->
        OggPacket(header.packets.first(), header.lastPageIndex, header.trailingBytes)
    }

/**
 * Collects [count] consecutive packets starting at [startPageIndex] using the lacing rules from
 * RFC 3533 section 6: a lacing value below 255 ends a packet, 255 means the packet continues.
 */
internal fun findOggPackets(
    source: RandomAccessSource,
    pages: List<OggPage>,
    startPageIndex: Int,
    count: Int,
): OggStreamHeader? {
    if (startPageIndex >= pages.size || count <= 0) return null
    val packets = ArrayList<ByteArray>(count)
    val segments = ArrayList<ByteArray>()
    for (index in startPageIndex until pages.size) {
        val page = pages[index]
        val payload = OggPages.readPayload(source, page)
        var position = 0
        page.segmentTable.forEachIndexed { laceIndex, lacing ->
            if (position + lacing > payload.size) return null
            if (lacing > 0) segments += payload.copyOfRange(position, position + lacing)
            position += lacing
            if (lacing < 255) {
                packets += joinSegments(segments)
                segments.clear()
                if (packets.size == count) {
                    val trailing = position < payload.size || laceIndex < page.segmentTable.lastIndex
                    return OggStreamHeader(packets, index, trailing)
                }
            }
        }
    }
    return null
}

/**
 * Replaces the packets described by [header] with [newPackets] and rebuilds the pages after the
 * identification page. Following pages are renumbered and re-checksummed.
 */
internal fun replaceOggPackets(
    source: WritableRandomAccessSource,
    pages: List<OggPage>,
    header: OggStreamHeader,
    newPackets: List<ByteArray>,
) {
    check(!header.trailingBytes) { "Ogg page contains packets after the tag packet" }
    val identificationPage = pages.first()
    val lastHeaderPage = pages[header.lastPageIndex]
    val audioStart = lastHeaderPage.offset + lastHeaderPage.size

    val rebuiltPages = ArrayList<ByteArray>()
    var sequence = 1
    newPackets.forEach { packet ->
        val packetPages = OggPages.pagePacket(packet, identificationPage.serialNumber, sequence)
        rebuiltPages += packetPages
        sequence += packetPages.size
    }
    val newAudioStart = identificationPage.size.toLong() + rebuiltPages.sumOf { page -> page.size.toLong() }
    source.moveTail(audioStart, newAudioStart)

    var writeOffset = identificationPage.size.toLong()
    rebuiltPages.forEach { page ->
        source.writeAt(writeOffset, page)
        writeOffset += page.size
    }

    var nextSequence = rebuiltPages.size + 1
    var offset = newAudioStart
    while (offset < source.size) {
        val size = OggPages.renumberPage(source, offset, nextSequence++)
        if (size <= 0) break
        offset += size
    }
    source.flush()
}

/**
 * Replaces the packet described by [packet] with [newPacket], then renumbers and re-checksums the
 * following pages.
 */
internal fun replaceOggPacket(
    source: WritableRandomAccessSource,
    pages: List<OggPage>,
    packet: OggPacket,
    newPacket: ByteArray,
) {
    replaceOggPackets(
        source,
        pages,
        OggStreamHeader(listOf(packet.bytes), packet.lastPageIndex, packet.trailingBytes),
        listOf(newPacket),
    )
}

private fun joinSegments(segments: List<ByteArray>): ByteArray {
    val total = segments.sumOf { segment -> segment.size }
    val packet = ByteArray(total)
    var offset = 0
    segments.forEach { segment ->
        segment.copyInto(packet, offset)
        offset += segment.size
    }
    return packet
}
