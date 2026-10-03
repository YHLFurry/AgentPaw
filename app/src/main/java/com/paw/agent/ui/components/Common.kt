package com.paw.agent.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.paw.agent.R

/**
 * The empty-state shown before the first message: the paw mark, a headline, and
 * a shortcut into LLM settings when the app is not configured yet.
 */
@Composable
fun EmptyChatState(
    configured: Boolean,
    modifier: Modifier = Modifier,
    onOpenSettings: () -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        PawMark(size = 88.dp)
        Spacer(Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.chat_empty_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.chat_empty_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!configured) {
            Spacer(Modifier.height(24.dp))
            Surface(
                onClick = onOpenSettings,
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Text(
                    text = stringResource(R.string.chat_open_settings),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 14.dp),
                )
            }
        }
    }
}

/**
 * The paw mark, drawn with plain Compose shapes so it scales crisply and picks up
 * the theme colours — the same silhouette as the launcher icon.
 */
@Composable
fun PawMark(
    size: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center,
    ) {
        // Heel pad, sitting in the lower half.
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = size * 0.10f)
                .width(size * 0.58f)
                .height(size * 0.40f)
                .background(color, RoundedCornerShape(percent = 50)),
        )

        // Four toes in an arc above it; the inner pair is slightly taller,
        // matching the launcher icon's proportions.
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = size * 0.12f),
            horizontalArrangement = Arrangement.spacedBy(size * 0.06f),
            verticalAlignment = Alignment.Bottom,
        ) {
            listOf(0.72f, 1f, 1f, 0.72f).forEach { heightFactor ->
                Box(
                    Modifier
                        .width(size * 0.155f)
                        .height(size * 0.21f * heightFactor)
                        .background(color, RoundedCornerShape(percent = 50)),
                )
            }
        }
    }
}

/** Three pulsing dots shown while the model is producing its first tokens. */
@Composable
fun ThinkingIndicator(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "thinking")
    val alpha by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(700),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "alpha",
    )

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) { index ->
            val dotAlpha by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(700, delayMillis = index * 160),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "dot$index",
            )
            Box(
                Modifier
                    .size(7.dp)
                    .alpha(dotAlpha)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape),
            )
        }
    }
}

/** A labelled pill used for statuses like "stopped" or an active provider. */
@Composable
fun StatusPill(
    text: String,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    content: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = container,
        contentColor = content,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}
