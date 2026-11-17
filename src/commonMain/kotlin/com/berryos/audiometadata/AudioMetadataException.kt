package com.berryos.audiometadata

/**
 * Thrown when a file was recognised as a supported container but its metadata could not be read
 * or written.
 *
 * Unrecognised containers are reported by returning null from [AudioMetadataReader] and
 * [AudioFile.read] instead of throwing.
 */
public class AudioMetadataException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
