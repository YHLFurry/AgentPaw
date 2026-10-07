package com.paw.agent.ui.history

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.paw.agent.AppContainer
import com.paw.agent.R
import com.paw.agent.core.model.Conversation
import com.paw.agent.core.model.MessageRole
import com.paw.agent.data.conversation.PersistentConversationRepository
import com.paw.agent.data.conversation.TaskExecutionStats
import com.paw.agent.ui.components.adaptive.AppCard
import com.paw.agent.ui.components.adaptive.AppIcon
import com.paw.agent.ui.components.adaptive.AppIconButton
import com.paw.agent.ui.components.adaptive.AppLinearProgressIndicator
import com.paw.agent.ui.components.adaptive.AppScaffold
import com.paw.agent.ui.components.adaptive.AppSurface
import com.paw.agent.ui.components.adaptive.AppText
import com.paw.agent.ui.components.adaptive.AppTopAppBar
import com.paw.agent.ui.settings.SettingsCard
import com.paw.agent.ui.settings.SettingsGroupLabel
import com.paw.agent.ui.settings.SettingsRowDivider
import com.paw.agent.ui.theme.AppTheme

@Composable
fun TaskCompareScreen(
    id1: String,
    id2: String,
    container: AppContainer,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val historyList by container.conversationRepository.historyList.collectAsStateWithLifecycle()
    val conv1 = remember(historyList, id1) { historyList.firstOrNull { it.id == id1 } }
    val conv2 = remember(historyList, id2) { historyList.firstOrNull { it.id == id2 } }

    AppScaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            AppTopAppBar(
                title = stringResource(R.string.history_compare_title),
                navigationIcon = {
                    AppIconButton(onClick = onBack) {
                        AppIcon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                        )
                    }
                },
                color = AppTheme.colors.surfaceContainer,
            )
        },
        containerColor = AppTheme.colors.background,
    ) { innerPadding ->
        if (conv1 == null || conv2 == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                AppText("对比数据加载失败或部分记录已被删除")
            }
        } else {
            val stats1 = remember(conv1) { PersistentConversationRepository.computeStats(conv1) }
            val stats2 = remember(conv2) { PersistentConversationRepository.computeStats(conv2) }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                // 顶部：两任务概览并排卡片
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    TaskSummaryBox(
                        tag = "任务 A",
                        stats = stats1,
                        modifier = Modifier.weight(1f),
                    )
                    TaskSummaryBox(
                        tag = "任务 B",
                        stats = stats2,
                        modifier = Modifier.weight(1f),
                    )
                }

                Spacer(Modifier.height(20.dp))

                // 核心指标量化比对
                SettingsGroupLabel("核心量化指标对比")
                SettingsCard {
                    CompareMetricRow(
                        title = "总执行步数",
                        val1Text = "${stats1.stepCount} 步",
                        val2Text = "${stats2.stepCount} 步",
                        progress = if (stats1.stepCount + stats2.stepCount > 0) {
                            stats1.stepCount.toFloat() / (stats1.stepCount + stats2.stepCount)
                        } else 0.5f,
                    )

                    SettingsRowDivider()

                    CompareMetricRow(
                        title = "总执行耗时",
                        val1Text = "${stats1.durationMs / 1000f}s",
                        val2Text = "${stats2.durationMs / 1000f}s",
                        progress = if (stats1.durationMs + stats2.durationMs > 0) {
                            stats1.durationMs.toFloat() / (stats1.durationMs + stats2.durationMs)
                        } else 0.5f,
                    )
                }

                Spacer(Modifier.height(20.dp))

                // 工具调用分布对比
                SettingsGroupLabel("工具调用分布对比 (A vs B)")
                SettingsCard {
                    val allToolNames = (stats1.toolCounts.keys + stats2.toolCounts.keys).distinct().sorted()
                    if (allToolNames.isEmpty()) {
                        Box(modifier = Modifier.padding(16.dp)) {
                            AppText("两项任务均未发起工具调用", color = AppTheme.colors.onSurfaceVariant)
                        }
                    } else {
                        allToolNames.forEachIndexed { index, toolName ->
                            val c1 = stats1.toolCounts[toolName] ?: 0
                            val c2 = stats2.toolCounts[toolName] ?: 0
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                AppText(
                                    text = toolName,
                                    style = AppTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.weight(1.5f),
                                )
                                AppText(
                                    text = "$c1 次",
                                    style = AppTheme.typography.bodyMedium,
                                    color = AppTheme.colors.primary,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f),
                                    textAlign = TextAlign.Center,
                                )
                                AppText(
                                    text = "$c2 次",
                                    style = AppTheme.typography.bodyMedium,
                                    color = AppTheme.colors.outline,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f),
                                    textAlign = TextAlign.End,
                                )
                            }
                            if (index != allToolNames.lastIndex) {
                                SettingsRowDivider()
                            }
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))

                // 执行步骤轨迹对比
                SettingsGroupLabel("关键操作执行轨迹对比")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    StepTimelineColumn(
                        conv = conv1,
                        modifier = Modifier.weight(1f),
                    )
                    StepTimelineColumn(
                        conv = conv2,
                        modifier = Modifier.weight(1f),
                    )
                }

                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun TaskSummaryBox(
    tag: String,
    stats: TaskExecutionStats,
    modifier: Modifier = Modifier,
) {
    AppCard(
        cornerRadius = 14.dp,
        modifier = modifier,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppText(
                    text = tag,
                    style = AppTheme.typography.labelSmall,
                    color = AppTheme.colors.primary,
                    fontWeight = FontWeight.Bold,
                )
                StatusBadge(status = stats.status)
            }
            Spacer(Modifier.height(6.dp))
            AppText(
                text = stats.title.ifBlank { "任务" },
                style = AppTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            AppText(
                text = stats.initialGoal,
                style = AppTheme.typography.bodySmall,
                color = AppTheme.colors.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun CompareMetricRow(
    title: String,
    val1Text: String,
    val2Text: String,
    progress: Float,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            AppText(text = title, style = AppTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                AppText(text = "A: $val1Text", color = AppTheme.colors.primary, fontWeight = FontWeight.Bold)
                AppText(text = "B: $val2Text", color = AppTheme.colors.outline, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(8.dp))
        AppLinearProgressIndicator(
            progress = progress.coerceIn(0.05f, 0.95f),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun StepTimelineColumn(
    conv: Conversation,
    modifier: Modifier = Modifier,
) {
    val toolSteps = remember(conv) {
        conv.messages.filter { it.role == MessageRole.TOOL }
    }

    AppCard(
        cornerRadius = 12.dp,
        modifier = modifier,
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            AppText(
                text = "步骤序列 (${toolSteps.size})",
                style = AppTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            if (toolSteps.isEmpty()) {
                AppText("无操作步骤", style = AppTheme.typography.bodySmall, color = AppTheme.colors.onSurfaceVariant)
            } else {
                toolSteps.forEachIndexed { idx, msg ->
                    AppText(
                        text = "${idx + 1}. ${msg.content.take(35)}",
                        style = AppTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(vertical = 3.dp),
                    )
                }
            }
        }
    }
}
