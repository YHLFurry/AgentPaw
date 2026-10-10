package com.paw.agent.core.tool.android

import com.paw.agent.core.agent.AgentContext
import com.paw.agent.core.skill.OpenAndSearchSkill
import com.paw.agent.core.skill.ReturnHomeAndResetSkill
import com.paw.agent.core.skill.ScrollAndFindSkill
import com.paw.agent.core.skill.SkillRegistry
import com.paw.agent.core.tool.android.ClickElementTool
import com.paw.agent.device.PhoneController
import com.paw.agent.device.RectBounds
import com.paw.agent.device.ScreenStateInfo
import com.paw.agent.device.ScreenshotResult
import com.paw.agent.device.UiElementInfo
import com.paw.agent.device.VisionResolutionMode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidPhoneToolsTest {

    private class FakePhoneController : PhoneController {
        override val isAccessibilityEnabled: Boolean = true
        override val isShizukuAvailable: Boolean = true

        var lastTap: Pair<Int, Int>? = null
        var lastTapCropRoi: List<Int>? = null
        var lastTapAtPixel: Pair<Float, Float>? = null
        var lastSwipe: List<Int>? = null
        var lastInput: String? = null
        var lastKeyAction: String? = null
        var lastLaunched: String? = null
        var lastDeepLink: String? = null

        override suspend fun tap(xNormalized: Int, yNormalized: Int, cropRoi: List<Int>?): Boolean {
            lastTap = Pair(xNormalized, yNormalized)
            lastTapCropRoi = cropRoi
            return true
        }

        override suspend fun doubleTap(xNormalized: Int, yNormalized: Int, cropRoi: List<Int>?): Boolean = true
        override suspend fun longPress(xNormalized: Int, yNormalized: Int, durationMs: Long, cropRoi: List<Int>?): Boolean = true
        override suspend fun tapAtPixel(centerX: Float, centerY: Float): Boolean {
            lastTapAtPixel = Pair(centerX, centerY)
            return true
        }

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
        // General non-payment deeplink executes directly
        val normalRes = deepLinkTool.execute("""{"uri": "bilibili://video/123"}""", context)
        assertTrue(normalRes.contains("success"))
        assertEquals("bilibili://video/123", controller.lastDeepLink)

        // Payment deeplink requires confirmation when unconfirmed
        val riskRes = deepLinkTool.execute("""{"uri": "alipays://platformapi/startapp"}""", context)
        assertTrue(riskRes.contains("requires_confirmation"))

        // Payment deeplink executes when confirmed via context grant
        val confirmedContext = AgentContext("test", grantedTokens = setOf("risk_confirmed:open_deeplink"))
        val confirmedRes = deepLinkTool.execute("""{"uri": "alipays://platformapi/startapp"}""", confirmedContext)
        assertTrue(confirmedRes.contains("success"))
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

    /**
     * A phone controller that returns a single known element and records the exact
     * pixel point passed to [tapAtPixel]. Used to verify pixel-accurate clicking and
     * to guard against the old hardcoded 1080x2400 normalization bug.
     */
    private class SkillFakeController(val element: UiElementInfo) : PhoneController by FakePhoneController() {
        var lastTapAtPixel: Pair<Float, Float>? = null

        override suspend fun getScreenState(): ScreenStateInfo =
            ScreenStateInfo(foregroundPackage = "com.test.app", elements = listOf(element))

        override suspend fun tapAtPixel(centerX: Float, centerY: Float): Boolean {
            lastTapAtPixel = Pair(centerX, centerY)
            return true
        }
    }

    @Test
    fun `click_element taps the exact pixel center of an element resolved by index`() = runTest {
        val elem = UiElementInfo(text = "确认", bounds = RectBounds(10, 20, 110, 120)) // center (60, 70)
        val ctrl = SkillFakeController(elem)
        val tool = ClickElementTool(ctrl)
        val res = tool.execute("""{"index": 0}""", context)
        assertTrue(res.contains("success"))
        assertEquals(Pair(60f, 70f), ctrl.lastTapAtPixel)
    }

    @Test
    fun `click_element taps the exact pixel center from explicit bounds`() = runTest {
        val ctrl = SkillFakeController(UiElementInfo(text = "x", bounds = RectBounds(10, 20, 110, 120)))
        val tool = ClickElementTool(ctrl)
        val res = tool.execute("""{"bounds": [10, 20, 110, 120]}""", context)
        assertTrue(res.contains("success"))
        assertEquals(Pair(60f, 70f), ctrl.lastTapAtPixel)
    }

    @Test
    fun `click_element resolves and taps an element matched by text`() = runTest {
        val elem = UiElementInfo(text = "确认订单", bounds = RectBounds(200, 800, 400, 900)) // center (300, 850)
        val ctrl = SkillFakeController(elem)
        val tool = ClickElementTool(ctrl)
        val res = tool.execute("""{"text": "确认"}""", context)
        assertTrue(res.contains("success"))
        assertEquals(Pair(300f, 850f), ctrl.lastTapAtPixel)
    }

    @Test
    fun `click_element pauses on sensitive payment or password screen`() = runTest {
        val sensitiveController = object : PhoneController by controller {
            override suspend fun getScreenState(): ScreenStateInfo = ScreenStateInfo(
                foregroundPackage = "com.eg.android.AlipayGphone",
                elements = listOf(UiElementInfo(text = "请输入支付密码", isEditable = true)),
            )
        }
        val tool = ClickElementTool(sensitiveController)
        val res = tool.execute("""{"text": "支付"}""", context)
        assertTrue(res.contains("SAFETY PAUSE"))
        assertTrue(res.contains("paused"))
    }

    @Test
    fun `tap tool forwards crop_roi to the controller`() = runTest {
        val tool = TapTool(controller)
        val res = tool.execute("""{"x": 100, "y": 200, "crop_roi": [0, 0, 500, 500]}""", context)
        assertTrue(res.contains("success"))
        assertEquals(Pair(100, 200), controller.lastTap)
        assertEquals(listOf(0, 0, 500, 500), controller.lastTapCropRoi)
    }

    @Test
    fun `open_and_search skill clicks at real pixel center, not a hardcoded resolution`() = runTest {
        // 关键回归：旧实现用写死的 1080x2400 把 bounds 转成归一化坐标，
        // 在非 1080x2400 设备上会整体偏移。新实现直接按真实像素中心点击。
        val elem = UiElementInfo(text = "搜索", bounds = RectBounds(100, 200, 300, 400)) // center (200, 300)
        val ctrl = SkillFakeController(elem)
        val skill = OpenAndSearchSkill()
        val res = skill.execute("""{"app_name": "test", "keyword": "hello"}""", ctrl, context)
        assertTrue(res.contains("success"))
        assertEquals(Pair(200f, 300f), ctrl.lastTapAtPixel)
    }

    @Test
    fun `scroll_and_find skill taps at real pixel center, not a hardcoded resolution`() = runTest {
        val elem = UiElementInfo(text = "目标商品ABC", bounds = RectBounds(50, 60, 150, 160)) // center (100, 110)
        val ctrl = SkillFakeController(elem)
        val skill = ScrollAndFindSkill()
        val res = skill.execute("""{"target_text": "目标商品ABC", "max_swipes": 3}""", ctrl, context)
        assertTrue(res.contains("found_and_tapped"))
        assertEquals(Pair(100f, 110f), ctrl.lastTapAtPixel)
    }

    @Test
    fun `take_screenshot pauses on sensitive screen without exposing image_base64`() = runTest {
        val sensitiveController = object : PhoneController by controller {
            override suspend fun getScreenState(): ScreenStateInfo = ScreenStateInfo(
                foregroundPackage = "com.eg.android.AlipayGphone",
                elements = listOf(UiElementInfo(text = "请输入支付密码")),
            )
        }
        val tool = TakeScreenshotTool(sensitiveController)
        val res = tool.execute("{}", context)
        assertTrue(res.contains("SAFETY PAUSE"))
        assertTrue(res.contains("\"is_safety_pause\":true") || res.contains("\"is_safety_pause\": true"))
        org.junit.Assert.assertFalse("Sensitive screenshot result must not leak image_base64", res.contains("fake_base64"))
    }

    @Test
    fun `open_deep_link rejects forbidden schemes like file, content, and intent`() = runTest {
        val tool = DeepLinkTool(controller)
        val fileRes = tool.execute("""{"uri": "file:///data/data/com.paw.agent/databases/secret.db"}""", context)
        assertTrue(fileRes.contains("forbidden") || fileRes.contains("Security violation"))
        
        val intentRes = tool.execute("""{"uri": "intent://com.example/#Intent;scheme=bad;end"}""", context)
        assertTrue(intentRes.contains("forbidden") || intentRes.contains("Security violation"))

        val contentRes = tool.execute("""{"uri": "content://contacts/people"}""", context)
        assertTrue(contentRes.contains("forbidden") || contentRes.contains("Security violation"))
    }

    @Test
    fun `click_element with explicit bounds intercepts payment action when screen contains payment texts`() = runTest {
        val sensitiveController = object : PhoneController by controller {
            override suspend fun getScreenState(): ScreenStateInfo = ScreenStateInfo(
                foregroundPackage = "com.shopping.app",
                elements = listOf(UiElementInfo(text = "立即支付 99.00 元", bounds = RectBounds(10, 20, 100, 200))),
            )
        }
        val tool = ClickElementTool(sensitiveController)
        val res = tool.execute("""{"bounds": [10, 20, 100, 200]}""", context)
        assertTrue(res.contains("requires_confirmation") || res.contains("安全确认拦截"))
    }

    @Test
    fun `input_text safely handles quotes and special characters producing valid json`() = runTest {
        val tool = InputTextTool(controller)
        val trickyText = """Hello "world" \n \t with 'quotes' and {braces}"""
        val res = tool.execute(kotlinx.serialization.json.buildJsonObject {
            put("text", kotlinx.serialization.json.JsonPrimitive(trickyText))
        }.toString(), context)
        assertTrue(res.contains("success"))
        assertEquals(trickyText, controller.lastInput)
        // Verify valid JSON parsing of output
        val parsed = kotlinx.serialization.json.Json.parseToJsonElement(res)
        assertEquals("success", parsed.jsonObject["status"]?.jsonPrimitive?.content)
    }
}

