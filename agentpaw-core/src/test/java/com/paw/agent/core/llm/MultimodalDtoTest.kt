package com.paw.agent.core.llm

import com.paw.agent.core.llm.dto.ChatMessage
import com.paw.agent.core.llm.dto.ChatMessageContent
import com.paw.agent.core.llm.dto.ContentPart
import com.paw.agent.core.llm.dto.ImageUrl
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MultimodalDtoTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Test
    fun `text only message serializes content as string primitive`() {
        val message = ChatMessage(role = "user", content = "Hello agent")
        val serialized = json.encodeToString(ChatMessage.serializer(), message)

        assertTrue(serialized.contains(""""content":"Hello agent""""))

        val deserialized = json.decodeFromString(ChatMessage.serializer(), serialized)
        assertEquals("user", deserialized.role)
        assertEquals("Hello agent", deserialized.textContent)
    }

    @Test
    fun `multimodal message serializes content as parts array`() {
        val parts = listOf(
            ContentPart.TextPart("Analyze this screen"),
            ContentPart.ImagePart(ImageUrl("data:image/jpeg;base64,AAAA")),
        )
        val message = ChatMessage(role = "user", content = ChatMessageContent.Parts(parts))
        val serialized = json.encodeToString(ChatMessage.serializer(), message)

        assertTrue(serialized.contains(""""type":"text""""))
        assertTrue(serialized.contains(""""type":"image_url""""))
        assertTrue(serialized.contains(""""url":"data:image/jpeg;base64,AAAA""""))

        val deserialized = json.decodeFromString(ChatMessage.serializer(), serialized)
        assertEquals("user", deserialized.role)
        assertEquals("Analyze this screen", deserialized.textContent)
        assertTrue(deserialized.content is ChatMessageContent.Parts)
    }
}
