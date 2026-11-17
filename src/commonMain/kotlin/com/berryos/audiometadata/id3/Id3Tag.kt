package com.berryos.audiometadata.id3

import com.berryos.audiometadata.Artwork
import com.berryos.audiometadata.AudioFormat
import com.berryos.audiometadata.AudioInfo
import com.berryos.audiometadata.AudioMetadata
import com.berryos.audiometadata.Id3Version
import com.berryos.audiometadata.ReplayGain

/**
 * Mutable ID3 tag shared by MP3, WAV and AIFF.
 *
 * Values are nullable or lists; an empty value means that the frame is absent. Implementations
 * edit the underlying tag in place and are written by the container-specific writer.
 */
interface Id3Tag {
    val version: Id3Version
    var title: String?
    var artists: List<String>
    var album: String?
    var albumArtist: String?
    var trackNumber: Int?
    var trackTotal: Int?
    var discNumber: Int?
    var discTotal: Int?
    var year: String?
    var genres: List<String>
    var composers: List<String>
    var comments: List<String>
    var lyrics: String?
    var replayGain: ReplayGain?
    val artworks: List<Artwork>

    /** Replaces the embedded pictures. */
    fun setArtworks(artworks: List<Artwork>)

    fun toAudioMetadata(
        format: AudioFormat = AudioFormat.Mp3,
        audioInfo: AudioInfo? = null,
    ): AudioMetadata
}
