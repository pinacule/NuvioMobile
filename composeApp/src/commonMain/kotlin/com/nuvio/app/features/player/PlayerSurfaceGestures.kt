package com.nuvio.app.features.player

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs

internal fun Modifier.playerSurfaceTapGestures(
    layoutSize: IntSize,
    playbackGesturesEnabled: Boolean,
    playerControlsLockedState: State<Boolean>,
    onSurfaceTap: State<(Offset) -> Unit>,
    onSurfaceDoubleTap: State<(Offset) -> Unit>,
    activateHoldToSpeedState: State<() -> Unit>,
    deactivateHoldToSpeedState: State<() -> Unit>,
    revealLockedOverlayState: State<() -> Unit>,
): Modifier =
    pointerInput(layoutSize, playbackGesturesEnabled) {
        detectTapGestures(
            onPress = {
                tryAwaitRelease()
                deactivateHoldToSpeedState.value()
            },
            onTap = { offset -> onSurfaceTap.value(offset) },
            onDoubleTap = if (playbackGesturesEnabled) {
                { offset -> onSurfaceDoubleTap.value(offset) }
            } else null,
            onLongPress = if (playbackGesturesEnabled) {
                {
                    if (playerControlsLockedState.value) {
                        revealLockedOverlayState.value()
                    } else {
                        activateHoldToSpeedState.value()
                    }
                }
            } else null,
        )
    }

internal fun Modifier.playerSurfaceDragGestures(
    gestureController: PlayerGestureController?,
    layoutSize: IntSize,
    playbackGesturesEnabled: Boolean,
    sideGestureSystemEdgeExclusionPx: Float,
    playerControlsLockedState: State<Boolean>,
    touchGesturesEnabledState: State<Boolean>,
    isHoldToSpeedGestureActiveState: State<Boolean>,
    showBrightnessFeedbackState: State<(Float) -> Unit>,
    revealLockedOverlayState: State<() -> Unit>,
    volumeBrightnessGesturesEnabledState: State<Boolean>? = null,
): Modifier =
    pointerInput(gestureController, layoutSize, sideGestureSystemEdgeExclusionPx, playbackGesturesEnabled) {
        if (!playbackGesturesEnabled) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown()
            if (playerControlsLockedState.value) {
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    change.consume()
                }
                return@awaitEachGesture
            }
            if (!touchGesturesEnabledState.value) {
                return@awaitEachGesture
            }
            // While a panel like the speed modal is open, vertical swipes must not
            // change brightness.
            val volumeBrightnessGesturesEnabled =
                volumeBrightnessGesturesEnabledState?.value ?: true
            val controller = gestureController
            val height = size.height.toFloat().takeIf { it > 0f } ?: return@awaitEachGesture
            val sideGestureEdgeExclusionPx = sideGestureSystemEdgeExclusionPx
                .coerceAtMost(height * 0.25f)
            val isInSideGestureSystemEdge =
                down.position.y <= sideGestureEdgeExclusionPx ||
                    down.position.y >= height - sideGestureEdgeExclusionPx
            val region = if (isInSideGestureSystemEdge) null else PlayerSideGesture.Brightness

            val initialBrightness = if (region == PlayerSideGesture.Brightness) {
                controller?.currentBrightness()
            } else {
                null
            }

            var totalDx = 0f
            var totalDy = 0f
            var gestureMode: PlayerGestureMode? = null
            var verticalGestureActivationDy = 0f

            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break

                val delta = change.position - change.previousPosition
                totalDx += delta.x
                totalDy += delta.y

                if (gestureMode == null) {
                    val holdToSpeedActive = isHoldToSpeedGestureActiveState.value
                    val verticalGestureActivationSlop = maxOf(
                        viewConfiguration.touchSlop * PlayerVerticalGestureTouchSlopMultiplier,
                        height * PlayerVerticalGestureMinHeightFraction,
                    )
                    val verticalDominant =
                        !holdToSpeedActive &&
                            abs(totalDy) > verticalGestureActivationSlop &&
                            abs(totalDy) > abs(totalDx) * PlayerVerticalGestureDominanceRatio

                    gestureMode = when {
                        verticalDominant && region == PlayerSideGesture.Brightness && initialBrightness != null && volumeBrightnessGesturesEnabled -> {
                            verticalGestureActivationDy = totalDy
                            PlayerGestureMode.Brightness
                        }

                        else -> null
                    }

                    if (gestureMode == null) {
                        continue
                    }
                }

                when (gestureMode) {
                    PlayerGestureMode.Brightness -> {
                        val activeTotalDy = totalDy - verticalGestureActivationDy
                        val gestureDeltaFraction =
                            (-activeTotalDy / height) * PlayerVerticalGestureSensitivity
                        controller?.setBrightness((initialBrightness ?: 0f) + gestureDeltaFraction)
                            ?.let(showBrightnessFeedbackState.value)
                    }
                }
                change.consume()
            }
        }
    }
