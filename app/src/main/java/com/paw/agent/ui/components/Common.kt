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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.paw.agent.R
import com.paw.agent.ui.components.adaptive.AppSurface
import com.paw.agent.ui.components.adaptive.AppText
import com.paw.agent.ui.theme.AppTheme

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
        AppText(
            text = stringResource(R.string.chat_empty_title),
            style = AppTheme.typography.headlineSmall,
            color = AppTheme.colors.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        AppText(
            text = stringResource(R.string.chat_empty_subtitle),
            style = AppTheme.typography.bodyMedium,
            color = AppTheme.colors.onSurfaceVariant,
        )
        if (!configured) {
            Spacer(Modifier.height(24.dp))
            AppSurface(
                onClick = onOpenSettings,
                shape = AppTheme.shapes.large,
                color = AppTheme.colors.primaryContainer,
                contentColor = AppTheme.colors.onPrimaryContainer,
            ) {
                AppText(
                    text = stringResource(R.string.chat_open_settings),
                    style = AppTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 14.dp),
                )
            }
        }
    }
}

/**
 * The paw mark, drawn with organic bezier curves matching the launcher icon's cute silhouette
 * and brand logo heart-spark emblem. Scales crisply across all sizes.
 */
@Composable
fun PawMark(
    size: Dp,
    modifier: Modifier = Modifier,
    color: Color = AppTheme.colors.primary,
) {
    androidx.compose.foundation.Canvas(
        modifier = modifier.size(size),
    ) {
        val w = this.size.width
        val h = this.size.height

        fun pxX(x: Float): Float = x / 108f * w
        fun pxY(y: Float): Float = y / 108f * h

        // 1. 左外侧可爱倾斜趾垫
        val path1 = androidx.compose.ui.graphics.Path().apply {
            moveTo(pxX(33.5f), pxY(41.0f))
            cubicTo(pxX(30.2f), pxY(36.8f), pxX(32.2f), pxY(31.2f), pxX(36.5f), pxY(29.2f))
            cubicTo(pxX(40.8f), pxY(27.2f), pxX(45.0f), pxY(30.5f), pxX(45.0f), pxY(35.8f))
            cubicTo(pxX(45.0f), pxY(41.2f), pxX(39.8f), pxY(45.2f), pxX(35.8f), pxY(44.2f))
            cubicTo(pxX(34.6f), pxY(43.8f), pxX(33.8f), pxY(42.5f), pxX(33.5f), pxY(41.0f))
            close()
        }
        drawPath(path1, color)

        // 2. 左内侧饱满直立趾垫
        val path2 = androidx.compose.ui.graphics.Path().apply {
            moveTo(pxX(46.5f), pxY(33.0f))
            cubicTo(pxX(44.2f), pxY(28.8f), pxX(46.2f), pxY(22.5f), pxX(50.5f), pxY(22.5f))
            cubicTo(pxX(54.8f), pxY(22.5f), pxX(57.8f), pxY(27.8f), pxX(56.8f), pxY(33.0f))
            cubicTo(pxX(55.8f), pxY(38.2f), pxX(49.5f), pxY(39.2f), pxX(47.5f), pxY(37.2f))
            cubicTo(pxX(46.8f), pxY(36.2f), pxX(46.6f), pxY(34.5f), pxX(46.5f), pxY(33.0f))
            close()
        }
        drawPath(path2, color)

        // 3. 右内侧饱满直立趾垫
        val path3 = androidx.compose.ui.graphics.Path().apply {
            moveTo(pxX(61.5f), pxY(33.0f))
            cubicTo(pxX(60.5f), pxY(27.8f), pxX(63.5f), pxY(22.5f), pxX(67.8f), pxY(22.5f))
            cubicTo(pxX(72.1f), pxY(22.5f), pxX(74.1f), pxY(28.8f), pxX(71.8f), pxY(33.0f))
            cubicTo(pxX(70.0f), pxY(38.5f), pxX(64.0f), pxY(39.2f), pxX(62.0f), pxY(37.2f))
            cubicTo(pxX(61.6f), pxY(35.5f), pxX(61.5f), pxY(34.2f), pxX(61.5f), pxY(33.0f))
            close()
        }
        drawPath(path3, color)

        // 4. 右外侧可爱倾斜趾垫
        val path4 = androidx.compose.ui.graphics.Path().apply {
            moveTo(pxX(74.5f), pxY(41.0f))
            cubicTo(pxX(74.2f), pxY(42.5f), pxX(73.4f), pxY(43.8f), pxX(72.2f), pxY(44.2f))
            cubicTo(pxX(68.2f), pxY(45.2f), pxX(63.0f), pxY(41.2f), pxX(63.0f), pxY(35.8f))
            cubicTo(pxX(63.0f), pxY(30.5f), pxX(67.2f), pxY(27.2f), pxX(71.5f), pxY(29.2f))
            cubicTo(pxX(75.8f), pxY(31.2f), pxX(77.8f), pxY(36.8f), pxX(74.5f), pxY(41.0f))
            close()
        }
        drawPath(path4, color)

        // 5. 主掌垫 (心萌三瓣饱满大肉垫)
        val mainPad = androidx.compose.ui.graphics.Path().apply {
            moveTo(pxX(54.0f), pxY(50.0f))
            cubicTo(pxX(61.0f), pxY(43.5f), pxX(71.5f), pxY(44.5f), pxX(75.8f), pxY(51.8f))
            cubicTo(pxX(80.5f), pxY(60.2f), pxX(75.8f), pxY(71.0f), pxX(68.0f), pxY(75.0f))
            cubicTo(pxX(63.8f), pxY(77.2f), pxX(57.5f), pxY(75.5f), pxX(54.0f), pxY(73.0f))
            cubicTo(pxX(50.5f), pxY(75.5f), pxX(44.2f), pxY(77.2f), pxX(40.0f), pxY(75.0f))
            cubicTo(pxX(32.2f), pxY(71.0f), pxX(27.5f), pxY(60.2f), pxX(32.2f), pxY(51.8f))
            cubicTo(pxX(36.5f), pxY(44.5f), pxX(47.0f), pxY(43.5f), pxX(54.0f), pxY(50.0f))
            close()
        }
        drawPath(mainPad, color)

        // 6. 融入本 APP 特色 Logo 元素：掌心软萌光印与科技晶芯（大尺寸时渲染精致细节）
        if (size >= 30.dp) {
            val sparkPath = androidx.compose.ui.graphics.Path().apply {
                moveTo(pxX(54.0f), pxY(59.5f))
                cubicTo(pxX(55.0f), pxY(61.5f), pxX(56.5f), pxY(62.5f), pxX(58.5f), pxY(63.0f))
                cubicTo(pxX(56.5f), pxY(63.5f), pxX(55.0f), pxY(64.5f), pxX(54.0f), pxY(66.5f))
                cubicTo(pxX(53.0f), pxY(64.5f), pxX(51.5f), pxY(63.5f), pxX(49.5f), pxY(63.0f))
                cubicTo(pxX(51.5f), pxY(62.5f), pxX(53.0f), pxY(61.5f), pxX(54.0f), pxY(59.5f))
                close()
            }
            drawPath(sparkPath, color.copy(alpha = 0.35f))
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
                    .background(AppTheme.colors.onSurfaceVariant, CircleShape),
            )
        }
    }
}

/** A labelled pill used for statuses like "stopped" or an active provider. */
@Composable
fun StatusPill(
    text: String,
    modifier: Modifier = Modifier,
    container: Color = AppTheme.colors.surfaceContainerHighest,
    content: Color = AppTheme.colors.onSurfaceVariant,
) {
    AppSurface(
        modifier = modifier,
        shape = AppTheme.shapes.small,
        color = container,
        contentColor = content,
    ) {
        AppText(
            text = text,
            style = AppTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}
