package com.nuvio.app.core.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.carousel_scroll_back
import nuvio.composeapp.generated.resources.carousel_scroll_forward
import org.jetbrains.compose.resources.stringResource

/**
 * Hover-reveal paging arrows for a horizontal [LazyRow] carousel.
 *
 * Touch users scroll carousels by dragging, but on desktop a mouse wheel produces vertical
 * deltas that the outer page consumes, which leaves horizontal rows with no obvious way to
 * move. These arrows page the row by one viewport in the tapped direction.
 *
 * The overlay itself never intercepts taps — only the arrow buttons are clickable — and the
 * arrows only appear while the pointer hovers the row (touch devices never hover, so nothing
 * changes there) and only in directions that can actually scroll.
 *
 * Place as the last child of the [Box] wrapping the row, with [Modifier.matchParentSize].
 */
@Composable
fun HorizontalCarouselControls(
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val interactionSource = remember { MutableInteractionSource() }
    var isHovered by remember { mutableStateOf(false) }
    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is HoverInteraction.Enter -> isHovered = true
                is HoverInteraction.Exit -> isHovered = false
            }
        }
    }
    val canScrollBack = listState.canScrollBackward
    val canScrollForward = listState.canScrollForward

    Box(
        modifier = modifier.hoverable(interactionSource = interactionSource),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedVisibility(
            visible = isHovered && canScrollBack,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = NuvioTokens.Space.s8),
        ) {
            CarouselArrowButton(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                contentDescription = stringResource(Res.string.carousel_scroll_back),
                onClick = {
                    scope.launch { listState.animateScrollByPage(pageDirection = -1) }
                },
            )
        }
        AnimatedVisibility(
            visible = isHovered && canScrollForward,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = NuvioTokens.Space.s8),
        ) {
            CarouselArrowButton(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = stringResource(Res.string.carousel_scroll_forward),
                onClick = {
                    scope.launch { listState.animateScrollByPage(pageDirection = 1) }
                },
            )
        }
    }
}

private suspend fun LazyListState.animateScrollByPage(pageDirection: Int) {
    // Content-relative: positive scrolls toward the end of the list in any layout direction.
    val viewportWidth = layoutInfo.viewportSize.width.toFloat()
    if (viewportWidth > 0f) {
        animateScrollBy(pageDirection * viewportWidth)
    }
}

@Composable
private fun CarouselArrowButton(
    imageVector: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    Box(
        modifier = modifier
            .size(NuvioTokens.Space.s40)
            .clip(CircleShape)
            .background(
                color = tokens.colors.surface.copy(alpha = 0.92f),
                shape = CircleShape,
            )
            .clickable(
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            tint = tokens.colors.textPrimary,
            modifier = Modifier.size(tokens.icons.md),
        )
    }
}
