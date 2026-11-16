package com.berryos.audiometadata

/**
 * Marks low-level, format-specific APIs.
 *
 * They are public for advanced use but may change while the unified model is being completed.
 */
@RequiresOptIn(
    message = "Format-specific metadata access is experimental; prefer the AudioFile properties.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.PROPERTY_SETTER,
)
annotation class ExperimentalAudioMetadataApi
