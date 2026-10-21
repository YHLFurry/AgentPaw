package com.paw.agent.core.skill

import com.paw.agent.core.llm.LlmChunk
import com.paw.agent.core.llm.LlmClient
import com.paw.agent.core.llm.LlmConfig
import com.paw.agent.core.llm.dto.ChatCompletionRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 许愿式生成。
 *
 * 这些用例模拟真实模型的各种「不听话」：包了代码块、枚举小写、数字写成字符串、
 * 第一步就漏字段。生成器必须能吸收这些偏差，而不是把脏 JSON 直接丢给用户。
 */
class SkillWishComposerTest {

    private class QueueLlmClient(vararg responses: String) : LlmClient {
        private val queue = ArrayDeque(responses.toList())
        var calls = 0
            private set

        override fun complete(config: LlmConfig, request: ChatCompletionRequest): Flow<LlmChunk> = flow {
            calls++
            val next = queue.removeFirstOrNull() ?: ""
            emit(LlmChunk.Delta(next))
            emit(LlmChunk.Done("stop", null))
        }

        override suspend fun testConnection(config: LlmConfig): Result<Unit> = Result.success(Unit)
    }

    private val config = LlmConfig(
        baseUrl = "https://api.example.com/v1",
        apiKey = "sk-test",
        model = "test-model",
    )

    private val validJson = """
        ```json
        {
          "displayName": "微信搜索好友",
          "key": "skill_wx_search",
          "description": "启动微信并搜索指定好友，适用于快速定位联系人的场景",
          "emoji": "🔍",
          "category": "社交",
          "parameters": [
            {"key": "keyword", "type": "text", "description": "搜索关键词", "required": true}
          ],
          "steps": [
            {"id": "step_1", "kind": "launch_app", "target": "微信"},
            {"id": "step_2", "kind": "tap_element", "target": "搜索"},
            {"id": "step_3", "kind": "input_text", "target": "{{keyword}}", "x": "500", "y": "600", "onError": "abort"}
          ]
        }
        ```
    """.trimIndent()

    @Test
    fun `parses fenced json with lowercase enums and stringified numbers`() = runTest {
        val composer = SkillWishComposer(QueueLlmClient(validJson))

        val result = composer.compose("打开微信搜索好友", config)

        assertTrue(result is SkillWishResult.Success)
        val success = result as SkillWishResult.Success
        val def = success.definition
        assertEquals(SkillSource.WISH, def.source)
        assertEquals("skill_wx_search", def.key)
        assertEquals(SkillActionKind.LAUNCH_APP, def.steps[0].kind)
        assertEquals(SkillActionKind.TAP_ELEMENT, def.steps[1].kind)
        assertEquals(500, def.steps[2].x)
        assertEquals(600, def.steps[2].y)
        assertEquals(SkillParamType.TEXT, def.parameters.single().type)
        assertEquals(false, success.repaired)
        assertTrue(success.issues.none { it.isError })
    }

    @Test
    fun `repairs once when the first answer fails validation`() = runTest {
        val broken = """{"displayName":"空技能","key":"skill_broken","description":"故意缺少步骤用来触发一次修正","steps":[]}"""
        val client = QueueLlmClient(broken, validJson)
        val composer = SkillWishComposer(client)

        val result = composer.compose("打开微信搜索好友", config)

        assertTrue(result is SkillWishResult.Success)
        val success = result as SkillWishResult.Success
        assertEquals(2, success.attempts)
        assertTrue(success.repaired)
        assertEquals(2, client.calls)
        assertEquals("skill_wx_search", success.definition.key)
    }

    @Test
    fun `falls back to the quick-notes format when the model ignores the json contract`() = runTest {
        val prose = """
            名称: 打开微信
            标识: skill_open_wx
            说明: 打开微信并等待首页出现，适合作为其他技能的前置步骤
            ---
            打开 微信
            等待 800ms
        """.trimIndent()
        val composer = SkillWishComposer(QueueLlmClient(prose))

        val result = composer.compose("打开微信", config)

        assertTrue(result is SkillWishResult.Success)
        val def = (result as SkillWishResult.Success).definition
        assertEquals(2, def.steps.size)
        assertEquals(SkillActionKind.LAUNCH_APP, def.steps[0].kind)
    }

    @Test
    fun `avoids key collision with existing skills`() = runTest {
        val composer = SkillWishComposer(QueueLlmClient(validJson))

        val result = composer.compose(
            "打开微信搜索好友",
            config,
            existingKeys = setOf("skill_wx_search"),
        )

        val def = (result as SkillWishResult.Success).definition
        assertEquals("skill_wx_search_2", def.key)
    }

    @Test
    fun `reports failure with the raw output when nothing parses`() = runTest {
        // 第一次和「自动修正」那一次都返回无法解析的内容
        val composer = SkillWishComposer(
            QueueLlmClient("抱歉，我无法完成这个请求。", "抱歉，我仍然无法完成。"),
        )

        val result = composer.compose("随便", config)

        assertTrue(result is SkillWishResult.Failure)
        val failure = result as SkillWishResult.Failure
        assertNotNull(failure.message)
        assertTrue(failure.rawText.contains("抱歉"))
    }

    @Test
    fun `rejects unusable config before calling the model`() = runTest {
        val client = QueueLlmClient(validJson)
        val composer = SkillWishComposer(client)

        val result = composer.compose("打开微信", LlmConfig())

        assertTrue(result is SkillWishResult.Failure)
        assertEquals(0, client.calls)
    }

    @Test
    fun `extracts json from surrounding prose`() {
        val composer = SkillWishComposer(QueueLlmClient())
        val raw = "好的，这是技能：\n{\"displayName\":\"x\"}\n希望有帮助！"
        assertEquals("{\"displayName\":\"x\"}", composer.extractJson(raw))
    }
}
