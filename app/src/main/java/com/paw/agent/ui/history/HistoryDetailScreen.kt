package com.paw.agent.ui.history

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.History
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.paw.agent.core.model.Conversation
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.paw.agent.AppContainer
import com.paw.agent.R
import com.paw.agent.data.conversation.PersistentConversationRepository
import com.paw.agent.ui.chat.MessageBubble
import com.paw.agent.ui.components.adaptive.AppButton
import com.paw.agent.ui.components.adaptive.AppCard
import com.paw.agent.ui.components.adaptive.AppIcon
import com.paw.agent.ui.components.adaptive.AppIconButton
import com.paw.agent.ui.components.adaptive.AppScaffold
import com.paw.agent.ui.components.adaptive.AppText
import com.paw.agent.ui.components.adaptive.AppTopAppBar
import com.paw.agent.ui.components.adaptive.AppTopBarAction
import com.paw.agent.ui.components.adaptive.LocalTopBarClearance
import com.paw.agent.ui.theme.AppTheme

@Composable
fun HistoryDetailScreen(
    conversationId: String,
    container: AppContainer,
    onBack: () -> Unit,
    onRestoreToChat: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    val scope = rememberCoroutineScope()
    val historyList by container.conversationRepository.historyList.collectAsStateWithLifecycle()
    val loaded by produceState<Pair<Boolean, Conversation?>>(false to null, historyList, conversationId) {
        value = true to container.conversationRepository.getConversation(conversationId)
    }
    val conv = loaded.second
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()

    AppScaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            AppTopAppBar(
                title = conv?.title?.ifBlank { stringResource(R.string.history_detail) } ?: stringResource(R.string.history_detail),
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
                    if (conv != null) {
                        AppTopBarAction(onClick = {
                          scope.launch {
                            val ok = container.restoreConversation(conv.id)
                            if (ok) {
                                Toast.makeText(context, resources.getString(R.string.history_restored), Toast.LENGTH_SHORT).show()
                                onRestoreToChat()
                            }
                          }
                        }) {
                            AppIcon(
                                imageVector = Icons.Default.History,
                                contentDescription = stringResource(R.string.history_restore_action),
                            )
                        }
                    }
                },
                color = AppTheme.colors.surfaceContainer,
            )
        },
        bottomBar = {
            if (conv != null) {
                AppCard(
                    cornerRadius = 16.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            val stats = remember(conv) {
                                PersistentConversationRepository.computeStats(conv)
                            }
                            AppText(
                                text = if (stats.durationMs > 0) stringResource(R.string.history_execution_summary, stats.stepCount, stats.durationMs / 1000f) else stringResource(R.string.history_duration_unknown),
                                style = AppTheme.typography.labelMedium,
                                color = AppTheme.colors.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        AppButton(onClick = {
                          scope.launch {
                            val ok = container.restoreConversation(conv.id)
                            if (ok) {
                                Toast.makeText(context, resources.getString(R.string.history_restored_ok), Toast.LENGTH_SHORT).show()
                                onRestoreToChat()
                            }
                          }
                        }) {
                            AppText(stringResource(R.string.history_restore_continue))
                        }
                    }
                }
            }
        },
        containerColor = AppTheme.colors.background,
    ) { innerPadding ->
        if (conv == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                AppText(if (!loaded.first) stringResource(R.string.history_loading) else stringResource(R.string.history_not_found))
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                // 顶栏那一份高度额外作为 contentPadding：Material 下是 0，布局与改动前一致。
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    top = LocalTopBarClearance.current,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // 不以 message.id 单独作为 key，使用带 index 的安全组合 key，
                // 防止极端情况下同轮次或衍生 tool-call id 重复导致页面崩溃闪退
                itemsIndexed(
                    items = conv.messages,
                    key = { index, message -> "${message.id}_$index" },
                ) { _, message ->
                    MessageBubble(message = message)
                }
            }
        }
    }
}
