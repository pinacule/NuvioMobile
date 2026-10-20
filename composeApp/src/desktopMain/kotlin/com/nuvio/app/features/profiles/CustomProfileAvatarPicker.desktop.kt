package com.nuvio.app.features.profiles

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import java.awt.EventQueue
import java.awt.FileDialog
import java.awt.Frame
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MaxProfileAvatarPixels = 512

@Composable
actual fun rememberCustomProfileAvatarPicker(
    onImagePicked: (ByteArray) -> Unit,
): () -> Unit {
    val scope = rememberCoroutineScope()
    val currentOnImagePicked = rememberUpdatedState(onImagePicked)
    return remember {
        {
            scope.launch {
                val file = withContext(Dispatchers.IO) { chooseProfileImageFile() }
                val bytes = file?.let {
                    withContext(Dispatchers.IO) { downscaleProfileImageToJpeg(it) }
                }
                if (bytes != null) {
                    currentOnImagePicked.value(bytes)
                }
            }
        }
    }
}

private fun chooseProfileImageFile(): File? {
    var chosen: File? = null
    // File dialogs must be shown on the AWT event dispatch thread.
    EventQueue.invokeAndWait {
        val dialog = FileDialog(null as Frame?, "Choose profile picture", FileDialog.LOAD)
        dialog.setFilenameFilter { _, name ->
            val lower = name.lowercase()
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
                lower.endsWith(".png") || lower.endsWith(".webp")
        }
        dialog.isVisible = true
        chosen = dialog.file?.let { name -> File(dialog.directory, name) }
    }
    return chosen
}

private fun downscaleProfileImageToJpeg(file: File): ByteArray? {
    val source = runCatching { ImageIO.read(file) }.getOrNull() ?: return null
    val longestEdge = max(source.width, source.height)
    val targetWidth: Int
    val targetHeight: Int
    if (longestEdge > MaxProfileAvatarPixels) {
        val scale = MaxProfileAvatarPixels.toDouble() / longestEdge
        targetWidth = (source.width * scale).toInt().coerceAtLeast(1)
        targetHeight = (source.height * scale).toInt().coerceAtLeast(1)
    } else {
        targetWidth = source.width
        targetHeight = source.height
    }
    // Always redraw into an RGB image so JPEG encoding works for PNG/WebP
    // sources with alpha channels too.
    val image = BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB)
    val graphics = image.createGraphics()
    try {
        graphics.setRenderingHint(
            RenderingHints.KEY_INTERPOLATION,
            RenderingHints.VALUE_INTERPOLATION_BILINEAR,
        )
        graphics.drawImage(source, 0, 0, targetWidth, targetHeight, null)
    } finally {
        graphics.dispose()
    }
    return ByteArrayOutputStream().use { output ->
        if (ImageIO.write(image, "jpg", output)) output.toByteArray() else null
    }
}
