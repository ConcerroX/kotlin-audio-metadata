package com.berryos.audiometadata

/** Container format detected for an audio file. */
public enum class AudioFormat {
    MP3,
    FLAC,
    OGG,
    OPUS,
    MP4,
    WAV,
    AIFF,
    UNKNOWN,
}

/** ID3v2 tag versions supported by the MP3 reader. */
public enum class Id3Version(public val majorVersion: Int) {
    V22(2),
    V23(3),
    V24(4),
}

/** Technical properties of the audio stream itself. */
public data class AudioInfo(
    val bitrateKbps: Int? = null,
    val sampleRateHz: Int? = null,
    val channels: Int? = null,
    val bitsPerSample: Int? = null,
    val durationMs: Long? = null,
)

/** ReplayGain values as stored in the file, including their unit suffix. */
public data class ReplayGain(
    val trackGain: String? = null,
    val trackPeak: String? = null,
    val albumGain: String? = null,
    val albumPeak: String? = null,
)

/** Format-independent view of the tags attached to a single audio file. */
public data class AudioMetadata(
    val format: AudioFormat,
    val id3Version: Id3Version? = null,
    val title: String? = null,
    val artists: List<String> = emptyList(),
    val album: String? = null,
    val albumArtist: String? = null,
    val trackNumber: Int? = null,
    val trackTotal: Int? = null,
    val discNumber: Int? = null,
    val discTotal: Int? = null,
    val year: String? = null,
    val genres: List<String> = emptyList(),
    val composers: List<String> = emptyList(),
    val comments: List<String> = emptyList(),
    val lyrics: String? = null,
    val replayGain: ReplayGain? = null,
    val audioInfo: AudioInfo? = null,
)
