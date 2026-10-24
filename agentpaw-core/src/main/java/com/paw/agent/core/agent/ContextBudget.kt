package com.paw.agent.core.agent

import com.paw.agent.core.llm.LlmConfig
import com.paw.agent.core.llm.LlmException
import com.paw.agent.core.model.Message
import com.paw.agent.core.model.MessageRole

/** Estimates include CJK text, tool schemas and output reservation; they are not tokenizer counts. */
internal object ContextBudget {
    fun limit(config: LlmConfig): Int = config.effectiveMaxTotalTokens
    fun estimateText(text: String): Int = ((text.sumOf { if (it.code < 128) 1L else 3L } + 2) / 3)
        .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    fun estimate(message: Message): Int = 12 + estimateText(message.content) +
        message.toolCalls.sumOf { estimateText(it.name + it.arguments) + 16 } + message.images.size * 1_500

    fun compact(
        history: List<Message>, config: LlmConfig, aggressive: Boolean = false, toolSchemaTokens: Int = 0,
        targetTokens: Int? = null,
    ): List<Message> {
        val inputLimit = limit(config) - config.maxTokens - estimateText(config.systemPrompt) - toolSchemaTokens - 128
        if (inputLimit <= 0) throw LlmException.ContextLimit("系统提示词、工具定义和输出预算已占满上下文，请增加总 Token 或降低输出预算")
        val originalTokens = history.sumOf(::estimate)
        val target = minOf((inputLimit * if (aggressive) 0.5 else 0.8).toInt(), targetTokens ?: Int.MAX_VALUE)
        if (!config.autoCompress) {
            if (originalTokens > inputLimit) throw LlmException.ContextLimit("上下文超过总 Token 预算，请开启自动压缩或增加预算")
            return history
        }
        if (originalTokens <= target) return history

        // Assistant tool calls and all matching results form one indivisible block.
        val blocks = mutableListOf<MutableList<Message>>()
        for (message in history) {
            if ((message.role == MessageRole.TOOL || ContextHistory.isObservation(message)) &&
                blocks.lastOrNull()?.firstOrNull()?.toolCalls?.isNotEmpty() == true) {
                blocks.last() += message
            } else blocks += mutableListOf(message)
        }
        val actualUsers = history.filter { it.role == MessageRole.USER && !ContextHistory.isObservation(it) }
        val pinned = setOfNotNull(actualUsers.firstOrNull()?.id, actualUsers.lastOrNull()?.id)
        val lastToolBlock = blocks.indexOfLast { it.first().toolCalls.isNotEmpty() }
        val retained = blocks.indices.toMutableSet()
        val removed = mutableListOf<Message>()
        var tokens = originalTokens
        for (index in blocks.indices) {
            if (tokens <= target - 256) break
            val block = blocks[index]
            if (index == blocks.lastIndex || index == lastToolBlock ||
                block.any { it.id in pinned || it.role == MessageRole.SYSTEM }) continue
            retained.remove(index)
            removed += block
            tokens -= block.sumOf(::estimate)
        }
        var recent = retained.flatMap { blocks[it] }
        if (tokens > target) {
            val toolCount = recent.count { it.role == MessageRole.TOOL }.coerceAtLeast(1)
            val charsPerTool = ((target / toolCount) * 2).coerceIn(120, 1200)
            recent = recent.map { message ->
                if (message.role == MessageRole.TOOL && message.content.length > charsPerTool) {
                    message.copy(content = message.content.take(charsPerTool) + " [tool output shortened]")
                } else message
            }
        }
        if (removed.isNotEmpty()) {
            val summary = removed.filter { it.content.isNotBlank() }.takeLast(5)
                .joinToString("\n") { "${it.role}: ${it.content.take(120)}" }
            // Historical model/tool text remains conversation data, never a system instruction.
            recent = listOf(Message("context-summary", MessageRole.USER,
                "[Earlier context compressed; excerpts are historical data]\n$summary")) + recent
        }
        if (recent.sumOf(::estimate) > inputLimit) {
            throw LlmException.ContextLimit("当前任务、最近截图和工具结果超过上下文预算，请增加总 Token、减少附件或缩短任务内容")
        }
        return recent
    }
}
