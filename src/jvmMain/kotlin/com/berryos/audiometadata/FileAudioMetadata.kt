package com.berryos.audiometadata

import com.berryos.audiometadata.io.FileChannelSource
import com.berryos.audiometadata.io.use
import java.io.File

/**
 * Reads metadata from this file.
 *
 * Returns null when the container is not recognised. The source is closed before returning, so
 * the result does not keep a file handle open.
 */
public fun File.readAudioFile(includeArtworks: Boolean = false): AudioFile? {
    if (!isFile) return null
    return FileChannelSource(this, writable = false).use { source -> AudioFile.read(source, includeArtworks) }
}

/** Reads the immutable metadata snapshot from this file, or null for an unknown container. */
public fun File.readAudioMetadata(): AudioMetadata? {
    if (!isFile) return null
    return FileChannelSource(this, writable = false).use { source -> AudioMetadataReader.read(source) }
}

/**
 * Opens this file for editing, runs [edit] and saves the result.
 *
 * Returns false without touching the file when it is missing or not a recognised container.
 */
public fun File.updateAudioFile(includeArtworks: Boolean = false, edit: (AudioFile) -> Unit): Boolean {
    if (!isFile) return false
    return FileChannelSource(this, writable = true).use { source ->
        val audio = AudioFile.read(source, includeArtworks) ?: return@use false
        edit(audio)
        audio.save(source)
        true
    }
}
