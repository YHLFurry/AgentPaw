package com.paw.agent.core.skill

import com.paw.agent.core.agent.AgentContext
import com.paw.agent.device.PhoneController
import com.paw.agent.device.RectBounds
import com.paw.agent.device.ScreenStateInfo
import com.paw.agent.device.ScreenshotResult
import com.paw.agent.device.UiElementInfo
import com.paw.agent.device.VisionResolutionMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomExecutableSkillTest {

    private class FakeTestPhoneController : PhoneController {
        var lastLaunchedApp: String? = null
        var lastTapCoordinate: Pair<Int, Int>? = null
        var lastTapPixel: Pair<Float, Float>? = null
        var lastInputText: String? = null
        var lastKeyAction: String? = null
        var fakeElements: List<UiElementInfo> = emptyList()

        override val isAccessibilityEnabled: Boolean get() = true
        override val isShizukuAvailable: Boolean get() = false

        override suspend fun openDeepLink(uri: String): Boolean = true

        override suspend fun takeScreenshot(mode: VisionResolutionMode, cropRoi: List<Int>?): ScreenshotResult? {
            return ScreenshotResult("", 1080, 2400, false)
        }

        override suspend fun tap(xNormalized: Int, yNormalized: Int, cropRoi: List<Int>?): Boolean {
            lastTapCoordinate = Pair(xNormalized, yNormalized)
            return true
        }

        override suspend fun doubleTap(xNormalized: Int, yNormalized: Int, cropRoi: List<Int>?): Boolean = true
        override suspend fun longPress(xNormalized: Int, yNormalized: Int, durationMs: Long, cropRoi: List<Int>?): Boolean = true

        override suspend fun tapAtPixel(centerX: Float, centerY: Float): Boolean {
            lastTapPixel = Pair(centerX, centerY)
            return true
        }

        override suspend fun swipe(
            startXNormalized: Int,
            startYNormalized: Int,
            endXNormalized: Int,
            endYNormalized: Int,
            durationMs: Long,
        ): Boolean = true

        override suspend fun inputText(text: String, clearBeforeInput: Boolean): Boolean {
            lastInputText = text
            return true
        }

        override suspend fun pressBack(): Boolean {
            lastKeyAction = "BACK"
            return true
        }

        override suspend fun pressHome(): Boolean {
            lastKeyAction = "HOME"
            return true
        }

        override suspend fun pressRecents(): Boolean {
            lastKeyAction = "RECENTS"
            return true
        }

        override suspend fun pressEnter(): Boolean {
            lastKeyAction = "ENTER"
            return true
        }

        override suspend fun launchApp(packageNameOrName: String): Boolean {
            lastLaunchedApp = packageNameOrName
            return true
        }

        override suspend fun getScreenState(): ScreenStateInfo {
            return ScreenStateInfo(elements = fakeElements)
        }
    }

    @Test
    fun `TAP_ELEMENT prioritizes exact match over partial contains match`() = runBlocking {
        val controller = FakeTestPhoneController()
        controller.fakeElements = listOf(
            UiElementInfo(text = "搜索结果历史", bounds = RectBounds(10, 10, 30, 30)),
            UiElementInfo(text = "搜索", bounds = RectBounds(100, 100, 200, 200)),
        )

        val skillDef = CustomSkillDefinition(
            id = "test-skill-1",
            name = "skill_search",
            displayName = "执行搜索",
            description = "搜索指定关键词",
            actions = listOf(
                SkillActionStep(
                    type = SkillActionType.TAP_ELEMENT,
                    target = "搜索",
                    waitMillis = 0L,
                ),
            ),
        )

        val skill = CustomExecutableSkill(skillDef)
        val context = AgentContext("test-conv-1")

        val result = skill.execute("{}", controller, context)

        assertTrue(result.contains("\"status\":\"success\""))
        assertEquals(Pair(150.0f, 150.0f), controller.lastTapPixel)
    }

    @Test
    fun `TAP_ELEMENT with exactMatch true ignores fuzzy matches`() = runBlocking {
        val controller = FakeTestPhoneController()
        controller.fakeElements = listOf(
            UiElementInfo(text = "搜索结果历史", bounds = RectBounds(10, 10, 30, 30)),
        )

        val skillDef = CustomSkillDefinition(
            id = "test-skill-2",
            name = "skill_exact_search",
            displayName = "精确搜索",
            description = "测试精确匹配",
            actions = listOf(
                SkillActionStep(
                    type = SkillActionType.TAP_ELEMENT,
                    target = "搜索",
                    exactMatch = true,
                    waitMillis = 0L,
                ),
            ),
        )

        val skill = CustomExecutableSkill(skillDef)
        val context = AgentContext("test-conv-2")

        skill.execute("{}", controller, context)

        // Exact match not found, so no tap was executed
        assertEquals(null, controller.lastTapPixel)
    }

    @Test
    fun `executes interpolated parameters into steps`() = runBlocking {
        val controller = FakeTestPhoneController()

        val skillDef = CustomSkillDefinition(
            id = "test-skill-3",
            name = "skill_send_msg",
            displayName = "发消息",
            description = "发送消息给指定联系人",
            parametersSchema = """{"type":"object","properties":{"user":{"type":"string"}}}""",
            actions = listOf(
                SkillActionStep(
                    type = SkillActionType.INPUT_TEXT,
                    target = "你好，{{user}}！",
                    waitMillis = 0L,
                ),
            ),
        )

        val skill = CustomExecutableSkill(skillDef)
        val context = AgentContext("test-conv-3")

        skill.execute("""{"user":"小明"}""", controller, context)

        assertEquals("你好，小明！", controller.lastInputText)
    }

    @Test
    fun `WAIT action executes single delay without double delay`() = runBlocking {
        val controller = FakeTestPhoneController()

        val skillDef = CustomSkillDefinition(
            id = "test-skill-4",
            name = "skill_wait_test",
            displayName = "等待测试",
            description = "测试单步等待时长",
            actions = listOf(
                SkillActionStep(
                    type = SkillActionType.WAIT,
                    target = "30",
                    waitMillis = 30L,
                ),
            ),
        )

        val skill = CustomExecutableSkill(skillDef)
        val context = AgentContext("test-conv-4")

        val start = System.currentTimeMillis()
        val result = skill.execute("{}", controller, context)
        val elapsed = System.currentTimeMillis() - start

        assertTrue(result.contains("\"executed_steps\":1"))
        assertTrue(elapsed >= 25L)
    }
}
