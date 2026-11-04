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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.paw.agent.R
import com.paw.agent.ui.components.adaptive.AppIcon
import com.paw.agent.ui.components.adaptive.AppSurface
import com.paw.agent.ui.components.adaptive.AppText
import com.paw.agent.ui.theme.AppTheme

/**
 * The empty-state shown before the first message: the paw mark, a headline,
 * a shortcut into LLM settings when not configured, and quick starter suggestion chips when ready.
 */
@Composable
fun EmptyChatState(
    configured: Boolean,
    modifier: Modifier = Modifier,
    onOpenSettings: () -> Unit,
    onSuggestionClick: (String) -> Unit = {},
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        PawMark(size = 84.dp)
        Spacer(Modifier.height(20.dp))
        AppText(
            text = if (configured) stringResource(R.string.chat_empty_title_configured) else stringResource(R.string.chat_empty_title),
            style = AppTheme.typography.headlineSmall,
            color = AppTheme.colors.onSurface,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        AppText(
            text = if (configured) stringResource(R.string.chat_empty_subtitle_configured) else stringResource(R.string.chat_empty_subtitle),
            style = AppTheme.typography.bodyMedium,
            color = AppTheme.colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (!configured) {
            Spacer(Modifier.height(12.dp))
            AppText(
                text = stringResource(R.string.chat_setup_help),
                style = AppTheme.typography.bodySmall,
                color = AppTheme.colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            AppSurface(
                onClick = onOpenSettings,
                shape = AppTheme.shapes.large,
                color = AppTheme.colors.primaryContainer,
                contentColor = AppTheme.colors.onPrimaryContainer,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 14.dp),
                ) {
                    AppIcon(
                        imageVector = Icons.Outlined.Settings,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    AppText(
                        text = stringResource(R.string.chat_configure_model),
                        style = AppTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        } else {
            Spacer(Modifier.height(24.dp))
            val suggestions = listOf(
                "返回手机桌面" to "返回桌面",
                "打开系统设置" to "打开系统设置",
                "联网搜索最新资讯" to "联网搜索最新科技新闻",
            )
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                suggestions.forEach { (label, prompt) ->
                    AppSurface(
                        onClick = { onSuggestionClick(prompt) },
                        shape = RoundedCornerShape(16.dp),
                        color = AppTheme.colors.surfaceContainer,
                        contentColor = AppTheme.colors.onSurface,
                        modifier = Modifier.fillMaxWidth(0.88f),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        ) {
                            AppIcon(
                                imageVector = Icons.Outlined.AutoAwesome,
                                contentDescription = null,
                                tint = AppTheme.colors.primary,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                            AppText(
                                text = label,
                                style = AppTheme.typography.bodyMedium,
                                color = AppTheme.colors.onSurface,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 手绘爪印与启动图标共用同一组矢量路径，所有尺寸与主题保持一致。 */
@Composable
fun PawMark(
    size: Dp,
    modifier: Modifier = Modifier,
    color: Color = AppTheme.colors.primary,
) {
    androidx.compose.foundation.Image(
        painter = androidx.compose.ui.res.painterResource(R.drawable.ic_paw),
        contentDescription = null,
        colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(color),
        modifier = modifier.size(size),
    )
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
