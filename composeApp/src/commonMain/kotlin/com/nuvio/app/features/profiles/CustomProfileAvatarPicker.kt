package com.nuvio.app.features.profiles

import androidx.compose.runtime.Composable

/**
 * Remembers a launcher that opens the platform photo picker. When the user
 * picks an image, [onImagePicked] receives JPEG bytes already downscaled to
 * at most ~512px on the long edge. Returns the function that opens the picker;
 * cancelling the picker simply never invokes [onImagePicked].
 */
@Composable
internal expect fun rememberCustomProfileAvatarPicker(
    onImagePicked: (ByteArray) -> Unit,
): () -> Unit
