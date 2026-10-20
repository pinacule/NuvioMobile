package com.nuvio.app.features.profiles

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.uikit.LocalUIViewController
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSData
import platform.PhotosUI.PHPickerConfiguration
import platform.PhotosUI.PHPickerFilter
import platform.PhotosUI.PHPickerResult
import platform.PhotosUI.PHPickerViewController
import platform.PhotosUI.PHPickerViewControllerDelegateProtocol
import platform.UIKit.UIGraphicsBeginImageContextWithOptions
import platform.UIKit.UIGraphicsEndImageContext
import platform.UIKit.UIGraphicsGetImageFromCurrentImageContext
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.darwin.NSObject
import platform.posix.memcpy

private const val MaxProfileAvatarPixels = 512.0

@OptIn(ExperimentalForeignApi::class)
private class CustomAvatarPickerDelegate(
    private val onImagePicked: (ByteArray) -> Unit,
) : NSObject(), PHPickerViewControllerDelegateProtocol {
    override fun picker(picker: PHPickerViewController, didFinishPicking: List<*>) {
        picker.dismissViewControllerAnimated(flag = true, completion = null)
        val result = didFinishPicking.firstOrNull() as? PHPickerResult ?: return
        result.itemProvider.loadDataRepresentationForTypeIdentifier(
            typeIdentifier = "public.image",
        ) { data, _ ->
            val imageData = data ?: return@loadDataRepresentationForTypeIdentifier
            val image = UIImage.imageWithData(imageData)
                ?: return@loadDataRepresentationForTypeIdentifier
            profileAvatarJpegBytes(image)?.let(onImagePicked)
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun profileAvatarJpegBytes(image: UIImage): ByteArray? {
    val width = image.size.width
    val height = image.size.height
    val longestEdge = maxOf(width, height)
    val resized = if (longestEdge > MaxProfileAvatarPixels) {
        val scale = MaxProfileAvatarPixels / longestEdge
        val targetWidth = width * scale
        val targetHeight = height * scale
        UIGraphicsBeginImageContextWithOptions(
            CGSizeMake(targetWidth, targetHeight),
            false,
            1.0,
        )
        image.drawInRect(CGRectMake(0.0, 0.0, targetWidth, targetHeight))
        val scaledImage = UIGraphicsGetImageFromCurrentImageContext()
        UIGraphicsEndImageContext()
        scaledImage
    } else {
        image
    } ?: return null
    val jpegData = UIImageJPEGRepresentation(resized, 0.85) ?: return null
    return jpegData.toByteArray()
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    if (size == 0) return ByteArray(0)
    val source = bytes ?: return ByteArray(0)
    return ByteArray(size).apply {
        usePinned { pinned ->
            memcpy(pinned.addressOf(0), source, size.convert())
        }
    }
}

@Composable
actual fun rememberCustomProfileAvatarPicker(
    onImagePicked: (ByteArray) -> Unit,
): () -> Unit {
    val parentViewController = LocalUIViewController.current
    val currentOnImagePicked = rememberUpdatedState(onImagePicked)
    // The picker delegate is weakly referenced by the view controller, so the
    // remembered instance must stay alive for as long as this composable is
    // in the tree (i.e. while the picker is on screen).
    val delegate = remember {
        CustomAvatarPickerDelegate { bytes -> currentOnImagePicked.value(bytes) }
    }
    return remember(parentViewController, delegate) {
        {
            val configuration = PHPickerConfiguration()
            configuration.selectionLimit = 1
            configuration.filter = PHPickerFilter.imagesFilter()
            val picker = PHPickerViewController(configuration = configuration)
            picker.delegate = delegate
            parentViewController.presentViewController(
                viewControllerToPresent = picker,
                animated = true,
                completion = null,
            )
        }
    }
}
