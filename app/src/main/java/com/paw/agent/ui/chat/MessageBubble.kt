package com.paw.agent.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.paw.agent.R
import com.paw.agent.core.model.Message
import com.paw.agent.core.model.MessageRole
import com.paw.agent.core.model.MessageStatus
import com.paw.agent.ui.components.PawMark
import com.paw.agent.ui.components.StatusPill
import com.paw.agent.ui.components.ThinkingIndicator

/**
 * One message row. User turns are right-aligned in a filled container; agent
 * turns are left-aligned with a paw avatar, matching the app's identity.
 */
@Composable
fun MessageBubble(
    message: Message,
    modifier: Modifier = Modifier,
) {
    val isUser = message.role == MessageRole.USER

    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        if (!isUser) {
            PawMark(
                size = 28.dp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 10.dp, end = 8.dp),
            )
        }

        Column(
            modifier = Modifier.widthIn(max = 320.dp),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
        ) {
            Surface(
                shape = if (isUser) {
                    RoundedCornerShape(
                        topStart = 18.dp,
                        topEnd = 18.dp,
                        bottomStart = 18.dp,
                        bottomEnd = 4.dp,
                    )
                } else {
                    RoundedCornerShape(
                        topStart = 18.dp,
                        topEnd = 18.dp,
                        bottomStart = 4.dp,
                        bottomEnd = 18.dp,
                    )
                },
                color = if (isUser) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
                contentColor = if (isUser) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    when (message.role) {
                        MessageRole.TOOL -> ToolMessageBody(message)
                        else -> AssistantOrUserBody(message)
                    }
                }
            }

            // Status line: errors, cancellation, streaming indicator.
            when {
                message.status == MessageStatus.FAILED && message.error != null -> {
                    Spacer(Modifier.height(6.dp))
                    StatusPill(
                        text = message.error,
                        container = MaterialTheme.colorScheme.errorContainer,
                        content = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }

                message.status == MessageStatus.CANCELLED -> {
                    Spacer(Modifier.height(6.dp))
                    StatusPill(text = stringResource(R.string.chat_stopped))
                }

                message.status == MessageStatus.STREAMING && message.content.isEmpty() -> {
                    Spacer(Modifier.height(6.dp))
                    ThinkingIndicator()
                }
            }
        }
    }
}

@Composable
private fun AssistantOrUserBody(message: Message) {
    Text(
        text = message.content,
        style = MaterialTheme.typography.bodyLarge,
    )
}

@Composable
private fun ToolMessageBody(message: Message) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Outlined.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.size(8.dp))
        Text(
            text = message.content,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Compact avatar kept for symmetry with the launcher mark. */
@Composable
fun AgentAvatar(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.size(28.dp),
        shape = RoundedCornerShape(percent = 50),
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Icon(
            imageVector = Icons.Filled.Pets,
            contentDescription = stringResource(R.string.cd_paw_logo),
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(5.dp),
        )
    }
}
