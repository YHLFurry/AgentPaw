package com.paw.agent.core.agent

import com.paw.agent.core.model.Message
import com.paw.agent.core.model.MessageRole

internal object ContextHistory {
    fun isObservation(message: Message): Boolean = message.role == MessageRole.USER &&
        message.id.endsWith("_obs") && message.content.startsWith("[Current Screen Observation]")

    /** Repairs interrupted exchanges without replaying tools, and places results before observations. */
    fun normalize(history: List<Message>): List<Message> = buildList {
        var index = 0
        while (index < history.size) {
            val message = history[index++]
            if (message.role == MessageRole.TOOL) continue
            add(message)
            if (message.role != MessageRole.ASSISTANT || message.toolCalls.isEmpty()) continue
            val results = linkedMapOf<String, Message>()
            val observations = mutableListOf<Message>()
            while (index < history.size) {
                val next = history[index]
                if (next.role == MessageRole.TOOL) next.toolCallId?.let { results[it] = next }
                else if (isObservation(next)) observations += next
                else break
                index++
            }
            message.toolCalls.forEach { call ->
                val result = results[call.id]
                add(result?.copy(content = SensitiveDataMasker.mask(result.fullLog ?: result.content), fullLog = null)
                    ?: Message("${call.id}_interrupted", MessageRole.TOOL,
                        "Tool execution was interrupted; no result is available. Do not assume this action succeeded.",
                        toolCallId = call.id))
            }
            addAll(observations)
        }
    }

    fun retainImages(history: List<Message>): List<Message> {
        val latestAttachment = history.indexOfLast { it.role == MessageRole.USER && !isObservation(it) && it.images.isNotEmpty() }
        val observations = history.indices.filter { isObservation(history[it]) && history[it].images.isNotEmpty() }
            .takeLast(2).toSet()
        return history.mapIndexed { index, message ->
            if (message.images.isNotEmpty() && index != latestAttachment && index !in observations) {
                message.copy(images = emptyList(), content = message.content + " [Earlier image omitted]")
            } else message
        }
    }
}
