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
import androidx.compose.material.icons.outlined.DeleteSweep
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
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.paw.agent.ui.components.adaptive.AppTextField
import com.paw.agent.ui.components.adaptive.AppTopAppBar
import com.paw.agent.ui.components.adaptive.AppTopBarAction
import com.paw.agent.ui.components.adaptive.LocalTopBarClearance
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
    val resources = androidx.compose.ui.platform.LocalResources.current
    val scope = rememberCoroutineScope()
    val historyList by container.conversationRepository.historyList.collectAsStateWithLifecycle()
    val initialized by container.conversationRepository.isInitialized.collectAsStateWithLifecycle()
    val storageError by container.conversationRepository.storageError.collectAsStateWithLifecycle()
    val hasMore by container.conversationRepository.hasMoreHistory.collectAsStateWithLifecycle()
    val allHistory by container.conversationRepository.allHistorySummaries.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var failedOnly by rememberSaveable { mutableStateOf(false) }
    val visibleHistory = remember(historyList, allHistory, query, failedOnly) {
        val search = query.trim()
        (if (search.isNotBlank() || failedOnly) allHistory else historyList).filter { conv ->
            val stats = PersistentConversationRepository.computeStats(conv)
            (!failedOnly || stats.status == MessageStatus.FAILED) &&
                (search.isBlank() || conv.title.contains(search, ignoreCase = true) ||
                    stats.initialGoal.contains(search, ignoreCase = true))
        }
    }
    val selectedForCompare = remember { mutableStateListOf<String>() }
    var isCompareMode by remember { mutableStateOf(false) }
    var pendingDeleteConvId by remember { mutableStateOf<String?>(null) }
    var pendingClearMedia by remember { mutableStateOf(false) }
    var clearingMedia by remember { mutableStateOf(false) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()

    AppScaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            AppTopAppBar(
                title = stringResource(R.string.history_title),
                isContentScrolled = listState.canScrollBackward,
                navigationIcon = {
                    AppTopBarAction(onClick = onBack) {
                        AppIcon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                        )
                    }
                },
                actions = {
                    AppTopBarAction(onClick = { if (!clearingMedia) pendingClearMedia = true }) {
                        AppIcon(Icons.Outlined.DeleteSweep, stringResource(R.string.history_clear_media))
                    }
                    if (historyList.size >= 2) {
                        AppTopBarAction(onClick = {
                            isCompareMode = !isCompareMode
                            if (!isCompareMode) selectedForCompare.clear()
                        }) {
                            AppIcon(
                                imageVector = Icons.AutoMirrored.Filled.CompareArrows,
                                contentDescription = stringResource(R.string.history_compare_select),
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
      if (pendingClearMedia) com.paw.agent.ui.components.adaptive.AppAlertDialog(
          onDismissRequest = { pendingClearMedia = false },
          title = { AppText(stringResource(R.string.history_clear_media)) },
          text = { AppText(stringResource(R.string.history_clear_media_help)) },
          confirmButton = {
              AppButton(onClick = {
                  pendingClearMedia = false
                  clearingMedia = true
                  container.agentTaskRunner.stop()
                  scope.launch {
                      try {
                          val success = container.conversationRepository.clearAllMedia()
                          Toast.makeText(context, resources.getString(if (success) R.string.history_media_cleared else R.string.history_media_clear_failed), Toast.LENGTH_SHORT).show()
                      } finally { clearingMedia = false }
                  }
              }) { AppText(stringResource(R.string.action_delete)) }
          },
          dismissButton = { AppOutlinedButton(onClick = { pendingClearMedia = false }) { AppText(stringResource(R.string.action_cancel)) } },
      )
      Column(Modifier.fillMaxSize().padding(innerPadding).padding(top = LocalTopBarClearance.current)) {
        storageError?.let {
            AppText(it, color = AppTheme.colors.error, modifier = Modifier.padding(16.dp))
            AppOutlinedButton(onClick = container.conversationRepository::retryFailedSaves, modifier = Modifier.padding(horizontal = 16.dp)) {
                AppText(stringResource(R.string.history_retry_save))
            }
        }
        if (clearingMedia) AppText(stringResource(R.string.history_clearing_media), modifier = Modifier.padding(16.dp))
        AppTextField(
            value = query,
            onValueChange = { query = it },
            label = stringResource(R.string.history_search),
            singleLine = true,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            AppOutlinedButton(onClick = { failedOnly = !failedOnly }) {
                AppText(stringResource(if (failedOnly) R.string.history_show_all else R.string.history_failed_only))
            }
            Spacer(Modifier.width(12.dp))
            AppText("${visibleHistory.size}/${allHistory.size}", style = AppTheme.typography.bodySmall)
        }
        if (visibleHistory.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                AppText(
                    text = stringResource(if (!initialized) R.string.history_loading else if (historyList.isEmpty()) R.string.history_empty else R.string.history_no_matches),
                    color = AppTheme.colors.onSurfaceVariant,
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize(),
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
                                text = stringResource(R.string.history_selected_count, selectedForCompare.size),
                                style = AppTheme.typography.bodyMedium,
                                color = AppTheme.colors.onPrimaryContainer,
                            )
                            if (selectedForCompare.size == 2) {
                                AppButton(onClick = {
                                    onOpenCompare(selectedForCompare[0], selectedForCompare[1])
                                }) {
                                    AppText(stringResource(R.string.history_compare_start))
                                }
                            }
                        }
                    }
                }

                LazyColumn(
                    state = listState,
                    // 外边距维持原样，顶栏那一份高度额外作为 contentPadding：
                    // Material 下它是 0，布局与改动前逐像素一致。
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        top = 0.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(visibleHistory, key = { it.id }) { conv ->
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
                                        Toast.makeText(context, resources.getString(R.string.history_compare_limit), Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    onOpenDetail(conv.id)
                                }
                            },
                            onRestore = {
                              scope.launch {
                                val ok = container.restoreConversation(conv.id)
                                if (ok) {
                                    Toast.makeText(context, resources.getString(R.string.history_restored), Toast.LENGTH_SHORT).show()
                                    onRestoreToChat()
                                } else {
                                    Toast.makeText(context, resources.getString(R.string.history_restore_failed), Toast.LENGTH_SHORT).show()
                                }
                              }
                            },
                            onDelete = {
                                pendingDeleteConvId = conv.id
                            },
                        )
                    }
                    if (hasMore && query.isBlank() && !failedOnly) item {
                        AppOutlinedButton(onClick = container.conversationRepository::loadMoreHistory, modifier = Modifier.fillMaxWidth()) {
                            AppText(stringResource(R.string.history_load_more))
                        }
                    }
                }

                if (pendingDeleteConvId != null) {
                    val targetId = pendingDeleteConvId!!
                    com.paw.agent.ui.components.adaptive.AppAlertDialog(
                        onDismissRequest = { pendingDeleteConvId = null },
                        title = { AppText(stringResource(R.string.history_delete_title), style = AppTheme.typography.titleMedium) },
                        text = {
                            AppText(
                                stringResource(R.string.history_delete_message),
                                style = AppTheme.typography.bodyMedium,
                            )
                        },
                        confirmButton = {
                            AppButton(
                                onClick = {
                                    container.conversationRepository.deleteConversation(targetId)
                                    selectedForCompare.remove(targetId)
                                    pendingDeleteConvId = null
                                    Toast.makeText(context, resources.getString(R.string.history_deleted), Toast.LENGTH_SHORT).show()
                                },
                            ) {
                                AppText(stringResource(R.string.action_delete))
                            }
                        },
                        dismissButton = {
                            AppOutlinedButton(onClick = { pendingDeleteConvId = null }) {
                                AppText(stringResource(R.string.action_cancel))
                            }
                        },
                    )
                }
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
                        text = stats.title.ifBlank { stringResource(R.string.history_record) },
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
                text = stringResource(R.string.history_goal, stats.initialGoal),
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
                    text = stringResource(R.string.history_step_count, stats.stepCount),
                    style = AppTheme.typography.labelSmall,
                    color = AppTheme.colors.primary,
                )
                if (stats.durationMs > 0) {
                    AppText(
                        text = stringResource(R.string.history_seconds, stats.durationMs / 1000f),
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
                    AppIcon(imageVector = Icons.Default.Delete, contentDescription = stringResource(R.string.history_delete_record))
                }
            }
        }
    }
}

@Composable
fun StatusBadge(status: MessageStatus) {
    val (label, bg, fg) = when (status) {
        MessageStatus.COMPLETE -> Triple(stringResource(R.string.history_status_complete), AppTheme.colors.primary.copy(alpha = 0.12f), AppTheme.colors.primary)
        MessageStatus.FAILED -> Triple(stringResource(R.string.history_status_failed), AppTheme.colors.error.copy(alpha = 0.12f), AppTheme.colors.error)
        MessageStatus.CANCELLED -> Triple(stringResource(R.string.history_status_cancelled), AppTheme.colors.outline.copy(alpha = 0.12f), AppTheme.colors.outline)
        MessageStatus.STREAMING -> Triple(stringResource(R.string.history_status_streaming), AppTheme.colors.primaryContainer, AppTheme.colors.onPrimaryContainer)
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
