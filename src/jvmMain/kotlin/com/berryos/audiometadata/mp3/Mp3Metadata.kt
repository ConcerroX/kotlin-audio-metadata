package com.berryos.audiometadata.mp3

import com.berryos.audiometadata.AudioFormat
import com.berryos.audiometadata.AudioInfo
import com.berryos.audiometadata.AudioMetadata
import com.berryos.audiometadata.id3.Id3Tag

/** Result of reading an MP3 file: the ID3v2 tag (if any), the audio offset and the ID3v1 tag. */
internal class Mp3Metadata(
    val tag: Id3Tag?,
    internal val audioStartOffset: Long,
    val id3v1: Id3v1Metadata?,
    val audioInfo: AudioInfo? = null,
) {
    fun toAudioMetadata(): AudioMetadata = tag?.toAudioMetadata(AudioFormat.Mp3, audioInfo)
        ?: id3v1?.toAudioMetadata(audioInfo)
        ?: AudioMetadata(format = AudioFormat.Mp3, audioInfo = audioInfo)
}

/** Fields of a trailing ID3v1/ID3v1.1 tag. */
internal data class Id3v1Metadata(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val year: String? = null,
    val comment: String? = null,
    val trackNumber: Int? = null,
    val genreIndex: Int? = null,
) {
    fun toAudioMetadata(audioInfo: AudioInfo? = null): AudioMetadata = AudioMetadata(
        format = AudioFormat.Mp3,
        title = title,
        artists = listOfNotNull(artist),
        album = album,
        year = year,
        comments = listOfNotNull(comment),
        trackNumber = trackNumber,
        audioInfo = audioInfo,
    )
}
