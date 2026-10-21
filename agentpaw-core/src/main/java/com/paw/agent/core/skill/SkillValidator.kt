package com.paw.agent.core.skill

import java.util.UUID

/**
 * 技能校验器。
 *
 * 校验不是「保存前的一次性检查」，而是编辑器、许愿生成、执行引擎共用的一段逻辑：
 * 同一份定义在不同入口得到同一套诊断，才不会出现「编辑器说能存、执行时才炸」。
 */
object SkillValidator {

    /** 技能 key 必须能安全地当成工具名发给模型。 */
    val KEY_PATTERN = Regex("^[a-z][a-z0-9_]{2,63}$")

    /** 这些名字属于内置工具，技能不能占用。 */
    val RESERVED_TOOL_NAMES = setOf(
        "tap", "double_tap", "long_press", "swipe", "input_text", "key_action",
        "launch_app", "open_deeplink", "take_screenshot", "get_screen_state",
        "click_element", "wait_seconds", "web_search", "run_script",
        "delegate_task", "shell_command",
    )

    /** 运行时自动注入的变量，不需要在参数里声明。 */
    val BUILTIN_VARIABLES = setOf(
        "foreground_package", "screen_text", "last_result", "now", "skill_key",
    )

    private const val MAX_STEPS = 60
    private const val MAX_DESCRIPTION = 400
    private const val MAX_GUARD_MS = 600_000L

    fun validate(def: SkillDefinition): List<SkillIssue> {
        val issues = mutableListOf<SkillIssue>()

        fun error(code: String, message: String, stepId: String? = null, field: String? = null) {
            issues += SkillIssue(SkillIssueSeverity.ERROR, code, message, stepId, field)
        }

        fun warn(code: String, message: String, stepId: String? = null, field: String? = null) {
            issues += SkillIssue(SkillIssueSeverity.WARNING, code, message, stepId, field)
        }

        fun info(code: String, message: String, stepId: String? = null, field: String? = null) {
            issues += SkillIssue(SkillIssueSeverity.INFO, code, message, stepId, field)
        }

        // ---- 元信息 ----
        if (def.displayName.isBlank()) {
            error("name_blank", "技能名称不能为空")
        } else if (def.displayName.length > 40) {
            warn("name_too_long", "技能名称建议不超过 40 个字符，界面上会被截断")
        }

        when {
            def.key.isBlank() -> error("key_blank", "技能标识（key）不能为空")
            !KEY_PATTERN.matches(def.key) ->
                error("key_invalid", "技能标识只能包含小写字母、数字和下划线，且必须以字母开头（3~64 位）")
            def.key in RESERVED_TOOL_NAMES ->
                error("key_reserved", "「${def.key}」是内置工具名，请换一个技能标识")
            !def.key.startsWith("skill_") ->
                info("key_prefix", "建议以 skill_ 开头，便于在工具列表中与内置工具区分")
        }

        when {
            def.description.isBlank() -> error("desc_blank", "技能描述不能为空：模型靠它判断何时调用该技能")
            def.description.trim().length < 8 ->
                error("desc_short", "技能描述太短，请写清「什么时候用、做什么」")
            def.description.length > MAX_DESCRIPTION ->
                warn("desc_long", "技能描述超过 $MAX_DESCRIPTION 字符，会挤占模型上下文")
        }

        // ---- 参数 ----
        val paramKeys = mutableSetOf<String>()
        def.parameters.forEachIndexed { index, param ->
            val label = param.key.ifBlank { "第 ${index + 1} 个参数" }
            if (!param.isKeyValid) {
                error("param_key_invalid", "参数名「$label」不合法：只能包含字母、数字、下划线，且不能以数字开头")
            }
            if (param.key.isNotBlank() && !paramKeys.add(param.key)) {
                error("param_dup", "参数名「${param.key}」重复")
            }
            if (param.key in BUILTIN_VARIABLES) {
                error("param_builtin", "参数名「${param.key}」与内置变量冲突")
            }
            if (param.type == SkillParamType.CHOICE && param.choices.isEmpty()) {
                error("param_choice_empty", "参数「$label」是选择类型，但没有任何可选项")
            }
            if (param.type != SkillParamType.CHOICE && param.choices.isNotEmpty()) {
                warn("param_choice_unused", "参数「$label」不是选择类型，候选值不会生效")
            }
            if (param.key.isNotBlank()) {
                val used = def.steps.any { step ->
                    SkillInterpolator.referencedVariables(step.target).contains(param.key) ||
                        SkillInterpolator.referencedVariables(step.condition).contains(param.key)
                }
                if (!used) {
                    info("param_unused", "参数「${param.key}」没有被任何步骤引用")
                }
            }
        }

        // ---- 步骤 ----
        if (def.steps.isEmpty()) {
            error("steps_empty", "技能至少要有一个步骤")
        }
        if (def.steps.size > MAX_STEPS) {
            error("steps_too_many", "单个技能最多 $MAX_STEPS 个步骤，请拆分")
        }

        val stepIds = mutableSetOf<String>()
        var estimatedMs = 0L

        def.steps.forEachIndexed { index, step ->
            val spec = SkillCatalog.spec(step.kind)
            val stepLabel = step.label.ifBlank { "第 ${index + 1} 步（${spec.label}）" }
            val stepId = step.id

            if (step.id.isBlank()) {
                warn("step_id_blank", "「$stepLabel」缺少步骤 id，保存时会自动生成", stepId)
            } else if (!stepIds.add(step.id)) {
                error("step_id_dup", "步骤 id「${step.id}」重复", stepId)
            }

            if (spec.needsTarget && step.target.isBlank()) {
                error("step_target_blank", "「$stepLabel」需要填写${spec.targetHint}", stepId, "target")
            }
            if (spec.needsPoint) {
                if (step.x !in 0..1000 || step.y !in 0..1000) {
                    error("step_point_range", "「$stepLabel」的坐标必须是 0~1000 的归一化值", stepId, "x/y")
                }
            }
            if (spec.needsDestination) {
                val tx = step.toX
                val ty = step.toY
                if (tx == null || ty == null) {
                    error("step_dest_blank", "「$stepLabel」需要填写终点坐标", stepId, "toX/toY")
                } else if (tx !in 0..1000 || ty !in 0..1000) {
                    error("step_dest_range", "「$stepLabel」的终点坐标必须是 0~1000", stepId, "toX/toY")
                }
            }

            val maxDuration = if (step.kind == SkillActionKind.WAIT) 30_000L else 10_000L
            if (step.durationMs < 0 || step.durationMs > maxDuration) {
                error(
                    "step_duration_range",
                    "「$stepLabel」的时长必须在 0~$maxDuration 毫秒之间",
                    stepId,
                    "durationMs",
                )
            }
            if (step.delayMs !in 0..10_000) {
                error("step_delay_range", "「$stepLabel」的稳定等待必须在 0~10000 毫秒之间", stepId, "delayMs")
            }
            if (step.timeoutMs !in 200..60_000) {
                error("step_timeout_range", "「$stepLabel」的超时必须在 200~60000 毫秒之间", stepId, "timeoutMs")
            }
            if (step.maxAttempts !in 1..20) {
                error("step_attempts_range", "「$stepLabel」的尝试次数必须在 1~20 之间", stepId, "maxAttempts")
            }

            if (step.condition.isNotBlank()) {
                SkillExpression.validate(step.condition)?.let { message ->
                    error("step_condition_invalid", "「$stepLabel」的条件表达式有误：$message", stepId, "condition")
                }
            }

            (SkillInterpolator.referencedVariables(step.target) +
                SkillInterpolator.referencedVariables(step.condition))
                .distinct()
                .forEach { name ->
                    if (name !in def.variableKeys && name !in BUILTIN_VARIABLES) {
                        error(
                            "var_unknown",
                            "「$stepLabel」引用了不存在的变量 {{$name}}，请先添加同名参数",
                            stepId,
                        )
                    }
                }

            if (step.kind == SkillActionKind.SHELL) {
                if (!def.guard.allowShell) {
                    error(
                        "shell_not_allowed",
                        "「$stepLabel」包含 Root 命令，但技能守卫未开启「允许 Root 命令」(guard.allowShell)",
                        stepId,
                        "guard.allowShell",
                    )
                }
                warn(
                    "shell_confirm",
                    "「$stepLabel」将以 Root 身份执行命令；执行前会强制要求用户确认，并在资深设置里受总开关约束",
                    stepId,
                )
            }

            when (step.kind) {
                SkillActionKind.PRESS_KEY -> {
                    val key = step.target.trim().lowercase()
                    if (key.isNotBlank() && key !in setOf("back", "home", "recents", "enter")) {
                        error("step_press_key", "「$stepLabel」的按键只能是 back/home/recents/enter", stepId, "target")
                    }
                }

                SkillActionKind.SCROLL -> {
                    val dir = step.target.trim().lowercase()
                    if (dir.isNotBlank() && dir !in setOf("up", "down", "left", "right")) {
                        error("step_scroll_dir", "「$stepLabel」的滚动方向只能是 up/down/left/right", stepId, "target")
                    }
                }

                SkillActionKind.SET_VARIABLE, SkillActionKind.SCREEN_SNAPSHOT -> {
                    if (step.storeAs.isBlank()) {
                        error("step_store_blank", "「$stepLabel」需要填写要写入的变量名", stepId, "storeAs")
                    } else if (!SkillParameter.KEY_PATTERN.matches(step.storeAs)) {
                        error("step_store_invalid", "「$stepLabel」的变量名不合法", stepId, "storeAs")
                    }
                }

                else -> Unit
            }

            if (step.onError == SkillErrorPolicy.RETRY && step.maxAttempts < 2) {
                warn("step_retry_once", "「$stepLabel」选择了失败重试，但尝试次数是 1，等于不重试", stepId)
            }

            if (step.enabled) {
                estimatedMs += step.durationMs + step.delayMs + step.timeoutMs.coerceAtMost(step.timeoutMs)
            }
        }

        if (def.steps.none { it.enabled }) {
            warn("steps_all_disabled", "所有步骤都被禁用了，这个技能执行时不会做任何事")
        }

        // ---- 守卫 ----
        if (def.guard.maxDurationMs !in 1_000..MAX_GUARD_MS) {
            error("guard_duration_range", "技能级超时必须在 1000~$MAX_GUARD_MS 毫秒之间", field = "guard.maxDurationMs")
        }
        if (estimatedMs > def.guard.maxDurationMs) {
            warn(
                "guard_duration_tight",
                "步骤的累计等待（约 ${estimatedMs / 1000} 秒）已超过技能级超时（${def.guard.maxDurationMs / 1000} 秒），可能被提前中断",
                field = "guard.maxDurationMs",
            )
        }
        def.guard.allowedPackages.forEach { pkg ->
            if (pkg.isBlank() || pkg.any { it.isWhitespace() }) {
                error("guard_package_invalid", "允许运行的应用包名「$pkg」不合法", field = "guard.allowedPackages")
            }
        }
        val risk = SkillCatalog.riskOf(def)
        if (risk >= SkillRisk.HIGH && !def.guard.confirmBeforeRun) {
            warn(
                "guard_confirm_suggested",
                "该技能包含高风险动作（$risk），建议开启「执行前需要用户确认」",
                field = "guard.confirmBeforeRun",
            )
        }
        if (def.steps.any { SkillCatalog.requiresExplicitConfirmation(it.kind) } && !def.guard.confirmBeforeRun) {
            info(
                "guard_confirm_implicit",
                "包含 shell / DeepLink 步骤的技能，执行前会强制确认一次（无需手动勾选）",
                field = "guard.confirmBeforeRun",
            )
        }

        return issues
    }

    fun errorsOf(def: SkillDefinition): List<SkillIssue> = validate(def).filter { it.isError }

    fun isValid(def: SkillDefinition): Boolean = errorsOf(def).isEmpty()

    /**
     * 补齐可推导字段，让「刚新建还没填完」的技能也能安全保存与往返编辑：
     * 生成 id、由名称推导 key、补齐步骤 id、刷新时间戳。
     */
    fun normalize(
        def: SkillDefinition,
        clock: () -> Long = { System.currentTimeMillis() },
    ): SkillDefinition {
        val now = clock()
        val id = def.id.ifBlank { UUID.randomUUID().toString() }
        val key = def.key.trim().ifBlank { slugify(def.displayName) }
        val usedIds = mutableSetOf<String>()
        val steps = def.steps.mapIndexed { index, step ->
            val candidate = step.id.trim().ifBlank { "step_${index + 1}" }
            var unique = candidate
            var suffix = 2
            while (!usedIds.add(unique)) {
                unique = "${candidate}_$suffix"
                suffix++
            }
            step.copy(id = unique)
        }
        return def.copy(
            id = id,
            key = key,
            steps = steps,
            createdAt = if (def.createdAt == 0L) now else def.createdAt,
            updatedAt = if (def.updatedAt == 0L) now else def.updatedAt,
            schemaVersion = SKILL_SCHEMA_VERSION,
        )
    }

    /** 由中文/英文名称推导一个合法的技能 key。 */
    fun slugify(name: String): String {
        val ascii = name.lowercase()
            .map { if (it in 'a'..'z' || it in '0'..'9') it else '_' }
            .joinToString("")
            .replace(Regex("_+"), "_")
            .trim('_')
        val base = "skill_" + ascii.take(40).ifBlank {
            "u" + (name.hashCode().toLong() and 0xFFFFFFFFL).toString(36)
        }
        return if (base.length >= 3 && KEY_PATTERN.matches(base)) base else "skill_custom"
    }
}
