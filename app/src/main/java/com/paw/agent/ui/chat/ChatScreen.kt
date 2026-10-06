package com.paw.agent.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.paw.agent.R
import com.paw.agent.core.llm.LlmConfig
import com.paw.agent.ui.components.EmptyChatState
import com.paw.agent.ui.components.PawMark
import com.paw.agent.ui.components.adaptive.AppButton
import com.paw.agent.ui.components.adaptive.AppCard
import com.paw.agent.ui.components.adaptive.AppIcon
import com.paw.agent.ui.components.adaptive.AppIconButton
import com.paw.agent.ui.components.adaptive.AppOutlinedButton
import com.paw.agent.ui.components.adaptive.AppScaffold
import com.paw.agent.ui.components.adaptive.AppSurface
import com.paw.agent.ui.components.adaptive.AppText
import com.paw.agent.ui.components.adaptive.AppTextField
import com.paw.agent.ui.components.adaptive.AppTopAppBar
import com.paw.agent.ui.theme.AppTheme

@Composable
fun ChatScreen(
    messages: List<com.paw.agent.core.model.Message>,
    draft: String,
    isGenerating: Boolean,
    configReady: Boolean,
    modelLabel: String,
    onDraftChange: (String) -> Unit,
    onSendOrStop: () -> Unit,
    onOpenSettings: () -> Unit,
    onNewConversation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    // Keep the newest message in view as tokens stream in without animation conflicts.
    LaunchedEffect(messages.size, messages.lastOrNull()?.content?.length) {
        if (messages.isNotEmpty()) {
            val isNearBottom = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let {
                it >= messages.size - 2
            } ?: true
            if (isNearBottom) {
                listState.scrollToItem(messages.lastIndex)
            }
        }
    }

    AppScaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            AppTopAppBar(
                title = stringResource(R.string.chat_title),
                subtitle = modelLabel,
                // Miuix renders a fixed title/subtitle pair, so the paw mark goes
                // into its leading slot instead of inside the title content.
                navigationIcon = {
                    if (AppTheme.isMiuix) {
                        PawMark(
                            size = 26.dp,
                            color = AppTheme.colors.onPrimary,
                            modifier = Modifier.padding(start = 16.dp, end = 4.dp),
                        )
                    }
                },
                titleContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PawMark(size = 26.dp, color = AppTheme.colors.onPrimary)
                        Spacer(Modifier.size(10.dp))
                        Column {
                            AppText(
                                text = stringResource(R.string.chat_title),
                                style = AppTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (modelLabel.isNotBlank()) {
                                AppText(
                                    text = modelLabel,
                                    style = AppTheme.typography.labelSmall,
                                    color = AppTheme.colors.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                },
                actions = {
                    AppIconButton(onClick = onNewConversation) {
                        AppIcon(
                            imageVector = Icons.Outlined.EditNote,
                            contentDescription = stringResource(R.string.chat_new_conversation),
                        )
                    }
                    AppIconButton(onClick = onOpenSettings) {
                        AppIcon(
                            imageVector = Icons.Outlined.Settings,
                            contentDescription = stringResource(R.string.chat_open_settings),
                        )
                    }
                },
                color = AppTheme.colors.surfaceContainer,
            )
        },
        bottomBar = {
            Composer(
                draft = draft,
                isGenerating = isGenerating,
                configReady = configReady,
                enabled = draft.isNotBlank() || isGenerating,
                onDraftChange = onDraftChange,
                onSendOrStop = onSendOrStop,
                onOpenSettings = onOpenSettings,
            )
        },
        containerColor = AppTheme.colors.background,
    ) { padding ->
        val context = androidx.compose.ui.platform.LocalContext.current
        val permTick = androidx.compose.runtime.remember {
            androidx.compose.runtime.mutableIntStateOf(0)
        }
        androidx.lifecycle.compose.LifecycleEventEffect(
            androidx.lifecycle.Lifecycle.Event.ON_RESUME,
        ) {
            permTick.intValue++
        }
        val isAccessibilityEnabled = androidx.compose.runtime.remember(permTick.intValue) {
            com.paw.agent.device.DevicePermissionManager.isAccessibilityServiceEnabled(context)
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (!isAccessibilityEnabled) {
                AppCard(
                    onClick = {
                        com.paw.agent.device.DevicePermissionManager.openAccessibilitySettings(context)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    cornerRadius = 12.dp,
                    containerColor = AppTheme.colors.tertiaryContainer,
                    contentColor = AppTheme.colors.onTertiaryContainer,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AppText(
                            text = stringResource(R.string.chat_accessibility_banner),
                            style = AppTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        AppOutlinedButton(
                            onClick = {
                                com.paw.agent.device.DevicePermissionManager.openAccessibilitySettings(context)
                            },
                        ) {
                            AppText(
                                text = stringResource(R.string.chat_accessibility_enable),
                                style = AppTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                if (messages.isEmpty()) {
                    EmptyChatState(
                        configured = configReady,
                        modifier = Modifier.align(Alignment.Center),
                        onOpenSettings = onOpenSettings,
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 12.dp),
                    ) {
                        // 不以 message.id 作为 key：部分 OpenAI 兼容后端会返回重复或
                        // 空的 tool-call id，重复 key 会让 LazyColumn 直接抛
                        // "Key was already used" 崩掉整个对话界面。消息列表只追加
                        // 不重排，默认的位置 key 足够且更稳。
                        items(items = messages) { message ->
                            if (message.role != com.paw.agent.core.model.MessageRole.ASSISTANT ||
                                message.content.isNotBlank() ||
                                message.status == com.paw.agent.core.model.MessageStatus.STREAMING
                            ) {
                                MessageBubble(message = message)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Composer(
    draft: String,
    isGenerating: Boolean,
    configReady: Boolean,
    enabled: Boolean,
    onDraftChange: (String) -> Unit,
    onSendOrStop: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    AppSurface(
        color = AppTheme.colors.surfaceContainer,
        contentColor = AppTheme.colors.onSurface,
        shape = RectangleShape,
        tonalElevation = 3.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AppTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    modifier = Modifier.weight(1f),
                    placeholder = stringResource(R.string.chat_input_hint),
                    cornerRadius = 24.dp,
                    maxLines = 5,
                )

                AppSurface(
                    onClick = {
                        if (!configReady && !isGenerating) {
                            onOpenSettings()
                        } else {
                            onSendOrStop()
                        }
                    },
                    enabled = enabled || !configReady,
                    shape = CircleShape,
                    color = if (isGenerating) {
                        AppTheme.colors.errorContainer
                    } else {
                        AppTheme.colors.primary
                    },
                    contentColor = if (isGenerating) {
                        AppTheme.colors.onErrorContainer
                    } else {
                        AppTheme.colors.onPrimary
                    },
                ) {
                    Box(
                        Modifier.size(52.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        AppIcon(
                            imageVector = if (isGenerating) {
                                Icons.Filled.Stop
                            } else {
                                Icons.AutoMirrored.Filled.Send
                            },
                            contentDescription = stringResource(
                                if (isGenerating) R.string.chat_stop else R.string.chat_send,
                            ),
                        )
                    }
                }
            }
        }
    }
}

private val RectangleShape = RoundedCornerShape(0.dp)
