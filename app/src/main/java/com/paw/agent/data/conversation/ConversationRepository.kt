package com.paw.agent.data.conversation

import com.paw.agent.core.model.Conversation
import com.paw.agent.core.model.Message
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

/**
 * Holds conversation state.
 *
 * Provides [InMemoryConversationRepository] for testing and lightweight usages,
 * and [PersistentConversationRepository] for production disk persistence, screenshot
 * asset storage, and history analysis.
 */
interface ConversationRepository {
    val conversation: StateFlow<Conversation>
    val isInitialized: StateFlow<Boolean>

    fun newConversation(): String
    fun addMessage(message: Message)
    /** Replaces the message with [id] — used to grow a streaming assistant turn. */
    fun updateMessage(id: String, transform: (Message) -> Message)
    fun appendToMessage(id: String, text: String)
    fun removeMessage(id: String)
    fun clear()
    fun observe(): Flow<Conversation> = conversation
}

class InMemoryConversationRepository : ConversationRepository {

    private val _isInitialized = MutableStateFlow(true)
    override val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    private val _conversation = MutableStateFlow(
        Conversation(
            id = UUID.randomUUID().toString(),
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
        ),
    )
    override val conversation: StateFlow<Conversation> = _conversation.asStateFlow()

    override fun newConversation(): String {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        _conversation.value = Conversation(id = id, createdAt = now, updatedAt = now)
        return id
    }

    override fun addMessage(message: Message) {
        _conversation.update { current ->
            current.copy(
                messages = current.messages + message,
                title = current.title.ifBlank { message.content.take(TITLE_MAX_CHARS) },
                updatedAt = System.currentTimeMillis(),
            )
        }
    }

    override fun updateMessage(id: String, transform: (Message) -> Message) {
        _conversation.update { current ->
            current.copy(
                messages = current.messages.map { if (it.id == id) transform(it) else it },
                updatedAt = System.currentTimeMillis(),
            )
        }
    }

    override fun appendToMessage(id: String, text: String) {
        if (text.isEmpty()) return
        updateMessage(id) { it.copy(content = it.content + text) }
    }

    override fun removeMessage(id: String) {
        _conversation.update { current ->
            current.copy(
                messages = current.messages.filterNot { it.id == id },
                updatedAt = System.currentTimeMillis(),
            )
        }
    }

    override fun clear() {
        val now = System.currentTimeMillis()
        _conversation.update { it.copy(messages = emptyList(), title = "", updatedAt = now) }
    }

    private companion object {
        const val TITLE_MAX_CHARS = 40
    }
}
