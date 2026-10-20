package com.nuvio.app.features.profiles

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MaxProfileAvatarPixels = 512

@Composable
actual fun rememberCustomProfileAvatarPicker(
    onImagePicked: (ByteArray) -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentOnImagePicked = rememberUpdatedState(onImagePicked)
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val bytes = withContext(Dispatchers.IO) {
                    decodeDownscaledProfileJpeg(context, uri)
                }
                if (bytes != null) {
                    currentOnImagePicked.value(bytes)
                }
            }
        }
    }
    return remember(launcher) {
        {
            launcher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
        }
    }
}

private fun decodeDownscaledProfileJpeg(context: Context, uri: Uri): ByteArray? {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    val boundsDecoded = runCatching {
        resolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        }
    }.isSuccess
    if (!boundsDecoded || bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sampleSize = 1
    while (
        bounds.outWidth / (sampleSize * 2) >= MaxProfileAvatarPixels &&
        bounds.outHeight / (sampleSize * 2) >= MaxProfileAvatarPixels
    ) {
        sampleSize *= 2
    }
    val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    val bitmap = runCatching {
        resolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, decodeOptions)
        }
    }.getOrNull() ?: return null

    val longestEdge = max(bitmap.width, bitmap.height)
    val scaled = if (longestEdge > MaxProfileAvatarPixels) {
        val scale = MaxProfileAvatarPixels.toFloat() / longestEdge
        Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    } else {
        bitmap
    }
    return ByteArrayOutputStream().use { output ->
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, output)
        output.toByteArray()
    }
}
