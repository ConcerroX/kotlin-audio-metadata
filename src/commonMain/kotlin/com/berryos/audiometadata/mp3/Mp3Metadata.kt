package com.berryos.audiometadata.mp3

import com.berryos.audiometadata.AudioFormat
import com.berryos.audiometadata.AudioInfo
import com.berryos.audiometadata.AudioMetadata
import com.berryos.audiometadata.id3.Id3v1Tag
import com.berryos.audiometadata.id3.Id3v2Tag

/** Result of reading an MP3 file: the ID3v2 tag (if any), the audio offset and the ID3v1 tag. */
internal class Mp3Metadata(
    val tag: Id3v2Tag?,
    internal val audioStartOffset: Long,
    val id3v1: Id3v1Tag?,
    val audioInfo: AudioInfo? = null,
) {
    fun toAudioMetadata(): AudioMetadata = tag?.toAudioMetadata(AudioFormat.MP3, audioInfo)
        ?: id3v1?.let(::id3v1Metadata)
        ?: AudioMetadata(format = AudioFormat.MP3, audioInfo = audioInfo)

    private fun id3v1Metadata(tag: Id3v1Tag): AudioMetadata = AudioMetadata(
        format = AudioFormat.MP3,
        title = tag.title,
        artists = listOfNotNull(tag.artist),
        album = tag.album,
        year = tag.year,
        comments = listOfNotNull(tag.comment),
        trackNumber = tag.trackNumber,
        audioInfo = audioInfo,
    )
}
