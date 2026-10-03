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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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

@OptIn(ExperimentalMaterial3Api::class)
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

    // Keep the newest message in view as tokens stream in.
    LaunchedEffect(messages.size, messages.lastOrNull()?.content?.length) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PawMark(size = 26.dp, color = MaterialTheme.colorScheme.onPrimary)
                        Spacer(Modifier.size(10.dp))
                        Column {
                            Text(
                                text = stringResource(R.string.chat_title),
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (modelLabel.isNotBlank()) {
                                Text(
                                    text = modelLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onNewConversation) {
                        Icon(
                            imageVector = Icons.Outlined.Settings,
                            contentDescription = stringResource(R.string.chat_open_settings),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
        bottomBar = {
            Composer(
                draft = draft,
                isGenerating = isGenerating,
                enabled = draft.isNotBlank() || isGenerating,
                onDraftChange = onDraftChange,
                onSendOrStop = onSendOrStop,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
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
                    items(items = messages, key = { it.id }) { message ->
                        MessageBubble(message = message)
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
    enabled: Boolean,
    onDraftChange: (String) -> Unit,
    onSendOrStop: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
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
                OutlinedTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.chat_input_hint)) },
                    shape = RoundedCornerShape(24.dp),
                    maxLines = 5,
                )

                Surface(
                    onClick = onSendOrStop,
                    enabled = enabled,
                    shape = RoundedCornerShape(percent = 50),
                    color = if (isGenerating) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    contentColor = if (isGenerating) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.onPrimary
                    },
                ) {
                    Box(
                        Modifier.size(52.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
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
