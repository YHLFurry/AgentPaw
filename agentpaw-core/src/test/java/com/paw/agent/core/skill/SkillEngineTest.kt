package com.paw.agent.core.skill

import com.paw.agent.core.agent.AgentContext
import com.paw.agent.core.tool.ToolControlSignal
import com.paw.agent.device.PhoneController
import com.paw.agent.device.RectBounds
import com.paw.agent.device.ScreenStateInfo
import com.paw.agent.device.ScreenshotResult
import com.paw.agent.device.UiElementInfo
import com.paw.agent.device.VisionResolutionMode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 执行引擎的行为验证。
 *
 * 重点不是「能跑通」，而是几条安全边界真的成立：
 * 静态风险必须执行前确认、动态风险必须暂停、能力开关不可绕过、失败策略可预期。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SkillEngineTest {
    @Test fun `confirmation does not authorize a root command changed by a preceding step`() = runTest {
        var executed = false
        val def = skill(guard = SkillGuard(allowShell = true), steps = listOf(
            SkillStep(kind = SkillActionKind.SET_VARIABLE, target = "whoami", storeAs = "command", delayMs = 0),
            SkillStep(kind = SkillActionKind.SHELL, target = "{{command}}", delayMs = 0),
        )).copy(variables = mapOf("command" to "id"))
        val engine = SkillEngine(FakeController(), options = { SkillRunOptions(allowShellSteps = true) }, shellRunner = { executed = true; "done" })
        val result = engine.run(def, context = AgentContext("t", grantedTokens = setOf("risk_confirmed:skill_test")))
        assertEquals(SkillRunStatus.SAFETY_PAUSED, result.status)
        org.junit.Assert.assertFalse(executed)
    }

    private class FakeController(
        var elements: List<UiElementInfo> = listOf(
            UiElementInfo(text = "搜索", bounds = RectBounds(0, 0, 100, 100)),
        ),
        var foreground: String = "com.test.app",
        var launchResult: Boolean = true,
    ) : PhoneController {
        override val isAccessibilityEnabled: Boolean = true
        override val isShizukuAvailable: Boolean = false

        val launched = mutableListOf<String>()
        val inputs = mutableListOf<String>()
        var tapped: Pair<Float, Float>? = null
        var tappedNormalized: Pair<Int, Int>? = null
        var lastKey: String? = null
        var swipes = 0
        var screenReads = 0
        var onRead: (() -> Unit)? = null

        override suspend fun tap(xNormalized: Int, yNormalized: Int, cropRoi: List<Int>?): Boolean {
            tappedNormalized = xNormalized to yNormalized
            return true
        }

        override suspend fun doubleTap(xNormalized: Int, yNormalized: Int, cropRoi: List<Int>?): Boolean = true
        override suspend fun longPress(
            xNormalized: Int,
            yNormalized: Int,
            durationMs: Long,
            cropRoi: List<Int>?,
        ): Boolean = true

        override suspend fun tapAtPixel(centerX: Float, centerY: Float): Boolean {
            tapped = centerX to centerY
            return true
        }

        override suspend fun swipe(
            startXNormalized: Int,
            startYNormalized: Int,
            endXNormalized: Int,
            endYNormalized: Int,
            durationMs: Long,
        ): Boolean {
            swipes++
            return true
        }

        override suspend fun inputText(text: String, clearBeforeInput: Boolean): Boolean {
            inputs += text
            return true
        }

        override suspend fun pressBack(): Boolean {
            lastKey = "BACK"; return true
        }

        override suspend fun pressHome(): Boolean {
            lastKey = "HOME"; return true
        }

        override suspend fun pressRecents(): Boolean {
            lastKey = "RECENTS"; return true
        }

        override suspend fun pressEnter(): Boolean {
            lastKey = "ENTER"; return true
        }

        override suspend fun launchApp(packageNameOrName: String): Boolean {
            launched += packageNameOrName
            return launchResult
        }

        override suspend fun openDeepLink(uri: String): Boolean = true

        override suspend fun takeScreenshot(
            mode: VisionResolutionMode,
            cropRoi: List<Int>?,
        ): ScreenshotResult = ScreenshotResult("x", 1, 1, false)

        override suspend fun getScreenState(): ScreenStateInfo {
            screenReads++
            onRead?.invoke()
            return ScreenStateInfo(
            foregroundPackage = foreground,
            foregroundActivity = "$foreground.Main",
            elements = elements,
        )
        }
    }

    private fun skill(
        key: String = "skill_test",
        description: String = "用于单元测试的技能定义，覆盖执行引擎的各条分支",
        guard: SkillGuard = SkillGuard(),
        parameters: List<SkillParameter> = emptyList(),
        steps: List<SkillStep> = listOf(
            SkillStep(id = "step_1", kind = SkillActionKind.TAP_ELEMENT, target = "搜索"),
        ),
    ): SkillDefinition = SkillDefinition(
        key = key,
        displayName = "测试技能",
        description = description,
        parameters = parameters,
        steps = steps,
        guard = guard,
    )

    @Test
    fun `polling rechecks foreground package before tapping`() = runTest {
        val controller = FakeController()
        controller.onRead = {
            if (controller.screenReads >= 2) controller.foreground = "com.test.app.impostor"
        }
        val result = SkillEngine(controller).run(skill(guard = SkillGuard(allowedPackages = listOf("com.test.app"))))
        assertEquals(SkillRunStatus.SAFETY_PAUSED, result.status)
        assertEquals(null, controller.tapped)
    }

    @Test
    fun `polling rechecks sensitive page even after confirmation`() = runTest {
        val controller = FakeController()
        controller.onRead = {
            if (controller.screenReads >= 2) controller.elements = listOf(UiElementInfo(text = "搜索 Enter your password"))
        }
        val result = SkillEngine(controller).run(
            skill(), context = AgentContext("t", grantedTokens = setOf("risk_confirmed:skill_test")),
        )
        assertEquals(SkillRunStatus.SAFETY_PAUSED, result.status)
        assertEquals(null, controller.tapped)
    }

    @Test
    fun `total budget interrupts a single long wait`() = runTest {
        val def = skill(
            guard = SkillGuard(maxDurationMs = 1000),
            steps = listOf(SkillStep(kind = SkillActionKind.WAIT, durationMs = 30_000, delayMs = 0)),
        )
        val started = testScheduler.currentTime
        val result = SkillEngine(FakeController()).run(def)
        assertEquals(SkillRunStatus.FAILED, result.status)
        assertTrue(testScheduler.currentTime - started <= 1000)
    }

    @Test
    fun `runs steps in order and records a full trail`() = runTest {
        val controller = FakeController()
        val engine = SkillEngine(controller)
        val def = skill(
            steps = listOf(
                SkillStep(id = "s1", kind = SkillActionKind.LAUNCH_APP, target = "微信", delayMs = 0),
                SkillStep(
                    id = "s2",
                    kind = SkillActionKind.TAP_ELEMENT,
                    target = "搜索",
                    delayMs = 0,
                ),
                SkillStep(id = "s3", kind = SkillActionKind.INPUT_TEXT, target = "hello", delayMs = 0),
                SkillStep(id = "s4", kind = SkillActionKind.PRESS_KEY, target = "back", delayMs = 0),
            ),
        )

        val result = engine.run(def, "{}", AgentContext("t"))

        assertTrue(result.message, result.isSuccess)
        assertEquals(listOf("微信"), controller.launched)
        assertEquals(Pair(50f, 50f), controller.tapped)
        assertEquals(listOf("hello"), controller.inputs)
        assertEquals("BACK", controller.lastKey)
        assertEquals(4, result.executedSteps)
        assertEquals(4, result.steps.size)
    }

    @Test
    fun `condition false skips the step without failing`() = runTest {
        val controller = FakeController()
        val engine = SkillEngine(controller)
        val def = skill(
            parameters = listOf(SkillParameter(key = "mode")),
            steps = listOf(
                SkillStep(
                    id = "s1",
                    kind = SkillActionKind.TAP_ELEMENT,
                    target = "搜索",
                    condition = "{{mode}} == \"fast\"",
                    delayMs = 0,
                ),
            ),
        )

        val result = engine.run(def, """{"mode":"slow"}""", AgentContext("t"))

        assertTrue(result.isSuccess)
        assertEquals(SkillStepStatus.SKIPPED, result.steps.single().status)
        assertEquals(null, controller.tapped)
    }

    @Test
    fun `retry policy retries before failing`() = runTest {
        val controller = FakeController(elements = listOf(UiElementInfo(text = "别的东西")))
        val engine = SkillEngine(controller)
        val def = skill(
            steps = listOf(
                SkillStep(
                    id = "s1",
                    kind = SkillActionKind.TAP_ELEMENT,
                    target = "不存在的按钮",
                    onError = SkillErrorPolicy.RETRY,
                    maxAttempts = 2,
                    timeoutMs = 400,
                    delayMs = 0,
                ),
            ),
        )

        val result = engine.run(def, "{}", AgentContext("t"))

        assertEquals(SkillRunStatus.FAILED, result.status)
        assertEquals(2, result.steps.single().attempts)
    }

    @Test
    fun `continue policy keeps going after a failed step`() = runTest {
        val controller = FakeController(elements = listOf(UiElementInfo(text = "继续内容")))
        val engine = SkillEngine(controller)
        val def = skill(
            steps = listOf(
                SkillStep(
                    id = "s1",
                    kind = SkillActionKind.TAP_ELEMENT,
                    target = "不存在",
                    onError = SkillErrorPolicy.CONTINUE,
                    maxAttempts = 1,
                    delayMs = 0,
                ),
                SkillStep(id = "s2", kind = SkillActionKind.PRESS_KEY, target = "home", delayMs = 0),
            ),
        )

        val result = engine.run(def, "{}", AgentContext("t"))

        assertTrue(result.isSuccess)
        assertEquals(SkillStepStatus.FAILED, result.steps[0].status)
        assertEquals(SkillStepStatus.SUCCESS, result.steps[1].status)
        assertEquals("HOME", controller.lastKey)
    }

    @Test
    fun `variable default value is used when argument is absent`() = runTest {
        val controller = FakeController()
        val engine = SkillEngine(controller)
        val def = skill(
            parameters = listOf(SkillParameter(key = "keyword")),
            steps = listOf(
                SkillStep(
                    id = "s1",
                    kind = SkillActionKind.INPUT_TEXT,
                    target = "{{keyword|默认词}}",
                    delayMs = 0,
                ),
            ),
        )

        engine.run(def, "{}", AgentContext("t"))

        assertEquals(listOf("默认词"), controller.inputs)
    }

    @Test
    fun `statically risky target requires confirmation before any step runs`() = runTest {
        val controller = FakeController()
        val engine = SkillEngine(controller)
        val def = skill(
            steps = listOf(
                SkillStep(id = "s1", kind = SkillActionKind.TAP_ELEMENT, target = "确认支付", delayMs = 0),
            ),
        )

        val result = engine.run(def, "{}", AgentContext("t"))

        assertEquals(SkillRunStatus.REQUIRES_CONFIRMATION, result.status)
        assertNotNull(result.controlPayload)
        // 确认payload必须能被 Runner 的控制信号解析器识别
        val signal = ToolControlSignal.parse(result.controlPayload!!, def.key, "{}")
        assertTrue(signal is ToolControlSignal.RequiresConfirmation)
        // 关键：确认之前不能产生任何副作用
        assertEquals(null, controller.tapped)
        assertEquals(0, result.steps.size)
    }

    @Test
    fun `confirmation token lets a risky skill run`() = runTest {
        // 用「转账」而不是「确认支付」：前者命中 RiskActionGuard，
        // 后者还会命中 SafetyGuard 的敏感页关键词，语义就变成安全暂停了。
        val controller = FakeController(
            elements = listOf(UiElementInfo(text = "转账", bounds = RectBounds(0, 0, 100, 100))),
        )
        val engine = SkillEngine(controller)
        val def = skill(
            key = "skill_confirm_ok",
            steps = listOf(
                SkillStep(id = "s1", kind = SkillActionKind.TAP_ELEMENT, target = "转账", delayMs = 0),
            ),
        )

        val result = engine.run(
            def,
            "{}",
            AgentContext("t", grantedTokens = setOf("risk_confirmed:skill_confirm_ok")),
        )

        assertTrue(result.message, result.isSuccess)
        assertEquals(Pair(50f, 50f), controller.tapped)
    }

    @Test
    fun `sensitive screen pauses instead of acting`() = runTest {
        val controller = FakeController(
            elements = listOf(UiElementInfo(text = "请输入支付密码", isEditable = true)),
        )
        val engine = SkillEngine(controller)
        val def = skill(
            steps = listOf(SkillStep(id = "s1", kind = SkillActionKind.INPUT_TEXT, target = "123456")),
        )

        val result = engine.run(def, "{}", AgentContext("t"))

        assertEquals(SkillRunStatus.SAFETY_PAUSED, result.status)
        val signal = ToolControlSignal.parse(result.controlPayload!!)
        assertTrue(signal is ToolControlSignal.SafetyPause)
        assertTrue(controller.inputs.isEmpty())
    }

    @Test
    fun `allowed packages guard blocks the wrong foreground app`() = runTest {
        val controller = FakeController(foreground = "com.evil.app")
        val engine = SkillEngine(controller)
        val def = skill(
            guard = SkillGuard(allowedPackages = listOf("com.tencent.mm")),
            steps = listOf(SkillStep(id = "s1", kind = SkillActionKind.TAP_ELEMENT, target = "搜索")),
        )

        val result = engine.run(def, "{}", AgentContext("t"))

        assertEquals(SkillRunStatus.FAILED, result.status)
        assertTrue(result.message.contains("不在允许列表"))
        assertEquals(null, controller.tapped)
    }

    @Test
    fun `shell step is blocked unless the host enables it`() = runTest {
        var executed = false
        val engine = SkillEngine(
            controller = FakeController(),
            options = { SkillRunOptions(allowShellSteps = false) },
            shellRunner = { executed = true; "uid=0" },
        )
        val def = skill(
            key = "skill_shell",
            guard = SkillGuard(allowShell = true),
            steps = listOf(SkillStep(id = "s1", kind = SkillActionKind.SHELL, target = "id")),
        )

        val result = engine.run(
            def,
            "{}",
            AgentContext("t", grantedTokens = setOf("risk_confirmed:skill_shell")),
        )

        assertEquals(SkillRunStatus.FAILED, result.status)
        assertTrue(result.message.contains("允许技能执行 Root 命令"))
        assertEquals(false, executed)
    }

    @Test
    fun `shell step runs when enabled and confirmed`() = runTest {
        var executedCommand: String? = null
        val engine = SkillEngine(
            controller = FakeController(),
            options = { SkillRunOptions(allowShellSteps = true) },
            shellRunner = { cmd -> executedCommand = cmd; "uid=0(root)" },
        )
        val def = skill(
            key = "skill_shell_ok",
            guard = SkillGuard(allowShell = true),
            steps = listOf(
                SkillStep(id = "s1", kind = SkillActionKind.SHELL, target = "id", storeAs = "out", delayMs = 0),
            ),
        )

        val result = engine.run(
            def,
            "{}",
            AgentContext("t", grantedTokens = setOf("risk_confirmed:skill_shell_ok")),
        )

        assertTrue(result.message, result.isSuccess)
        assertEquals("id", executedCommand)
        assertEquals("uid=0(root)", result.variables["out"])
    }

    @Test
    fun `invalid skill is rejected before touching the device`() = runTest {
        val controller = FakeController()
        val engine = SkillEngine(controller)
        val def = skill(description = "", steps = emptyList())

        val result = engine.run(def, "{}", AgentContext("t"))

        assertEquals(SkillRunStatus.FAILED, result.status)
        assertTrue(result.message.contains("已拒绝执行"))
        assertEquals(null, controller.tapped)
    }

    @Test
    fun `total duration budget stops a runaway skill`() = runTest {
        val controller = FakeController()
        // 注入虚拟时钟：runTest 里的 delay 不推进 System.currentTimeMillis，
        // 用调度器的虚拟时间才能验证「总时长预算」这条逻辑。
        val engine = SkillEngine(controller, clock = { testScheduler.currentTime })
        val def = skill(
            guard = SkillGuard(maxDurationMs = 1_000),
            steps = (1..4).map { index ->
                SkillStep(
                    id = "s$index",
                    kind = SkillActionKind.WAIT,
                    durationMs = 400,
                    delayMs = 0,
                )
            },
        )

        val result = engine.run(def, "{}", AgentContext("t"))

        assertEquals(SkillRunStatus.FAILED, result.status)
        assertTrue(result.message.contains("超过设定的总时长"))
    }

    @Test
    fun `cancelled context stops between steps`() = runTest {
        val controller = FakeController()
        val engine = SkillEngine(controller)
        val def = skill(
            steps = listOf(
                SkillStep(id = "s1", kind = SkillActionKind.PRESS_KEY, target = "home", delayMs = 0),
                SkillStep(id = "s2", kind = SkillActionKind.PRESS_KEY, target = "back", delayMs = 0),
            ),
        )

        val result = engine.run(def, "{}", AgentContext("t", cancelledCheck = { true }))

        assertEquals(SkillRunStatus.CANCELLED, result.status)
        assertEquals(null, controller.lastKey)
    }
}
