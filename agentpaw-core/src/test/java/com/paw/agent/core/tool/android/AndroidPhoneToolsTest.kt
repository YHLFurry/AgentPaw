package com.paw.agent.core.tool.android

import com.paw.agent.core.agent.AgentContext
import com.paw.agent.core.skill.OpenAndSearchSkill
import com.paw.agent.core.skill.ReturnHomeAndResetSkill
import com.paw.agent.core.skill.SkillRegistry
import com.paw.agent.device.PhoneController
import com.paw.agent.device.ScreenStateInfo
import com.paw.agent.device.ScreenshotResult
import com.paw.agent.device.UiElementInfo
import com.paw.agent.device.VisionResolutionMode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidPhoneToolsTest {

    private class FakePhoneController : PhoneController {
        override val isAccessibilityEnabled: Boolean = true
        override val isShizukuAvailable: Boolean = true

        var lastTap: Pair<Int, Int>? = null
        var lastSwipe: List<Int>? = null
        var lastInput: String? = null
        var lastKeyAction: String? = null
        var lastLaunched: String? = null
        var lastDeepLink: String? = null

        override suspend fun tap(xNormalized: Int, yNormalized: Int): Boolean {
            lastTap = Pair(xNormalized, yNormalized)
            return true
        }

        override suspend fun doubleTap(xNormalized: Int, yNormalized: Int): Boolean = true
        override suspend fun longPress(xNormalized: Int, yNormalized: Int, durationMs: Long): Boolean = true

        override suspend fun swipe(
            startXNormalized: Int,
            startYNormalized: Int,
            endXNormalized: Int,
            endYNormalized: Int,
            durationMs: Long,
        ): Boolean {
            lastSwipe = listOf(startXNormalized, startYNormalized, endXNormalized, endYNormalized)
            return true
        }

        override suspend fun inputText(text: String, clearBeforeInput: Boolean): Boolean {
            lastInput = text
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
            lastLaunched = packageNameOrName
            return true
        }

        override suspend fun openDeepLink(uri: String): Boolean {
            lastDeepLink = uri
            return true
        }

        override suspend fun takeScreenshot(
            mode: VisionResolutionMode,
            cropRoi: List<Int>?,
        ): ScreenshotResult = ScreenshotResult(
            base64Data = "fake_base64",
            width = 1080,
            height = 2400,
            isDownscaled = mode == VisionResolutionMode.FAST,
            modeUsed = mode,
        )

        override suspend fun getScreenState(): ScreenStateInfo = ScreenStateInfo(
            foregroundPackage = "com.test.app",
            foregroundActivity = "com.test.app.MainActivity",
            elements = listOf(
                UiElementInfo(text = "Search", viewId = "search_btn", isClickable = true),
            ),
        )
    }

    private val controller = FakePhoneController()
    private val context = AgentContext("test")

    @Test
    fun `tap tool dispatches normalized coordinates`() = runTest {
        val tool = TapTool(controller)
        val res = tool.execute("""{"x": 300, "y": 700}""", context)
        assertTrue(res.contains("success"))
        assertEquals(Pair(300, 700), controller.lastTap)
    }

    @Test
    fun `swipe tool dispatches scroll trajectory`() = runTest {
        val tool = SwipeTool(controller)
        val res = tool.execute("""{"start_x": 500, "start_y": 800, "end_x": 500, "end_y": 200}""", context)
        assertTrue(res.contains("success"))
        assertEquals(listOf(500, 800, 500, 200), controller.lastSwipe)
    }

    @Test
    fun `input text tool types message`() = runTest {
        val tool = InputTextTool(controller)
        val res = tool.execute("""{"text": "Hello Android Agent"}""", context)
        assertTrue(res.contains("success"))
        assertEquals("Hello Android Agent", controller.lastInput)
    }

    @Test
    fun `key action tool triggers back and home`() = runTest {
        val tool = KeyActionTool(controller)
        tool.execute("""{"action": "BACK"}""", context)
        assertEquals("BACK", controller.lastKeyAction)

        tool.execute("""{"action": "HOME"}""", context)
        assertEquals("HOME", controller.lastKeyAction)
    }

    @Test
    fun `launch app and deeplink tools trigger properly`() = runTest {
        val launchTool = LaunchAppTool(controller)
        launchTool.execute("""{"app_name": "wechat"}""", context)
        assertEquals("wechat", controller.lastLaunched)

        val deepLinkTool = DeepLinkTool(controller)
        deepLinkTool.execute("""{"uri": "alipays://platformapi/startapp"}""", context)
        assertEquals("alipays://platformapi/startapp", controller.lastDeepLink)
    }

    @Test
    fun `screenshot tool returns adaptive screenshot data`() = runTest {
        val tool = TakeScreenshotTool(controller)
        val res = tool.execute("""{"mode": "FAST"}""", context)
        assertTrue(res.contains("fake_base64"))
        assertTrue(res.contains("FAST"))
    }

    @Test
    fun `screen state tool outputs structured hierarchy`() = runTest {
        val tool = GetScreenStateTool(controller)
        val res = tool.execute("{}", context)
        assertTrue(res.contains("com.test.app"))
        assertTrue(res.contains("search_btn"))
    }

    @Test
    fun `skill registry adapts skills to agent tools`() = runTest {
        val registry = SkillRegistry(listOf(ReturnHomeAndResetSkill(), OpenAndSearchSkill()))
        val tools = registry.toTools(controller)

        assertEquals(2, tools.size)
        assertTrue(tools.any { it.definition.name == "skill_return_home" })
        assertTrue(tools.any { it.definition.name == "skill_open_and_search" })

        val homeTool = tools.first { it.definition.name == "skill_return_home" }
        val res = homeTool.execute("{}", context)
        assertTrue(res.contains("success"))
        assertEquals("HOME", controller.lastKeyAction)
    }

    @Test
    fun `safety guard pauses interaction on sensitive payment or password screen`() = runTest {
        val sensitiveController = object : PhoneController by controller {
            override suspend fun getScreenState(): ScreenStateInfo = ScreenStateInfo(
                foregroundPackage = "com.eg.android.AlipayGphone",
                elements = listOf(
                    UiElementInfo(text = "请输入支付密码", isEditable = true),
                ),
            )
        }
        val tapTool = TapTool(sensitiveController)
        val res = tapTool.execute("""{"x": 500, "y": 500}""", context)
        assertTrue(res.contains("SAFETY PAUSE"))
        assertTrue(res.contains("paused"))
    }

    @Test
    fun `scroll and find skill locates and taps matching item`() = runTest {
        var swipeCount = 0
        val scrollingController = object : PhoneController by controller {
            override suspend fun swipe(
                startXNormalized: Int,
                startYNormalized: Int,
                endXNormalized: Int,
                endYNormalized: Int,
                durationMs: Long,
            ): Boolean {
                swipeCount++
                return true
            }

            override suspend fun getScreenState(): ScreenStateInfo {
                return if (swipeCount >= 2) {
                    ScreenStateInfo(
                        foregroundPackage = "com.test.app",
                        elements = listOf(UiElementInfo(text = "目标商品ABC")),
                    )
                } else {
                    ScreenStateInfo(
                        foregroundPackage = "com.test.app",
                        elements = listOf(UiElementInfo(text = "无关内容")),
                    )
                }
            }
        }

        val skill = com.paw.agent.core.skill.ScrollAndFindSkill()
        val result = skill.execute("""{"target_text": "目标商品ABC", "max_swipes": 3}""", scrollingController, context)
        assertTrue(result.contains("found_and_tapped"))
        assertEquals(2, swipeCount)
    }

    @Test
    fun `double tap tool executes successfully`() = runTest {
        val tool = DoubleTapTool(controller)
        val res = tool.execute("""{"x": 400, "y": 600}""", context)
        assertTrue(res.contains("success"))
        assertTrue(res.contains("double_tap"))
    }

    @Test
    fun `long press tool executes with duration`() = runTest {
        val tool = LongPressTool(controller)
        val res = tool.execute("""{"x": 400, "y": 600, "duration_ms": 1500}""", context)
        assertTrue(res.contains("success"))
        assertTrue(res.contains("long_press"))
    }

    @Test
    fun `wait tool sleeps within bounded duration`() = runTest {
        val tool = WaitTool()
        val res = tool.execute("""{"seconds": 0.5}""", context)
        assertTrue(res.contains("success"))
        assertTrue(res.contains("waited_seconds"))
    }

    @Test
    fun `popular app aliases map contains major ecosystem applications`() {
        val aliases = com.paw.agent.device.HybridPhoneController.POPULAR_APP_ALIASES
        assertEquals("com.tencent.mm", aliases["微信"])
        assertEquals("com.eg.android.AlipayGphone", aliases["支付宝"])
        assertEquals("com.taobao.taobao", aliases["淘宝"])
        assertEquals("com.jingdong.app.mall", aliases["京东"])
        assertEquals("com.ss.android.ugc.aweme", aliases["抖音"])
        assertEquals("tv.danmaku.bili", aliases["b站"])
        assertEquals("com.autonavi.minimap", aliases["高德地图"])
        assertEquals("com.android.settings", aliases["设置"])
    }
}

