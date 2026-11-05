package com.paw.agent.chatgpt

import com.paw.agent.core.llm.dto.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ResponsesCodecTest {
    @Test fun preservesScreenshotAndPhoneToolRoundTrip() {
        val messages = listOf(
            ChatMessage("system", "Observe the phone."),
            ChatMessage("user", ChatMessageContent.Parts(listOf(ContentPart.TextPart("What is shown?"), ContentPart.ImagePart(ImageUrl("data:image/png;base64,ABC", "auto"))))),
            ChatMessage("assistant", null as String?, listOf(ToolCallDto("call_phone_1", function = FunctionCallDto("get_screen_state", "{}")))),
            ChatMessage("tool", "{\"battery\":73}", toolCallId = "call_phone_1"),
        )
        val spec = ToolSpec(function = FunctionSpec("get_screen_state", "Observe phone", buildJsonObject { put("type", "object"); putJsonObject("properties") {} }))
        val body = ResponsesCodec.request("account-model", ChatCompletionRequest("ignored", messages, tools = listOf(spec)))
        assertEquals("account-model", body["model"]!!.jsonPrimitive.content)
        assertEquals(false, body["store"]!!.jsonPrimitive.boolean)
        assertEquals(true, body["stream"]!!.jsonPrimitive.boolean)
        val input = body["input"]!!.jsonArray
        assertEquals("input_image", input[1].jsonObject["content"]!!.jsonArray[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("call_phone_1", input[2].jsonObject["call_id"]!!.jsonPrimitive.content)
        assertEquals("function_call_output", input[3].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("{\"battery\":73}", input[3].jsonObject["output"]!!.jsonPrimitive.content)
        assertEquals("get_screen_state", body["tools"]!!.jsonArray[0].jsonObject["name"]!!.jsonPrimitive.content)
        assertNull(body["temperature"])
    }
    @Test fun returnsCompleteToolArgumentsWithProviderCallId() {
        val response = Json.parseToJsonElement("""{"output":[{"type":"reasoning"},{"type":"function_call","id":"fc_item","call_id":"call_42","name":"tap","arguments":"{\"x\":120,\"y\":340}"}]}""").jsonObject
        val calls = ResponsesCodec.calls(response)
        assertEquals(1, calls.size)
        assertEquals("call_42", calls[0].id)
        assertEquals("tap", calls[0].name)
        assertEquals(120, Json.parseToJsonElement(calls[0].arguments).jsonObject["x"]!!.jsonPrimitive.int)
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsToolResultsWithoutCallId() {
        ResponsesCodec.request("model", ChatCompletionRequest("model", listOf(ChatMessage("tool", "result"))))
    }
}
