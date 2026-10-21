package com.paw.agent.core.skill

import com.paw.agent.core.agent.AgentContext
import com.paw.agent.core.agent.RiskActionGuard
import com.paw.agent.core.agent.RiskDecision
import com.paw.agent.core.agent.RiskLevel
import com.paw.agent.core.tool.android.SafetyGuard
import com.paw.agent.device.PhoneController
import com.paw.agent.device.ScreenStateInfo
import com.paw.agent.device.UiElementInfo
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * 技能执行引擎。
 *
 * 三条安全原则贯穿始终，且都不是「注释里的承诺」而是代码里的硬约束：
 *
 * 1. **静态风险前置确认**：所有能在执行前看出来的风险（Root 命令、DeepLink、
 *    文字命中「支付/删除/发送」）在第一步之前就要求用户确认。确认后整条技能
 *    重放一次，不存在「执行到一半再确认、确认后又从头重跑」的重复副作用。
 * 2. **动态风险安全暂停**：运行期才发现的风险（当前屏幕是支付页、目标不在
 *    允许的应用列表）一律暂停，绝不在用户不知情时继续。暂停后由用户手动完成，
 *    技能不会自动续跑，因此也不可能重复执行。
 * 3. **能力开关不可绕过**：Root 命令即使在技能里声明了 `allowShell`，仍必须
 *    宿主传入 [SkillRunOptions.allowShellSteps]；确认对话框不会授予这个能力。
 */
class SkillEngine(
    private val controller: PhoneController,
    private val options: () -> SkillRunOptions = { SkillRunOptions() },
    /** 宿主注入的 Root 执行器；为 null 时尝试从 [controller] 取。 */
    private val shellRunner: (suspend (String) -> String?)? = null,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun run(
        def: SkillDefinition,
        argumentsJson: String = "{}",
        context: AgentContext = AgentContext(conversationId = "skill_run"),
    ): SkillRunResult {
        val errors = SkillValidator.errorsOf(def)
        if (errors.isNotEmpty()) {
            return SkillRunResult(
                status = SkillRunStatus.FAILED,
                skillKey = def.key,
                message = "技能定义存在错误，已拒绝执行：\n" +
                    errors.take(5).joinToString("\n") { "· ${it.message}" },
            )
        }

        val opts = options()
        val hasShellSteps = def.steps.any { it.enabled && it.kind == SkillActionKind.SHELL }
        if (hasShellSteps && !opts.allowShellSteps) {
            return SkillRunResult(
                status = SkillRunStatus.FAILED,
                skillKey = def.key,
                message = "该技能包含 Root 命令，但资深设置中未开启「允许技能执行 Root 命令」。已阻止执行。",
            )
        }

        val vars = buildVariables(def, argumentsJson)
        val preflight = preflightRisk(def)
        val needsConfirm = (def.guard.confirmBeforeRun || preflight != null) && !opts.bypassRiskConfirmation
        if (needsConfirm && !context.isGranted("risk_confirmed:${def.key}")) {
            return SkillRunResult(
                status = SkillRunStatus.REQUIRES_CONFIRMATION,
                skillKey = def.key,
                message = "该技能包含高风险动作，需要您确认后才能执行。",
                controlPayload = confirmationPayload(def, preflight, argumentsJson),
            )
        }

        val reports = mutableListOf<SkillStepReport>()
        val deadline = clock() + def.guard.maxDurationMs

        for ((index, step) in def.steps.withIndex()) {
            if (!step.enabled) {
                reports += report(step, index, SkillStepStatus.SKIPPED, "步骤已禁用")
                continue
            }
            if (context.isCancelled()) {
                return SkillRunResult(
                    SkillRunStatus.CANCELLED,
                    def.key,
                    "用户已中止技能执行",
                    reports,
                    vars,
                )
            }
            if (clock() > deadline) {
                return SkillRunResult(
                    SkillRunStatus.FAILED,
                    def.key,
                    "技能执行超过设定的总时长（${def.guard.maxDurationMs / 1000} 秒），已中断",
                    reports,
                    vars,
                )
            }
            if (step.condition.isNotBlank() && !SkillExpression.evaluate(step.condition, vars)) {
                reports += report(step, index, SkillStepStatus.SKIPPED, "条件不成立：${step.condition}")
                continue
            }

            val maxAttempts = if (step.onError == SkillErrorPolicy.RETRY) step.maxAttempts else 1
            var outcome = StepOutcome(false, "未执行")
            var used = 0
            while (used < maxAttempts) {
                used++
                outcome = execute(step, def, vars, opts, context)
                if (outcome.ok || outcome.pausedPayload != null) break
                if (used < maxAttempts) delay(400L * used)
            }

            if (outcome.pausedPayload != null) {
                reports += report(step, index, SkillStepStatus.PAUSED, outcome.message, used)
                return SkillRunResult(
                    status = SkillRunStatus.SAFETY_PAUSED,
                    skillKey = def.key,
                    message = outcome.message,
                    steps = reports,
                    variables = vars,
                    controlPayload = outcome.pausedPayload,
                )
            }

            if (!outcome.ok) {
                reports += report(step, index, SkillStepStatus.FAILED, outcome.message, used)
                if (step.onError == SkillErrorPolicy.CONTINUE) continue
                return SkillRunResult(
                    status = SkillRunStatus.FAILED,
                    skillKey = def.key,
                    message = "第 ${index + 1} 步（${step.label.ifBlank { SkillCatalog.spec(step.kind).label }}）执行失败：${outcome.message}",
                    steps = reports,
                    variables = vars,
                )
            }

            if (step.storeAs.isNotBlank()) {
                vars[step.storeAs] = outcome.value.orEmpty()
            }
            vars["last_result"] = outcome.value.orEmpty().ifBlank { outcome.message }
            reports += report(
                step,
                index,
                SkillStepStatus.SUCCESS,
                outcome.message.ifBlank { "OK" },
                used,
            )

            if (step.delayMs > 0) delay(step.delayMs)
        }

        return SkillRunResult(
            status = SkillRunStatus.SUCCESS,
            skillKey = def.key,
            message = "技能执行完成，共 ${reports.count { it.status == SkillStepStatus.SUCCESS }} 步",
            steps = reports,
            variables = vars,
        )
    }

    // ------------------------------------------------------------ 执行单步

    private class StepOutcome(
        val ok: Boolean,
        val message: String = "",
        val value: String? = null,
        val pausedPayload: String? = null,
    )

    private suspend fun execute(
        step: SkillStep,
        def: SkillDefinition,
        vars: MutableMap<String, String>,
        opts: SkillRunOptions,
        context: AgentContext,
    ): StepOutcome {
        val target = SkillInterpolator.render(step.target, vars)
        val interactive = isInteractive(step.kind)
        // 用户已为整条技能显式确认过：静态目标文本不再重复拦截，
        // 但实时屏幕上的新风险（例如页面跳到了支付页）仍然会暂停。
        val confirmed = context.isGranted("risk_confirmed:${def.key}")

        if (interactive) {
            val state = safeScreenState()
            if (state != null && !packageAllowed(def, state.foregroundPackage)) {
                return StepOutcome(
                    ok = false,
                    message = "当前前台应用 ${state.foregroundPackage} 不在允许列表 ${def.guard.allowedPackages} 中",
                )
            }
            val screenText = state?.elements
                ?.joinToString(" ") { it.text + " " + it.contentDescription }
                .orEmpty()
            vars["foreground_package"] = state?.foregroundPackage.orEmpty()
            if (screenText.isNotBlank()) vars["screen_text"] = screenText.take(4000)

            if (SafetyGuard.isSensitive(screenText) &&
                !opts.allowSensitiveScreen &&
                !def.guard.allowSensitiveScreen
            ) {
                return StepOutcome(
                    ok = false,
                    message = "检测到敏感密码/支付页面，技能已安全暂停",
                    pausedPayload = safetyPausePayload(
                        "检测到敏感密码/支付页面，技能「${def.displayName}」已安全暂停",
                    ),
                )
            }

            // 运行期风险不弹确认、直接暂停：用户手动处理完再决定是否重跑，
            // 避免「确认后整条技能重放」造成重复扣款 / 重复发送。
            //
            // 例外：用户已经为整条技能显式确认过（confirmed），此时不再做二次
            // 风险确认——否则「确认了却仍然跑不动」会让确认按钮形同虚设。
            // 注意敏感页检查（上方）不受确认影响，那是最底线的安全暂停。
            if (!confirmed) {
                val risk = runtimeRisk(
                    step = step,
                    screenText = screenText,
                    targetText = target.text,
                    includeTargetText = true,
                )
                if (risk != null) {
                    return StepOutcome(
                        ok = false,
                        message = risk.action,
                        pausedPayload = safetyPausePayload(
                            "运行中发现高风险动作「${risk.action}」（${risk.target}）：${risk.impact}。技能已暂停，请确认后再继续。",
                        ),
                    )
                }
            }
        }

        return when (step.kind) {
            SkillActionKind.LAUNCH_APP -> {
                if (target.text.isBlank()) return StepOutcome(false, "未填写应用名/包名")
                val launched = controller.launchApp(target.text)
                StepOutcome(launched, if (launched) "已启动 ${target.text}" else "启动 ${target.text} 失败")
            }

            SkillActionKind.TAP_ELEMENT, SkillActionKind.WAIT_FOR_TEXT ->
                waitAndTap(step, target.text, timeout = step.timeoutMs, maxAttempts = step.maxAttempts)

            SkillActionKind.TAP_POINT -> {
                val ok = controller.tap(step.x, step.y)
                StepOutcome(ok, if (ok) "已点击 (${step.x}, ${step.y})" else "点击失败")
            }

            SkillActionKind.INPUT_TEXT -> {
                if (target.text.isEmpty()) return StepOutcome(false, "没有要输入的内容")
                val ok = controller.inputText(target.text, clearBeforeInput = step.clearFirst)
                StepOutcome(
                    ok,
                    if (ok) {
                        "已输入 ${if (step.clearFirst) "（先清空）" else ""}${target.text.take(40)}"
                    } else {
                        "输入失败"
                    },
                )
            }

            SkillActionKind.SWIPE -> {
                val toX = step.toX ?: step.x
                val toY = step.toY ?: step.y
                val ok = controller.swipe(step.x, step.y, toX, toY, step.durationMs)
                StepOutcome(ok, if (ok) "已从 (${step.x},${step.y}) 滑到 ($toX,$toY)" else "滑动手势失败")
            }

            SkillActionKind.SCROLL -> {
                val points = when (target.text.trim().lowercase()) {
                    "up" -> listOf(500, 300, 500, 750)
                    "left" -> listOf(850, 500, 150, 500)
                    "right" -> listOf(150, 500, 850, 500)
                    else -> listOf(500, 750, 500, 300)
                }
                val ok = controller.swipe(points[0], points[1], points[2], points[3], step.durationMs)
                StepOutcome(ok, if (ok) "已执行 ${target.text} 滚动" else "滚动失败")
            }

            SkillActionKind.PRESS_KEY -> {
                val ok = when (target.text.trim().lowercase()) {
                    "home" -> controller.pressHome()
                    "recents" -> controller.pressRecents()
                    "enter" -> controller.pressEnter()
                    else -> controller.pressBack()
                }
                StepOutcome(ok, if (ok) "已按下 ${target.text}" else "按键失败")
            }

            SkillActionKind.WAIT -> {
                val ms = step.durationMs.coerceIn(0, 30_000)
                if (ms > 0) delay(ms)
                StepOutcome(true, "等待 $ms 毫秒")
            }

            SkillActionKind.SCROLL_UNTIL_FOUND -> scrollUntilFound(step, target.text)

            SkillActionKind.OPEN_DEEPLINK -> {
                if (target.text.isBlank()) return StepOutcome(false, "未填写链接")
                val decision = RiskActionGuard.evaluate("open_deeplink", """{"uri":"${target.text}"}""")
                if (decision.category == "DANGEROUS_SCHEME") {
                    return StepOutcome(false, "禁止使用高危协议：${target.text.take(40)}")
                }
                val ok = controller.openDeepLink(target.text)
                StepOutcome(ok, if (ok) "已打开链接" else "打开链接失败")
            }

            SkillActionKind.SCREEN_SNAPSHOT -> {
                val state = safeScreenState() ?: return StepOutcome(false, "无法读取当前界面")
                val text = state.elements.joinToString(" | ") { it.text }
                    .replace(Regex("\\s+"), " ")
                    .take(500)
                val value = buildJsonObject {
                    put("package", state.foregroundPackage)
                    put("activity", state.foregroundActivity)
                    put("text", text)
                }.toString()
                StepOutcome(true, "已记录界面快照", value)
            }

            SkillActionKind.SET_VARIABLE -> StepOutcome(true, "已设置变量 ${step.storeAs}", target.text)

            SkillActionKind.SHELL -> {
                if (target.text.isBlank()) return StepOutcome(false, "未填写命令")
                val output = runShell(target.text)
                    ?: return StepOutcome(false, "无法执行 Root 命令（未获得 ROOT 权限或宿主未启用执行器）")
                StepOutcome(true, "命令已执行", output.take(500))
            }
        }
    }

    /** 轮询等待目标文字出现（可选点击）。TAP_ELEMENT 与 WAIT_FOR_TEXT 共用。 */
    private suspend fun waitAndTap(
        step: SkillStep,
        targetText: String,
        timeout: Long,
        maxAttempts: Int,
    ): StepOutcome {
        val deadline = clock() + timeout
        var attempts = 0
        while (true) {
            attempts++
            val state = safeScreenState()
            val found = state?.let { findElement(it, targetText, step.exactMatch) }
            if (found != null) {
                return if (step.tapAfter) {
                    val tapped = controller.tapAtPixel(
                        found.bounds.centerX.toFloat(),
                        found.bounds.centerY.toFloat(),
                    )
                    StepOutcome(
                        tapped,
                        if (tapped) "已点击「${describe(found)}」" else "点击「${describe(found)}」失败",
                    )
                } else {
                    StepOutcome(true, "已找到「${describe(found)}」")
                }
            }
            if (clock() >= deadline || attempts >= maxAttempts) {
                return StepOutcome(false, "在界面中找不到「$targetText」")
            }
            delay(350)
        }
    }

    /** 边滚动边查找。用于长列表，避免反复截图推理。 */
    private suspend fun scrollUntilFound(step: SkillStep, targetText: String): StepOutcome {
        val deadline = clock() + step.timeoutMs
        var swipes = 0
        while (true) {
            val state = safeScreenState()
            val found = state?.let { findElement(it, targetText, step.exactMatch) }
            if (found != null) {
                return if (step.tapAfter) {
                    val tapped = controller.tapAtPixel(
                        found.bounds.centerX.toFloat(),
                        found.bounds.centerY.toFloat(),
                    )
                    StepOutcome(
                        tapped,
                        if (tapped) "滚动 $swipes 次后找到并点击「${describe(found)}」" else "找到但点击失败",
                    )
                } else {
                    StepOutcome(true, "滚动 $swipes 次后找到「${describe(found)}」")
                }
            }
            if (swipes >= step.maxAttempts || clock() >= deadline) {
                return StepOutcome(false, "滚动 $swipes 次后仍未找到「$targetText」")
            }
            controller.swipe(500, 750, 500, 300, step.durationMs)
            swipes++
            delay(500)
        }
    }

    // ------------------------------------------------------------- 风险判断

    /**
     * 执行前的静态风险扫描：只看技能定义里写死的内容，不读屏幕。
     * 返回最严重的一条决策，null 表示没看出来风险。
     */
    private fun preflightRisk(def: SkillDefinition): RiskDecision? {
        var worst: RiskDecision? = null

        fun consider(decision: RiskDecision?) {
            if (decision == null || !decision.requiresConfirmation) return
            val current = worst
            if (current == null || decision.level.ordinal > current.level.ordinal) {
                worst = decision
            }
        }

        def.steps.filter { it.enabled }.forEach { step ->
            when (step.kind) {
                SkillActionKind.SHELL ->
                    consider(RiskActionGuard.evaluate("shell_command", step.target))

                SkillActionKind.OPEN_DEEPLINK ->
                    consider(RiskActionGuard.evaluate("open_deeplink", """{"uri":"${step.target}"}"""))

                SkillActionKind.TAP_ELEMENT, SkillActionKind.WAIT_FOR_TEXT,
                SkillActionKind.SCROLL_UNTIL_FOUND,
                ->
                    consider(
                        RiskActionGuard.evaluate(
                            "click_element",
                            """{"text":"${escape(step.target)}"}""",
                            targetElementText = step.target,
                        ),
                    )

                SkillActionKind.INPUT_TEXT ->
                    consider(
                        RiskActionGuard.evaluate(
                            "input_text",
                            """{"text":"${escape(step.target)}"}""",
                            targetElementText = step.target,
                        ),
                    )

                else -> Unit
            }
        }
        return worst
    }

    private fun escape(raw: String): String = raw.replace("\"", "'").replace("\n", " ")

    private fun runtimeRisk(
        step: SkillStep,
        screenText: String,
        targetText: String,
        includeTargetText: Boolean,
    ): RiskDecision? {
        val target = if (includeTargetText) targetText else ""
        val decision = when (step.kind) {
            SkillActionKind.TAP_ELEMENT,
            SkillActionKind.WAIT_FOR_TEXT,
            SkillActionKind.SCROLL_UNTIL_FOUND ->
                RiskActionGuard.evaluate("click_element", target, screenText, target)

            SkillActionKind.TAP_POINT ->
                RiskActionGuard.evaluate("tap", "{}", screenText)

            SkillActionKind.INPUT_TEXT ->
                RiskActionGuard.evaluate("input_text", target, screenText, target)

            SkillActionKind.PRESS_KEY ->
                RiskActionGuard.evaluate(
                    "key_action",
                    """{"key":"${targetText.uppercase()}"}""",
                    screenText,
                )

            SkillActionKind.OPEN_DEEPLINK ->
                RiskActionGuard.evaluate("open_deeplink", """{"uri":"${escape(targetText)}"}""", screenText)

            else -> null
        }
        return decision?.takeIf { it.requiresConfirmation }
    }

    private fun isInteractive(kind: SkillActionKind): Boolean = kind in setOf(
        SkillActionKind.TAP_ELEMENT,
        SkillActionKind.TAP_POINT,
        SkillActionKind.INPUT_TEXT,
        SkillActionKind.SWIPE,
        SkillActionKind.SCROLL,
        SkillActionKind.WAIT_FOR_TEXT,
        SkillActionKind.SCROLL_UNTIL_FOUND,
        SkillActionKind.OPEN_DEEPLINK,
        SkillActionKind.PRESS_KEY,
    )

    // -------------------------------------------------------------- 基础工具

    private suspend fun safeScreenState(): ScreenStateInfo? =
        runCatching { controller.getScreenState() }.getOrNull()

    private fun packageAllowed(def: SkillDefinition, pkg: String): Boolean {
        val allowed = def.guard.allowedPackages
        if (allowed.isEmpty()) return true
        if (pkg.isBlank()) return true // 无障碍尚未就绪时不要误判
        return allowed.any { pkg == it || pkg.startsWith("$it.") }
    }

    private fun findElement(state: ScreenStateInfo, target: String, exact: Boolean): UiElementInfo? {
        val candidates = target.split('|').map { it.trim() }.filter { it.isNotBlank() }
        if (candidates.isEmpty()) return null

        candidates.forEach { candidate ->
            state.elements.firstOrNull {
                it.text.equals(candidate, ignoreCase = true) ||
                    it.contentDescription.equals(candidate, ignoreCase = true)
            }?.let { return it }
        }
        if (!exact) {
            candidates.forEach { candidate ->
                state.elements.firstOrNull {
                    it.text.contains(candidate, ignoreCase = true) ||
                        it.contentDescription.contains(candidate, ignoreCase = true)
                }?.let { return it }
            }
        }
        return null
    }

    private fun describe(element: UiElementInfo): String =
        element.text.ifBlank { element.contentDescription }.ifBlank { element.className }.take(24)

    private suspend fun runShell(command: String): String? {
        shellRunner?.let { return it(command) }
        val hybrid = controller as? com.paw.agent.device.HybridPhoneController ?: return null
        if (!hybrid.isRootAvailable) return null
        return runCatching { hybrid.rootController.executeCommand(command) }.getOrNull()
    }

    private fun buildVariables(def: SkillDefinition, argumentsJson: String): MutableMap<String, String> {
        val vars = LinkedHashMap<String, String>()
        vars.putAll(def.variables)
        vars.putAll(SkillCatalog.defaultsOf(def))
        runCatching {
            json.parseToJsonElement(argumentsJson).jsonObject.forEach { (key, value) ->
                val primitive = value as? JsonPrimitive
                vars[key] = primitive?.content ?: value.toString()
            }
        }
        vars["skill_key"] = def.key
        vars["now"] = clock().toString()
        return vars
    }

    private fun report(
        step: SkillStep,
        index: Int,
        status: SkillStepStatus,
        message: String,
        attempts: Int = 1,
    ): SkillStepReport = SkillStepReport(
        stepId = step.id.ifBlank { "step_${index + 1}" },
        kind = step.kind,
        label = step.label.ifBlank { SkillCatalog.spec(step.kind).label },
        status = status,
        message = message,
        attempts = attempts,
    )

    private fun confirmationPayload(
        def: SkillDefinition,
        decision: RiskDecision?,
        argumentsJson: String,
    ): String {
        val effective = decision ?: RiskDecision(
            isRisk = true,
            level = RiskLevel.HIGH,
            category = "SKILL_CONFIRM",
            action = "执行技能「${def.displayName}」",
            target = def.key,
            impact = "该技能包含高风险动作，执行后可能产生不可逆影响",
            requiresConfirmation = true,
        )
        return SafetyGuard.formatConfirmationPayload(
            risk = effective,
            toolName = def.key,
            arguments = argumentsJson,
        )
    }

    private fun safetyPausePayload(message: String): String = buildJsonObject {
        put("status", "paused")
        put("is_safety_pause", true)
        put("reason", message)
        put("message", "[SAFETY PAUSE] $message")
    }.toString()

    /** 供 UI 预览技能的整体等待预算（不含模型推理）。 */
    fun estimateDurationMs(def: SkillDefinition): Long =
        def.steps.filter { it.enabled }.sumOf { step ->
            step.durationMs + step.delayMs +
                if (step.kind == SkillActionKind.WAIT_FOR_TEXT ||
                    step.kind == SkillActionKind.SCROLL_UNTIL_FOUND
                ) {
                    step.timeoutMs
                } else {
                    0L
                }
        }

    /** 给界面「测试运行」面板用的完整轨迹 JSON。 */
    fun resultToJson(result: SkillRunResult): String = buildJsonObject {
        put("status", result.status.name)
        put("skill", result.skillKey)
        put("message", result.message)
        put(
            "steps",
            buildJsonArray {
                result.steps.forEach { step ->
                    add(
                        buildJsonObject {
                            put("step", step.stepId)
                            put("kind", step.kind.name)
                            put("label", step.label)
                            put("status", step.status.name)
                            put("message", step.message)
                            put("attempts", step.attempts)
                        },
                    )
                }
            },
        )
        if (result.variables.isNotEmpty()) {
            put(
                "variables",
                buildJsonObject { result.variables.forEach { (k, v) -> put(k, v.take(200)) } },
            )
        }
    }.toString()

    /** 模型侧看到的紧凑结果，避免把整条轨迹塞满上下文。 */
    fun modelFacingJson(result: SkillRunResult): String =
        result.controlPayload ?: buildJsonObject {
            put("status", if (result.isSuccess) "success" else "error")
            put("skill", result.skillKey)
            put("message", result.message)
            result.failedStep?.let { failed ->
                put("failed_step", failed.label)
                put("failed_kind", failed.kind.name)
            }
            put("executed_steps", result.executedSteps)
            result.variables.entries
                .filter { it.key !in SkillValidator.BUILTIN_VARIABLES }
                .take(8)
                .forEach { (k, v) -> put(k, v.take(120)) }
        }.toString()
}
