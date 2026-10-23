package com.paw.agent.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Security
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.foundation.lazy.LazyRow
import com.paw.agent.core.agent.breakpoint.TaskBreakpoint
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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
import com.paw.agent.ui.components.adaptive.AppTopBarAction
import com.paw.agent.ui.components.adaptive.LocalTopBarClearance
import com.paw.agent.ui.theme.AppTheme

@Composable
fun ChatScreen(
    messages: List<com.paw.agent.core.model.Message>,
    draft: String,
    isGenerating: Boolean,
    configReady: Boolean,
    modelLabel: String,
    splitVisionLanguageMode: Boolean = false,
    activeBreakpoint: TaskBreakpoint? = null,
    isInitialized: Boolean = true,
    storageError: String? = null,
    images: List<String> = emptyList(),
    importingImages: Boolean = false,
    attachmentError: Boolean = false,
    onImagesPicked: (List<String>) -> Unit = {},
    onRemoveImage: (Int) -> Unit = {},
    onDraftChange: (String) -> Unit,
    onSendOrStop: () -> Unit,
    onResumeBreakpoint: () -> Unit = {},
    onConfirmRiskAction: () -> Unit = {},
    onDismissBreakpoint: () -> Unit = {},
    onOpenSettings: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSkills: () -> Unit,
    onNewConversation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        onImagesPicked(uris.map { it.toString() })
    }
    var selectedFilterIndex by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }

    AppScaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            AppTopAppBar(
                title = stringResource(R.string.chat_title),
                subtitle = modelLabel,
                // 流式对话页用列表自身的位置驱动：滚过一条就点亮玻璃材质，回到顶部则收起。
                isContentScrolled = listState.canScrollBackward,
                // Miuix 的玻璃顶栏把标题居中排，四颗玻璃胶囊按钮已经占掉右侧一整排，
                // 前导位再放爪印会把标题挤到截断；Material 仍把爪印画进标题里。
                navigationIcon = {},
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
                    AppTopBarAction(onClick = onOpenHistory) {
                        AppIcon(
                            imageVector = Icons.Default.History,
                            contentDescription = stringResource(R.string.history_title),
                        )
                    }
                    AppTopBarAction(onClick = onOpenSkills) {
                        AppIcon(
                            imageVector = Icons.Outlined.Build,
                            contentDescription = stringResource(R.string.skills_title),
                        )
                    }
                    AppTopBarAction(onClick = onNewConversation) {
                        AppIcon(
                            imageVector = Icons.Outlined.EditNote,
                            contentDescription = stringResource(R.string.chat_new_conversation),
                        )
                    }
                    AppTopBarAction(onClick = onOpenSettings) {
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
                activeBreakpoint = activeBreakpoint,
                isInitialized = isInitialized,
                images = images,
                importingImages = importingImages,
                attachmentError = attachmentError,
                onPickImages = { imagePicker.launch(arrayOf("image/*")) },
                onRemoveImage = onRemoveImage,
                enabled = ((draft.isNotBlank() || images.isNotEmpty()) && isInitialized && !importingImages) || isGenerating,
                onDraftChange = onDraftChange,
                onSendOrStop = onSendOrStop,
                onResumeBreakpoint = onResumeBreakpoint,
                onConfirmRiskAction = onConfirmRiskAction,
                onDismissBreakpoint = onDismissBreakpoint,
                onOpenSettings = onOpenSettings,
            )
        },
        containerColor = AppTheme.colors.background,
    ) { padding ->
        val context = androidx.compose.ui.platform.LocalContext.current
        val resources = androidx.compose.ui.platform.LocalResources.current
        val coroutineScope = rememberCoroutineScope()
        val permTick = remember { mutableIntStateOf(0) }
        val notificationPermissionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
        ) { _ ->
            permTick.intValue++
        }
        androidx.lifecycle.compose.LifecycleEventEffect(
            androidx.lifecycle.Lifecycle.Event.ON_RESUME,
        ) {
            // 回到前台时重新探测 Root 授权状态（ISSUE-202）：
            // 用户可能在系统/Magisk 里刚完成授权，而 Root 探测缓存不会自动过期。
            // 先刷新再 bump，让下方 produceState 读取到最新结果。
            coroutineScope.launch {
                com.paw.agent.device.DevicePermissionManager.refreshRootAvailability()
                permTick.intValue++
            }
        }
        val isAccessibilityEnabled = remember(permTick.intValue) {
            com.paw.agent.device.DevicePermissionManager.isAccessibilityServiceEnabled(context)
        }
        // Root 探测可能启动 su 进程并等待（最坏约 1.6s），放 IO 线程执行，
        // 避免阻塞界面合成；刷新由 ON_RESUME 与 Root 模式开关触发。
        val isRootAvailable by produceState(initialValue = false, permTick.intValue) {
            value = withContext(Dispatchers.IO) {
                com.paw.agent.device.DevicePermissionManager.isRootAvailable()
            }
        }
        val shizukuStatus by com.paw.agent.device.DevicePermissionManager.observeShizukuState()
            .collectAsStateWithLifecycle()
        val hasShizukuPermission = shizukuStatus == com.paw.agent.device.shizuku.ShizukuStatus.GRANTED
        val hasOverlayPermission = remember(permTick.intValue) {
            com.paw.agent.device.DevicePermissionManager.canDrawOverlays(context)
        }
        val hasNotificationPermission = remember(permTick.intValue) {
            com.paw.agent.device.DevicePermissionManager.hasNotificationPermission(context)
        }
        val hasAnyExecutionEngine = isRootAvailable || hasShizukuPermission || isAccessibilityEnabled
        val agentExecutionState by com.paw.agent.ui.floating.AgentExecutionController.state.collectAsStateWithLifecycle()
        // 顶栏让位只对"真正占了头部的内容"生效：提示卡和分流条不在可滚动列表里，
        // 得自己让开玻璃顶栏；两者都没有时头部为零高度，消息列表照常从顶栏下方穿过。
        val showRootDashboard = isRootAvailable
        val showTopNotice = (!hasAnyExecutionEngine || !hasOverlayPermission || !hasNotificationPermission) && !showRootDashboard
        val showTopHeader = showRootDashboard || showTopNotice || storageError != null || (splitVisionLanguageMode && messages.isNotEmpty())
        val showStreamFilter = splitVisionLanguageMode && messages.isNotEmpty()
        val filteredMessages = remember(messages, selectedFilterIndex, splitVisionLanguageMode) {
            if (!splitVisionLanguageMode) messages
            else when (selectedFilterIndex) {
                1 -> messages.filter {
                    // 思考与语言流 (Language & CoT)
                    it.isUser || (it.isAssistant && it.images.isEmpty())
                }
                2 -> messages.filter {
                    // 视觉与动作流 (Vision & Actions)
                    it.role == com.paw.agent.core.model.MessageRole.TOOL || it.images.isNotEmpty() || it.content.contains("[Current Screen Observation]")
                }
                else -> messages
            }
        }
        LaunchedEffect(filteredMessages.size, filteredMessages.lastOrNull()?.content?.length, selectedFilterIndex) {
            if (filteredMessages.isNotEmpty()) {
                val isNearBottom = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let {
                    it >= filteredMessages.size - 2
                } ?: true
                if (isNearBottom) listState.scrollToItem(filteredMessages.lastIndex)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (showTopHeader) {
              Column(
                  modifier = Modifier
                      .fillMaxWidth()
                      .padding(top = LocalTopBarClearance.current),
              ) {
            storageError?.let {
                AppText(it, color = AppTheme.colors.error, modifier = Modifier.fillMaxWidth().padding(12.dp))
            }
            if (showRootDashboard) {
                RootAuthDashboard(
                    shizukuGranted = hasShizukuPermission,
                    accessibilityEnabled = isAccessibilityEnabled,
                    overlayEnabled = hasOverlayPermission,
                    agentState = agentExecutionState,
                    onShizukuClick = {
                        val dispatched = com.paw.agent.device.DevicePermissionManager.openShizukuApp(context)
                        if (!dispatched) {
                            android.widget.Toast.makeText(context, resources.getString(R.string.chat_shizuku_missing), android.widget.Toast.LENGTH_SHORT).show()
                        }
                    },
                    onAccessibilityClick = {
                        coroutineScope.launch {
                            if (!isAccessibilityEnabled) {
                                val ok = com.paw.agent.device.DevicePermissionManager.enableAccessibilityViaRoot(context)
                                if (ok) {
                                    permTick.intValue++
                                    android.widget.Toast.makeText(context, resources.getString(R.string.chat_accessibility_root_enabled), android.widget.Toast.LENGTH_SHORT).show()
                                } else {
                                    com.paw.agent.device.DevicePermissionManager.openAccessibilitySettings(context)
                                }
                            } else {
                                android.widget.Toast.makeText(context, resources.getString(R.string.chat_accessibility_running), android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    onOverlayClick = {
                        if (!hasOverlayPermission) {
                            val isXiaomi = com.paw.agent.device.DevicePermissionManager.isXiaomiOrHyperOs()
                            if (isXiaomi) {
                                com.paw.agent.device.DevicePermissionManager.openXiaomiPermissionsSettings(context)
                            } else {
                                com.paw.agent.device.DevicePermissionManager.openOverlaySettings(context)
                            }
                        } else {
                            android.widget.Toast.makeText(context, resources.getString(R.string.chat_overlay_ready), android.widget.Toast.LENGTH_SHORT).show()
                        }
                    },
                    onAgentStateClick = {
                        if (agentExecutionState.isRunning) {
                            com.paw.agent.ui.floating.AgentExecutionController.requestStop()
                            android.widget.Toast.makeText(context, resources.getString(R.string.chat_stop_requested), android.widget.Toast.LENGTH_SHORT).show()
                        } else if (agentExecutionState.isPaused) {
                            com.paw.agent.ui.floating.AgentExecutionController.requestResume()
                            android.widget.Toast.makeText(context, resources.getString(R.string.chat_resumed), android.widget.Toast.LENGTH_SHORT).show()
                        } else {
                            android.widget.Toast.makeText(context, resources.getString(R.string.chat_idle), android.widget.Toast.LENGTH_SHORT).show()
                        }
                    },
                )
            } else if (!hasAnyExecutionEngine) {
                AppCard(
                    onClick = {
                        com.paw.agent.device.DevicePermissionManager.openAccessibilitySettings(context)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    cornerRadius = 12.dp,
                    containerColor = AppTheme.colors.errorContainer,
                    contentColor = AppTheme.colors.onErrorContainer,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AppText(
                            text = stringResource(R.string.chat_no_engine),
                            style = AppTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        if (isRootAvailable) {
                            AppOutlinedButton(
                                onClick = {
                                    coroutineScope.launch {
                                        com.paw.agent.device.DevicePermissionManager.enableAccessibilityViaRoot(context)
                                        permTick.intValue++
                                    }
                                },
                            ) {
                                AppText(
                                    text = stringResource(R.string.chat_enable_root),
                                    style = AppTheme.typography.labelSmall,
                                )
                            }
                            Spacer(Modifier.width(6.dp))
                        }
                        val isXiaomi = remember {
                            com.paw.agent.device.DevicePermissionManager.isXiaomiOrHyperOs()
                        }
                        if (isXiaomi) {
                            AppOutlinedButton(
                                onClick = {
                                    com.paw.agent.device.DevicePermissionManager.openAppDetailsSettings(context)
                                },
                            ) {
                                AppText(
                                    text = stringResource(R.string.chat_hyperos_restrictions),
                                    style = AppTheme.typography.labelSmall,
                                )
                            }
                            Spacer(Modifier.width(6.dp))
                        }
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
            } else if (!hasOverlayPermission) {
                AppCard(
                    onClick = {
                        com.paw.agent.device.DevicePermissionManager.openOverlaySettings(context)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    cornerRadius = 12.dp,
                    containerColor = AppTheme.colors.tertiaryContainer,
                    contentColor = AppTheme.colors.onTertiaryContainer,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AppText(
                            text = stringResource(R.string.chat_overlay_notice),
                            style = AppTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        val isXiaomi = remember {
                            com.paw.agent.device.DevicePermissionManager.isXiaomiOrHyperOs()
                        }
                        if (isXiaomi) {
                            AppOutlinedButton(
                                onClick = {
                                    com.paw.agent.device.DevicePermissionManager.openXiaomiPermissionsSettings(context)
                                },
                            ) {
                                AppText(
                                    text = stringResource(R.string.chat_background_overlay),
                                    style = AppTheme.typography.labelSmall,
                                )
                            }
                            Spacer(Modifier.width(6.dp))
                        }
                        AppOutlinedButton(
                            onClick = {
                                com.paw.agent.device.DevicePermissionManager.openOverlaySettings(context)
                            },
                        ) {
                            AppText(
                                text = stringResource(R.string.chat_enable_overlay),
                                style = AppTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            } else if (!hasNotificationPermission) {
                AppCard(
                    onClick = {
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            com.paw.agent.device.DevicePermissionManager.openNotificationSettings(context)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    cornerRadius = 12.dp,
                    containerColor = AppTheme.colors.secondaryContainer,
                    contentColor = AppTheme.colors.onSecondaryContainer,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AppText(
                            text = stringResource(R.string.chat_notifications_notice),
                            style = AppTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        AppOutlinedButton(
                            onClick = {
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                                    notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    com.paw.agent.device.DevicePermissionManager.openNotificationSettings(context)
                                }
                            },
                        ) {
                            AppText(
                                text = stringResource(R.string.chat_enable_notifications),
                                style = AppTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }

            if (showStreamFilter) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(stringResource(R.string.chat_stream_all), stringResource(R.string.chat_stream_text), stringResource(R.string.chat_stream_actions)).forEachIndexed { idx, label ->
                        val active = selectedFilterIndex == idx
                        AppSurface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (active) AppTheme.colors.primaryContainer else AppTheme.colors.surfaceContainer,
                            modifier = Modifier
                                .weight(1f)
                                .clickable { selectedFilterIndex = idx }
                                .padding(vertical = 4.dp),
                        ) {
                            AppText(
                                text = label,
                                style = AppTheme.typography.labelSmall,
                                color = if (active) AppTheme.colors.onPrimaryContainer else AppTheme.colors.onSurfaceVariant,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
              }
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                if (filteredMessages.isEmpty()) {
                    EmptyChatState(
                        configured = configReady,
                        modifier = Modifier.align(Alignment.Center),
                        onOpenSettings = onOpenSettings,
                        onSuggestionClick = { onDraftChange(it) },
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        // 顶栏高度花在 contentPadding 上：首条消息仍在顶栏下方起始，
                        // 往上滚时消息从玻璃顶栏的渐隐带下穿过而不是被硬切掉。
                        contentPadding = PaddingValues(
                            top = 12.dp + LocalTopBarClearance.current,
                            bottom = 12.dp,
                        ),
                    ) {
                        // 不以 message.id 作为 key：部分 OpenAI 兼容后端会返回重复或
                        // 空的 tool-call id，重复 key 会让 LazyColumn 直接抛
                        // "Key was already used" 崩掉整个对话界面。消息列表只追加
                        // 不重排，默认的位置 key 足够且更稳。
                        items(items = filteredMessages, key = { it.id }) { message: com.paw.agent.core.model.Message ->
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
    activeBreakpoint: TaskBreakpoint?,
    isInitialized: Boolean = true,
    images: List<String>,
    importingImages: Boolean,
    attachmentError: Boolean,
    onPickImages: () -> Unit,
    onRemoveImage: (Int) -> Unit,
    enabled: Boolean,
    onDraftChange: (String) -> Unit,
    onSendOrStop: () -> Unit,
    onResumeBreakpoint: () -> Unit,
    onConfirmRiskAction: () -> Unit = {},
    onDismissBreakpoint: () -> Unit,
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
            // 断点续操与风险授权确认提示条
            if (activeBreakpoint != null && !isGenerating) {
                val risk = activeBreakpoint.riskConfirmation
                if (risk != null && !risk.isConfirmed) {
                    // 独立的高风险操作确认卡片
                    AppCard(
                        cornerRadius = 14.dp,
                        containerColor = AppTheme.colors.errorContainer,
                        contentColor = AppTheme.colors.onErrorContainer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                AppIcon(
                                    imageVector = Icons.Filled.Warning,
                                    contentDescription = null,
                                    tint = AppTheme.colors.error,
                                    modifier = Modifier.size(22.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                AppText(
                                    text = stringResource(R.string.chat_risk_title, risk.riskLevel),
                                    style = AppTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = AppTheme.colors.error,
                                    modifier = Modifier.weight(1f),
                                )
                                AppIconButton(onClick = onDismissBreakpoint) {
                                    AppIcon(
                                        imageVector = Icons.Outlined.Close,
                                        contentDescription = stringResource(R.string.chat_dismiss),
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            AppText(
                                text = stringResource(R.string.chat_risk_target, risk.target),
                                style = AppTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            AppText(
                                text = stringResource(R.string.chat_risk_action, risk.action, risk.toolName),
                                style = AppTheme.typography.bodySmall,
                            )
                            AppText(
                                text = stringResource(R.string.chat_risk_reason, risk.reason),
                                style = AppTheme.typography.bodySmall,
                                color = AppTheme.colors.onErrorContainer.copy(alpha = 0.85f),
                            )
                            Spacer(Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                AppOutlinedButton(
                                    onClick = onDismissBreakpoint,
                                ) {
                                    AppText(text = stringResource(R.string.chat_cancel_action), style = AppTheme.typography.labelSmall)
                                }
                                Spacer(Modifier.width(8.dp))
                                AppButton(
                                    onClick = onConfirmRiskAction,
                                ) {
                                    AppText(
                                        text = stringResource(R.string.chat_confirm_action),
                                        style = AppTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }
                        }
                    }
                } else if (activeBreakpoint.isSafetyPaused) {
                    // 敏感页面安全暂停卡片
                    AppCard(
                        cornerRadius = 14.dp,
                        containerColor = AppTheme.colors.tertiaryContainer,
                        contentColor = AppTheme.colors.onTertiaryContainer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                AppIcon(
                                    imageVector = Icons.Outlined.Security,
                                    contentDescription = null,
                                    tint = AppTheme.colors.primary,
                                    modifier = Modifier.size(22.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                AppText(
                                    text = stringResource(R.string.chat_sensitive_paused),
                                    style = AppTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f),
                                )
                                AppIconButton(onClick = onDismissBreakpoint) {
                                    AppIcon(
                                        imageVector = Icons.Outlined.Close,
                                        contentDescription = stringResource(R.string.chat_dismiss),
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            AppText(
                                text = stringResource(R.string.chat_sensitive_help),
                                style = AppTheme.typography.bodySmall,
                            )
                            Spacer(Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                AppOutlinedButton(onClick = onDismissBreakpoint) {
                                    AppText(text = stringResource(R.string.chat_abandon), style = AppTheme.typography.labelSmall)
                                }
                                Spacer(Modifier.width(8.dp))
                                AppButton(onClick = onResumeBreakpoint) {
                                    AppText(text = stringResource(R.string.chat_manual_done), style = AppTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                } else {
                    // 普通用户主动暂停卡片
                    AppCard(
                        cornerRadius = 14.dp,
                        containerColor = AppTheme.colors.primaryContainer,
                        contentColor = AppTheme.colors.onPrimaryContainer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AppIcon(
                                imageVector = Icons.Filled.PauseCircle,
                                contentDescription = null,
                                tint = AppTheme.colors.primary,
                                modifier = Modifier.size(24.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                AppText(
                                    text = stringResource(R.string.chat_paused_step, activeBreakpoint.stoppedAtStep),
                                    style = AppTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                                AppText(
                                    text = activeBreakpoint.originalGoal.take(28),
                                    style = AppTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            AppButton(
                                onClick = onResumeBreakpoint,
                            ) {
                                AppText(text = stringResource(R.string.chat_continue_action), style = AppTheme.typography.labelSmall)
                            }
                            Spacer(Modifier.width(4.dp))
                            AppIconButton(onClick = onDismissBreakpoint) {
                                AppIcon(
                                    imageVector = Icons.Outlined.Close,
                                    contentDescription = stringResource(R.string.chat_dismiss_breakpoint),
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }

            if (images.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(images.size, key = { images[it] }) { index ->
                        Box(Modifier.size(88.dp)) {
                            MessageImages(listOf(images[index]))
                            AppIconButton(onClick = { onRemoveImage(index) }, modifier = Modifier.align(Alignment.TopEnd)) {
                                AppIcon(Icons.Outlined.Close, stringResource(R.string.chat_remove_image))
                            }
                        }
                    }
                }
            }
            if (importingImages || attachmentError) {
                AppText(
                    stringResource(if (importingImages) R.string.chat_importing_images else R.string.chat_image_error),
                    color = if (attachmentError) AppTheme.colors.error else AppTheme.colors.onSurfaceVariant,
                    style = AppTheme.typography.bodySmall,
                )
            }
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AppIconButton(onClick = onPickImages, enabled = isInitialized && !isGenerating && !importingImages && images.size < ChatViewModel.MAX_ATTACHMENTS) {
                    AppIcon(Icons.Outlined.AddPhotoAlternate, stringResource(R.string.chat_add_images))
                }
                AppTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    modifier = Modifier.weight(1f),
                    placeholder = when {
                        !isInitialized -> stringResource(R.string.chat_loading_history)
                        !configReady -> stringResource(R.string.chat_composer_hint_unconfigured)
                        activeBreakpoint != null -> stringResource(R.string.chat_resume_hint)
                        else -> stringResource(R.string.chat_input_hint)
                    },
                    cornerRadius = 24.dp,
                    maxLines = 5,
                    enabled = isInitialized,
                )

                AppSurface(
                    onClick = {
                        if (!configReady && !isGenerating) {
                            onOpenSettings()
                        } else {
                            onSendOrStop()
                        }
                    },
                    enabled = (enabled && isInitialized) || !configReady,
                    shape = CircleShape,
                    color = when {
                        isGenerating -> AppTheme.colors.errorContainer
                        !configReady -> AppTheme.colors.tertiaryContainer
                        else -> AppTheme.colors.primary
                    },
                    contentColor = when {
                        isGenerating -> AppTheme.colors.onErrorContainer
                        !configReady -> AppTheme.colors.onTertiaryContainer
                        else -> AppTheme.colors.onPrimary
                    },
                ) {
                    Box(
                        Modifier.size(52.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        AppIcon(
                            imageVector = when {
                                isGenerating -> Icons.Filled.Stop
                                !configReady -> Icons.Outlined.Settings
                                else -> Icons.AutoMirrored.Filled.Send
                            },
                            contentDescription = stringResource(
                                when {
                                    isGenerating -> R.string.chat_stop
                                    !configReady -> R.string.chat_open_settings
                                    else -> R.string.chat_send
                                },
                            ),
                        )
                    }
                }
            }
        }
    }
}

private val RectangleShape = RoundedCornerShape(0.dp)

@Composable
private fun RootAuthDashboard(
    shizukuGranted: Boolean,
    accessibilityEnabled: Boolean,
    overlayEnabled: Boolean,
    agentState: com.paw.agent.ui.floating.AgentExecutionController.ExecutionState,
    onShizukuClick: () -> Unit,
    onAccessibilityClick: () -> Unit,
    onOverlayClick: () -> Unit,
    onAgentStateClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AppCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        cornerRadius = 14.dp,
        containerColor = AppTheme.colors.surfaceContainer,
        contentPadding = PaddingValues(10.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppText(
                    text = stringResource(R.string.chat_system_console),
                    style = AppTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = AppTheme.colors.onSurface,
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(AppTheme.colors.primaryContainer)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    AppText(
                        text = stringResource(R.string.chat_root_granted),
                        style = AppTheme.typography.labelSmall,
                        color = AppTheme.colors.onPrimaryContainer,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            // 4个独立按钮以 2x2 网格排列
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RootModuleButton(
                    title = "Shizuku",
                    statusText = if (shizukuGranted) stringResource(R.string.chat_authorized_connected) else stringResource(R.string.chat_unauthorized),
                    isReady = shizukuGranted,
                    onClick = onShizukuClick,
                    modifier = Modifier.weight(1f),
                )
                RootModuleButton(
                    title = stringResource(R.string.chat_channel_accessibility),
                    statusText = if (accessibilityEnabled) stringResource(R.string.chat_accessibility_active) else stringResource(R.string.chat_accessibility_inactive),
                    isReady = accessibilityEnabled,
                    onClick = onAccessibilityClick,
                    modifier = Modifier.weight(1f),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RootModuleButton(
                    title = stringResource(R.string.chat_channel_overlay),
                    statusText = if (overlayEnabled) stringResource(R.string.chat_overlay_ready) else stringResource(R.string.chat_overlay_inactive),
                    isReady = overlayEnabled,
                    onClick = onOverlayClick,
                    modifier = Modifier.weight(1f),
                )
                RootModuleButton(
                    title = stringResource(R.string.chat_runtime_state),
                    statusText = when {
                        agentState.isRunning -> stringResource(R.string.chat_running_step, agentState.currentStep)
                        agentState.isPaused -> stringResource(R.string.chat_paused_continue)
                        else -> stringResource(R.string.chat_idle_ready)
                    },
                    isReady = agentState.isRunning || !agentState.isPaused,
                    onClick = onAgentStateClick,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun RootModuleButton(
    title: String,
    statusText: String,
    isReady: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AppSurface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = if (isReady) AppTheme.colors.surfaceContainerHigh else AppTheme.colors.errorContainer.copy(alpha = 0.5f),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppText(
                    text = title,
                    style = AppTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = AppTheme.colors.onSurface,
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (isReady) AppTheme.colors.primary.copy(alpha = 0.15f) else AppTheme.colors.error.copy(alpha = 0.15f))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                ) {
                    AppText(
                        text = if (isReady) stringResource(R.string.chat_ready) else stringResource(R.string.chat_not_ready),
                        style = AppTheme.typography.labelSmall,
                        color = if (isReady) AppTheme.colors.primary else AppTheme.colors.error,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            AppText(
                text = statusText,
                style = AppTheme.typography.bodySmall,
                color = if (isReady) AppTheme.colors.onSurfaceVariant else AppTheme.colors.error,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
