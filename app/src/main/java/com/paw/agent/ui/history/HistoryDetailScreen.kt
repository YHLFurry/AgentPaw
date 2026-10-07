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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.History
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
    val historyList by container.conversationRepository.historyList.collectAsStateWithLifecycle()
    val conv = remember(historyList, conversationId) {
        historyList.firstOrNull { it.id == conversationId }
    }

    AppScaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            AppTopAppBar(
                title = conv?.title?.ifBlank { "任务详情" } ?: "任务详情",
                navigationIcon = {
                    AppIconButton(onClick = onBack) {
                        AppIcon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                        )
                    }
                },
                actions = {
                    if (conv != null) {
                        AppIconButton(onClick = {
                            container.conversationRepository.loadConversation(conv.id)
                            Toast.makeText(context, "已载入此任务历史上下文！", Toast.LENGTH_SHORT).show()
                            onRestoreToChat()
                        }) {
                            AppIcon(
                                imageVector = Icons.Default.History,
                                contentDescription = "回溯此任务",
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
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            val stats = remember(conv) {
                                PersistentConversationRepository.computeStats(conv)
                            }
                            AppText(
                                text = "执行步数: ${stats.stepCount} 步 | 耗时: ${stats.durationMs / 1000f}s",
                                style = AppTheme.typography.labelMedium,
                                color = AppTheme.colors.onSurfaceVariant,
                            )
                        }
                        AppButton(onClick = {
                            container.conversationRepository.loadConversation(conv.id)
                            Toast.makeText(context, "已成功回溯此任务上下文！", Toast.LENGTH_SHORT).show()
                            onRestoreToChat()
                        }) {
                            AppText("回溯并继续任务")
                        }
                    }
                }
            }
        },
        containerColor = AppTheme.colors.background,
    ) { innerPadding ->
        if (conv == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                AppText("未找到该任务记录")
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(conv.messages, key = { it.id }) { message ->
                    MessageBubble(message = message)
                }
            }
        }
    }
}
