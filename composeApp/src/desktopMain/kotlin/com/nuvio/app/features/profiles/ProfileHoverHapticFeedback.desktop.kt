package com.nuvio.app.features.profiles

/** Desktop: no haptic hardware, no-op. */
internal actual object ProfileHoverHapticFeedback {
    actual fun prepare() = Unit

    actual fun perform() = Unit

    actual fun release() = Unit
}
