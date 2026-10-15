package com.paw.agent.core.tool.android

import com.paw.agent.core.agent.AgentContext
import com.paw.agent.device.PhoneController
import com.paw.agent.device.RectBounds
import com.paw.agent.device.ScreenStateInfo
import com.paw.agent.device.ScreenshotResult
import com.paw.agent.device.UiElementInfo
import com.paw.agent.device.VisionResolutionMode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ISSUE-205：工具返回 JSON 的结构化序列化。
 *
 * 背景缺陷：`launch_app` / `open_deeplink` 用字符串插值拼接返回 JSON，
 * LLM 生成的 app_name / uri 一旦包含 `"`、`\` 等字符就会产出结构损坏的
 * JSON，污染会话历史与后续 LLM 上下文（input_text 的同类问题 SEC-007
 * 已修复，这两个工具被遗漏）。
 */
class AndroidPhoneToolsJsonSafetyTest {

    private class FakePhoneController(
        private val launchResult: Boolean = true,
        private val deepLinkResult: Boolean = true,
    ) : PhoneController {
        override val isAccessibilityEnabled: Boolean = true
        override val isShizukuAvailable: Boolean = true

        override suspend fun tap(xNormalized: Int, yNormalized: Int, cropRoi: List<Int>?): Boolean = true
        override suspend fun doubleTap(xNormalized: Int, yNormalized: Int, cropRoi: List<Int>?): Boolean = true
        override suspend fun longPress(xNormalized: Int, yNormalized: Int, durationMs: Long, cropRoi: List<Int>?): Boolean = true
        override suspend fun tapAtPixel(centerX: Float, centerY: Float): Boolean = true
        override suspend fun swipe(
            startXNormalized: Int,
            startYNormalized: Int,
            endXNormalized: Int,
            endYNormalized: Int,
            durationMs: Long,
        ): Boolean = true

        override suspend fun inputText(text: String, clearBeforeInput: Boolean): Boolean = true
        override suspend fun pressBack(): Boolean = true
        override suspend fun pressHome(): Boolean = true
        override suspend fun pressRecents(): Boolean = true
        override suspend fun pressEnter(): Boolean = true
        override suspend fun launchApp(packageNameOrName: String): Boolean = launchResult
        override suspend fun openDeepLink(uri: String): Boolean = deepLinkResult

        override suspend fun takeScreenshot(
            mode: VisionResolutionMode,
            cropRoi: List<Int>?,
        ): ScreenshotResult = ScreenshotResult(
            base64Data = "fake_base64",
            width = 1080,
            height = 2400,
            isDownscaled = false,
            modeUsed = mode,
        )

        override suspend fun getScreenState(): ScreenStateInfo = ScreenStateInfo(
            foregroundPackage = "com.test.app",
            foregroundActivity = "com.test.app.MainActivity",
            elements = listOf(
                UiElementInfo(text = "Search", viewId = "search_btn", isClickable = true, bounds = RectBounds(0, 0, 100, 100)),
            ),
        )
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val context = AgentContext("test")

    @Test
    fun `launch_app result stays valid JSON with quote and backslash in app name`() = runTest {
        val tool = LaunchAppTool(FakePhoneController())
        // LLM 可能生成带引号/反斜杠的入参；修复前字符串插值会产出损坏 JSON
        val trickyName = "my\"app\\name"

        val result = tool.execute("""{"app_name":"my\"app\\name"}""", context)

        val obj = json.parseToJsonElement(result).jsonObject
        assertEquals("success", obj["status"]?.jsonPrimitive?.content)
        assertEquals("入参必须原样往返，不被 JSON 结构破坏", trickyName, obj["launched"]?.jsonPrimitive?.content)
    }

    @Test
    fun `launch_app result stays valid JSON with chinese app name`() = runTest {
        val tool = LaunchAppTool(FakePhoneController())

        val result = tool.execute("""{"app_name":"网易云音乐"}""", context)

        val obj = json.parseToJsonElement(result).jsonObject
        assertEquals("success", obj["status"]?.jsonPrimitive?.content)
        assertEquals("网易云音乐", obj["launched"]?.jsonPrimitive?.content)
    }

    @Test
    fun `launch_app error result stays valid JSON with quote in app name`() = runTest {
        val tool = LaunchAppTool(FakePhoneController(launchResult = false))

        val result = tool.execute("""{"app_name":"we\"ird"}""", context)

        val obj = json.parseToJsonElement(result).jsonObject
        assertEquals("error", obj["status"]?.jsonPrimitive?.content)
        assertTrue(obj["message"]?.jsonPrimitive?.content?.contains("we\"ird") == true)
    }

    @Test
    fun `open_deeplink result stays valid JSON with quote in uri`() = runTest {
        val tool = DeepLinkTool(FakePhoneController())
        val trickyUri = "https://example.com/path?a=1&b=\"2\""
        val arguments = json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject { put("uri", JsonPrimitive(trickyUri)) },
        )

        val result = tool.execute(arguments, context)

        val obj = json.parseToJsonElement(result).jsonObject
        assertEquals("success", obj["status"]?.jsonPrimitive?.content)
        assertEquals("入参必须原样往返，不被 JSON 结构破坏", trickyUri, obj["opened"]?.jsonPrimitive?.content)
    }

    @Test
    fun `open_deeplink error result stays valid JSON`() = runTest {
        val tool = DeepLinkTool(FakePhoneController(deepLinkResult = false))

        val result = tool.execute("""{"uri":"https://example.com/x"}""", context)

        val obj = json.parseToJsonElement(result).jsonObject
        assertEquals("error", obj["status"]?.jsonPrimitive?.content)
    }
}
