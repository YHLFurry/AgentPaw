package com.paw.agent.ui.history

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Compare
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.paw.agent.AppContainer
import com.paw.agent.R
import com.paw.agent.core.model.Conversation
import com.paw.agent.core.model.MessageStatus
import com.paw.agent.data.conversation.PersistentConversationRepository
import com.paw.agent.data.conversation.TaskExecutionStats
import com.paw.agent.ui.components.adaptive.AppButton
import com.paw.agent.ui.components.adaptive.AppCard
import com.paw.agent.ui.components.adaptive.AppIcon
import com.paw.agent.ui.components.adaptive.AppIconButton
import com.paw.agent.ui.components.adaptive.AppOutlinedButton
import com.paw.agent.ui.components.adaptive.AppScaffold
import com.paw.agent.ui.components.adaptive.AppSurface
import com.paw.agent.ui.components.adaptive.AppText
import com.paw.agent.ui.components.adaptive.AppTopAppBar
import com.paw.agent.ui.theme.AppTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onOpenDetail: (String) -> Unit,
    onOpenCompare: (String, String) -> Unit,
    onRestoreToChat: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val historyList by container.conversationRepository.historyList.collectAsStateWithLifecycle()
    val selectedForCompare = remember { mutableStateListOf<String>() }
    var isCompareMode by remember { mutableStateOf(false) }
    var pendingDeleteConvId by remember { mutableStateOf<String?>(null) }

    AppScaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            AppTopAppBar(
                title = stringResource(R.string.history_title),
                navigationIcon = {
                    AppIconButton(onClick = onBack) {
                        AppIcon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                        )
                    }
                },
                actions = {
                    if (historyList.size >= 2) {
                        AppIconButton(onClick = {
                            isCompareMode = !isCompareMode
                            if (!isCompareMode) selectedForCompare.clear()
                        }) {
                            AppIcon(
                                imageVector = Icons.AutoMirrored.Filled.CompareArrows,
                                contentDescription = "多选对比",
                                tint = if (isCompareMode) AppTheme.colors.primary else AppTheme.colors.onSurface,
                            )
                        }
                    }
                },
                color = AppTheme.colors.surfaceContainer,
            )
        },
        containerColor = AppTheme.colors.background,
    ) { innerPadding ->
        if (historyList.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                AppText(
                    text = stringResource(R.string.history_empty),
                    color = AppTheme.colors.onSurfaceVariant,
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                if (isCompareMode) {
                    AppSurface(
                        color = AppTheme.colors.primaryContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            AppText(
                                text = "已选 ${selectedForCompare.size}/2 条任务记录进行对比",
                                style = AppTheme.typography.bodyMedium,
                                color = AppTheme.colors.onPrimaryContainer,
                            )
                            if (selectedForCompare.size == 2) {
                                AppButton(onClick = {
                                    onOpenCompare(selectedForCompare[0], selectedForCompare[1])
                                }) {
                                    AppText("开始对比")
                                }
                            }
                        }
                    }
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(historyList, key = { it.id }) { conv ->
                        val stats = remember(conv) {
                            PersistentConversationRepository.computeStats(conv)
                        }
                        val isSelected = selectedForCompare.contains(conv.id)

                        HistoryTaskCard(
                            stats = stats,
                            isCompareMode = isCompareMode,
                            isSelected = isSelected,
                            onCardClick = {
                                if (isCompareMode) {
                                    if (isSelected) {
                                        selectedForCompare.remove(conv.id)
                                    } else if (selectedForCompare.size < 2) {
                                        selectedForCompare.add(conv.id)
                                    } else {
                                        Toast.makeText(context, "一次最多对比两条记录", Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    onOpenDetail(conv.id)
                                }
                            },
                            onRestore = {
                                container.conversationRepository.loadConversation(conv.id)
                                Toast.makeText(context, "已载入此任务历史上下文！", Toast.LENGTH_SHORT).show()
                                onRestoreToChat()
                            },
                            onDelete = {
                                pendingDeleteConvId = conv.id
                            },
                        )
                    }
                }

                if (pendingDeleteConvId != null) {
                    val targetId = pendingDeleteConvId!!
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { pendingDeleteConvId = null },
                        title = { AppText("确认删除历史记录？", style = AppTheme.typography.titleMedium) },
                        text = {
                            AppText(
                                "此操作将永久删除该会话记录及相关的历史截图，无法撤销。",
                                style = AppTheme.typography.bodyMedium,
                            )
                        },
                        confirmButton = {
                            AppButton(
                                onClick = {
                                    container.conversationRepository.deleteConversation(targetId)
                                    selectedForCompare.remove(targetId)
                                    pendingDeleteConvId = null
                                    Toast.makeText(context, "已删除该任务记录", Toast.LENGTH_SHORT).show()
                                },
                            ) {
                                AppText("确认删除")
                            }
                        },
                        dismissButton = {
                            AppOutlinedButton(onClick = { pendingDeleteConvId = null }) {
                                AppText("取消")
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryTaskCard(
    stats: TaskExecutionStats,
    isCompareMode: Boolean,
    isSelected: Boolean,
    onCardClick: () -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    val formattedTime = remember(stats.createdAt) {
        if (stats.createdAt > 0L) dateFormat.format(Date(stats.createdAt)) else ""
    }

    AppCard(
        cornerRadius = 16.dp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCardClick() },
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    if (isCompareMode) {
                        AppIcon(
                            imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                            contentDescription = null,
                            tint = if (isSelected) AppTheme.colors.primary else AppTheme.colors.outline,
                        )
                    }
                    AppText(
                        text = stats.title.ifBlank { "任务记录" },
                        style = AppTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                StatusBadge(status = stats.status)
            }

            Spacer(Modifier.height(6.dp))

            AppText(
                text = "目标: ${stats.initialGoal}",
                style = AppTheme.typography.bodySmall,
                color = AppTheme.colors.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.height(10.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                AppText(
                    text = "⚙ ${stats.stepCount} 步操作",
                    style = AppTheme.typography.labelSmall,
                    color = AppTheme.colors.primary,
                )
                if (stats.durationMs > 0) {
                    AppText(
                        text = "⏱ ${stats.durationMs / 1000f} 秒",
                        style = AppTheme.typography.labelSmall,
                        color = AppTheme.colors.onSurfaceVariant,
                    )
                }
                if (formattedTime.isNotBlank()) {
                    AppText(
                        text = formattedTime,
                        style = AppTheme.typography.labelSmall,
                        color = AppTheme.colors.outline,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppOutlinedButton(onClick = onRestore) {
                    AppIcon(
                        imageVector = Icons.Default.History,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    AppText(stringResource(R.string.history_restore))
                }
                Spacer(Modifier.width(8.dp))
                AppIconButton(onClick = onDelete) {
                    AppIcon(imageVector = Icons.Default.Delete, contentDescription = "删除记录")
                }
            }
        }
    }
}

@Composable
fun StatusBadge(status: MessageStatus) {
    val (label, bg, fg) = when (status) {
        MessageStatus.COMPLETE -> Triple("已完成", AppTheme.colors.primary.copy(alpha = 0.12f), AppTheme.colors.primary)
        MessageStatus.FAILED -> Triple("失败", AppTheme.colors.error.copy(alpha = 0.12f), AppTheme.colors.error)
        MessageStatus.CANCELLED -> Triple("已停止", AppTheme.colors.outline.copy(alpha = 0.12f), AppTheme.colors.outline)
        MessageStatus.STREAMING -> Triple("进行中", AppTheme.colors.primaryContainer, AppTheme.colors.onPrimaryContainer)
    }

    AppSurface(
        shape = CircleShape,
        color = bg,
    ) {
        AppText(
            text = label,
            style = AppTheme.typography.labelSmall,
            color = fg,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}
