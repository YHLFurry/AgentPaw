package com.paw.agent.ui.history

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
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
    var selectedForCompare by rememberSaveable { mutableStateOf(listOf<String>()) }
    var isCompareMode by rememberSaveable { mutableStateOf(false) }
    var pendingDeleteConvId by remember { mutableStateOf<String?>(null) }
    var pendingDeleteAll by remember { mutableStateOf(false) }
    var pendingClearMedia by remember { mutableStateOf(false) }
    var clearingMedia by remember { mutableStateOf(false) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(allHistory, initialized) {
        if (!initialized) return@LaunchedEffect
        selectedForCompare = selectedForCompare.filter { id -> allHistory.any { it.id == id } }
        if (allHistory.size < 2) {
            isCompareMode = false
            selectedForCompare = emptyList()
        }
    }
    BackHandler(isCompareMode) {
        isCompareMode = false
        selectedForCompare = emptyList()
    }

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
                    AppTopBarAction(onClick = { pendingClearMedia = true }, enabled = initialized && !clearingMedia && allHistory.isNotEmpty()) {
                        AppIcon(Icons.Outlined.DeleteSweep, stringResource(R.string.history_clear_media))
                    }
                    if (allHistory.size >= 2) {
                        AppTopBarAction(onClick = {
                            isCompareMode = !isCompareMode
                            if (!isCompareMode) selectedForCompare = emptyList()
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
      if (pendingDeleteAll) com.paw.agent.ui.components.adaptive.AppAlertDialog(
          onDismissRequest = { pendingDeleteAll = false },
          title = { AppText(stringResource(R.string.history_delete_all)) },
          text = { AppText(stringResource(R.string.history_delete_all_help)) },
          confirmButton = { AppButton(onClick = {
              pendingDeleteAll = false
              container.agentTaskRunner.stop()
              container.agentTaskRunner.dismissBreakpoint()
              container.conversationRepository.deleteAllHistory()
          }) { AppText(stringResource(R.string.action_delete)) } },
          dismissButton = { AppOutlinedButton(onClick = { pendingDeleteAll = false }) { AppText(stringResource(R.string.action_cancel)) } },
      )
      if (pendingClearMedia) com.paw.agent.ui.components.adaptive.AppAlertDialog(
          onDismissRequest = { pendingClearMedia = false },
          title = { AppText(stringResource(R.string.history_clear_media)) },
          text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
              AppText(stringResource(R.string.history_clear_media_help))
              AppOutlinedButton(onClick = { pendingClearMedia = false; pendingDeleteAll = true }) {
                  AppText(stringResource(R.string.history_delete_all))
              }
          } },
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
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            AppOutlinedButton(onClick = { failedOnly = !failedOnly }) {
                AppText(stringResource(if (failedOnly) R.string.history_show_all else R.string.history_failed_only))
            }
            AppText("${visibleHistory.size}/${allHistory.size}", style = AppTheme.typography.bodySmall)
        }
        if (isCompareMode) {
            AppSurface(color = AppTheme.colors.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    AppText(
                        stringResource(R.string.history_selected_count, selectedForCompare.size),
                        style = AppTheme.typography.bodyMedium,
                        color = AppTheme.colors.onSurface,
                        modifier = Modifier.align(Alignment.CenterVertically),
                    )
                    AppButton(
                        onClick = { onOpenCompare(selectedForCompare[0], selectedForCompare[1]) },
                        enabled = selectedForCompare.size == 2,
                    ) { AppText(stringResource(R.string.history_compare_start)) }
                    AppOutlinedButton(onClick = { isCompareMode = false; selectedForCompare = emptyList() }) {
                        AppText(stringResource(R.string.action_cancel))
                    }
                }
            }
        }
        if (visibleHistory.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
              Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(24.dp)) {
                AppText(
                    text = stringResource(if (!initialized) R.string.history_loading else if (historyList.isEmpty()) R.string.history_empty else R.string.history_no_matches),
                    color = AppTheme.colors.onSurfaceVariant,
                )
                if (query.isNotBlank() || failedOnly) {
                    AppOutlinedButton(onClick = { query = ""; failedOnly = false }) {
                        AppText(stringResource(R.string.history_clear_filters))
                    }
                }
              }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize(),
            ) {

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
                                        selectedForCompare = selectedForCompare - conv.id
                                    } else if (selectedForCompare.size < 2) {
                                        selectedForCompare = selectedForCompare + conv.id
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
                                    pendingDeleteConvId = null
                                    scope.launch {
                                        val deleted = container.conversationRepository.deleteConversation(targetId)
                                        if (deleted) selectedForCompare = selectedForCompare - targetId
                                        Toast.makeText(context, resources.getString(if (deleted) R.string.history_deleted else R.string.history_delete_failed), Toast.LENGTH_SHORT).show()
                                    }
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
internal fun HistoryTaskCard(
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
            .then(if (isSelected) Modifier.border(2.dp, AppTheme.colors.primary, RoundedCornerShape(16.dp)) else Modifier)
            .then(if (isCompareMode) Modifier.selectable(selected = isSelected, role = Role.Checkbox, onClick = onCardClick)
                  else Modifier.clickable(onClick = onCardClick)),
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
                        modifier = Modifier.weight(1f),
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

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
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
                        color = AppTheme.colors.onSurfaceVariant,
                    )
                }
            }

            if (!isCompareMode) {
                Spacer(Modifier.height(12.dp))

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(4.dp),
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
                AppIconButton(onClick = onDelete) {
                    AppIcon(imageVector = Icons.Default.Delete, contentDescription = stringResource(R.string.history_delete_record))
                }
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
