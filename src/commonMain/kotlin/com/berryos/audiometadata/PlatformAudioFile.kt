package com.berryos.audiometadata

import com.berryos.audiometadata.io.RandomAccessSource

/**
 * Opens containers whose readers live in the JVM source set.
 *
 * Kept as an expect/actual hook so [AudioFile.read] exposes the same API on every target; on
 * targets without these readers the formats simply come back as null.
 */
internal expect fun openPlatformAudioFile(
    source: RandomAccessSource,
    format: AudioFormat,
    includeArtworks: Boolean,
): AudioFile?
