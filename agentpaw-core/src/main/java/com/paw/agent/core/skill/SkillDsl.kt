package com.paw.agent.core.skill

/**
 * 「速记」格式：一行一个动作的自然语言技能稿。
 *
 * 这是给不想写 JSON 的人准备的入口，也是许愿生成结果的人工可读视图。
 * 完整格式：
 * ```
 * 名称: 微信搜索好友
 * 标识: skill_wechat_search
 * 说明: 启动微信并搜索指定好友，适用于需要快速定位联系人的场景
 * 分类: 社交
 * 参数: keyword | 文本 | 必填 | 搜索关键词 | 小明
 * 变量: greet = 你好
 * 守卫: 执行前确认 = 是
 * 守卫: 允许应用 = com.tencent.mm
 * 守卫: 总超时 = 120
 * ---
 * 打开 微信
 * 点 搜索|Search
 * 输入 {{keyword}}
 * 回车
 * ```
 *
 * 解析失败不吞行：每一条无法识别的内容都会作为带行号的错误返回，
 * 编辑器可以据此把光标定位到出错的那一行。
 */
object SkillDsl {

    private const val SEPARATOR = "---"

    data class Result(
        val definition: SkillDefinition?,
        val issues: List<SkillIssue>,
        /** 解析成功的动作行数，用于给用户反馈。 */
        val stepCount: Int = 0,
    ) {
        val isSuccess: Boolean get() = definition != null && issues.none { it.isError }
    }

    // ---------------------------------------------------------------- 解析

    fun parse(text: String, base: SkillDefinition = SkillDefinition()): Result {
        val issues = mutableListOf<SkillIssue>()
        var displayName = ""
        var key = ""
        var description = ""
        var category = ""
        var emoji = ""
        val parameters = mutableListOf<SkillParameter>()
        val variables = LinkedHashMap<String, String>()
        var confirmBeforeRun = false
        var allowedPackages = emptyList<String>()
        var maxDurationMs = 180_000L
        var allowShell = false
        var inBody = false

        val steps = mutableListOf<SkillStep>()

        text.lines().forEachIndexed { index, rawLine ->
            val lineNo = index + 1
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) return@forEachIndexed

            if (line == SEPARATOR) {
                inBody = true
                return@forEachIndexed
            }

            if (!inBody) {
                // 中英文冒号都接受：只在第一个分隔符处切分，值里的 http:// 不受影响。
                val colonIndex = listOf(line.indexOf(':'), line.indexOf('：')).filter { it >= 0 }.minOrNull()
                val field = if (colonIndex == null) line.trim() else line.substring(0, colonIndex).trim()
                val value = if (colonIndex == null) "" else line.substring(colonIndex + 1).trim()
                when (field) {
                    "名称", "name" -> displayName = value
                    "标识", "key" -> key = value
                    "说明", "描述", "description", "desc" -> description = value
                    "分类", "category" -> category = value
                    "表情", "emoji" -> emoji = value
                    "参数", "param" -> {
                        val parsed = parseParameter(value)
                        if (parsed == null) {
                            issues += SkillIssue(
                                SkillIssueSeverity.ERROR,
                                "dsl_param_invalid",
                                "参数定义格式应为：键 | 类型 | 必填/可选 | 说明 | 示例 | 候选值",
                                line = lineNo,
                            )
                        } else {
                            parameters += parsed
                        }
                    }

                    "变量", "var" -> {
                        val name = value.substringBefore('=').trim()
                        val v = value.substringAfter('=', "").trim()
                        if (name.isBlank()) {
                            issues += SkillIssue(
                                SkillIssueSeverity.ERROR,
                                "dsl_var_invalid",
                                "变量定义格式应为：变量名 = 值",
                                line = lineNo,
                            )
                        } else {
                            variables[name] = v
                        }
                    }

                    "守卫", "guard" -> {
                        val name = value.substringBefore('=').trim()
                        val v = value.substringAfter('=', "").trim()
                        when (name) {
                            "执行前确认", "confirm" -> confirmBeforeRun = v.isTruthy()
                            "允许应用", "packages" ->
                                allowedPackages = v.split(',', '，').map { it.trim() }.filter { it.isNotBlank() }

                            "总超时", "timeout" -> {
                                val seconds = v.removeSuffix("s").removeSuffix("秒").trim().toLongOrNull()
                                if (seconds == null || seconds <= 0) {
                                    issues += SkillIssue(
                                        SkillIssueSeverity.ERROR,
                                        "dsl_guard_invalid",
                                        "总超时请填写正整数秒数",
                                        line = lineNo,
                                    )
                                } else {
                                    maxDurationMs = (seconds * 1000).coerceIn(1_000, 600_000)
                                }
                            }

                            "允许命令", "allowShell" -> allowShell = v.isTruthy()
                            else -> issues += SkillIssue(
                                SkillIssueSeverity.WARNING,
                                "dsl_guard_unknown",
                                "未知的守卫项「$name」，已忽略（可用：执行前确认 / 允许应用 / 总超时 / 允许命令）",
                                line = lineNo,
                            )
                        }
                    }

                    else -> issues += SkillIssue(
                        SkillIssueSeverity.WARNING,
                        "dsl_header_unknown",
                        "未知的头部字段「$field」，已忽略",
                        line = lineNo,
                    )
                }
                return@forEachIndexed
            }

            // ---- 动作行 ----
            var body = line
            var condition = ""

            if (body.startsWith("如果 ", ignoreCase = true) || body.startsWith("if ", ignoreCase = true)) {
                val afterIf = body.substringAfter(' ')
                val thenMarker = when {
                    afterIf.contains(" 则 ") -> " 则 "
                    afterIf.contains(" then ") -> " then "
                    else -> null
                }
                if (thenMarker == null) {
                    issues += SkillIssue(
                        SkillIssueSeverity.ERROR,
                        "dsl_condition_invalid",
                        "条件行请写成「如果 <条件> 则 <动作>」",
                        line = lineNo,
                    )
                    return@forEachIndexed
                }
                condition = afterIf.substringBefore(thenMarker).trim()
                body = afterIf.substringAfter(thenMarker).trim()
                SkillExpression.validate(condition)?.let { message ->
                    issues += SkillIssue(
                        SkillIssueSeverity.ERROR,
                        "dsl_condition_invalid",
                        "条件表达式有误：$message",
                        line = lineNo,
                    )
                }
            }

            var onError = SkillErrorPolicy.ABORT
            val flagPattern = Regex(
                """[\[(（]\s*(失败继续|continue|重试\s*\d*|retry\s*\d*)\s*[\])）]""",
                RegexOption.IGNORE_CASE,
            )
            val flags = flagPattern.findAll(body).map { it.groupValues[1].trim() }.toList()
            if (flags.isNotEmpty()) {
                body = body.replace(flagPattern, " ").replace(Regex("\\s+"), " ").trim()
            }
            var maxAttempts = 4
            flags.forEach { flag ->
                when {
                    flag.contains("失败继续") || flag.equals("continue", true) ->
                        onError = SkillErrorPolicy.CONTINUE

                    flag.contains("重试") || flag.startsWith("retry", true) -> {
                        onError = SkillErrorPolicy.RETRY
                        maxAttempts = flag.filter(Char::isDigit).toIntOrNull()?.coerceIn(1, 20) ?: 3
                    }
                }
            }

            val parsed = parseAction(body)
            if (parsed == null) {
                issues += SkillIssue(
                    SkillIssueSeverity.ERROR,
                    "dsl_action_unknown",
                    "无法识别这一行：「$body」。可用：打开/点/精确点击/输入/清空输入/按/等待/滑到/等待出现/上滑/打开链接/记录界面/设置/执行命令",
                    line = lineNo,
                )
                return@forEachIndexed
            }

            steps += parsed.copy(
                id = "step_${steps.size + 1}",
                condition = condition,
                onError = onError,
                maxAttempts = if (onError == SkillErrorPolicy.RETRY) maxAttempts else parsed.maxAttempts,
            )
        }

        if (steps.isEmpty()) {
            issues += SkillIssue(
                SkillIssueSeverity.ERROR,
                "dsl_no_steps",
                "分隔线（---）之后至少需要一行动作",
            )
        }

        val guard = base.guard.copy(
            confirmBeforeRun = if (confirmBeforeRun) true else base.guard.confirmBeforeRun,
            allowedPackages = allowedPackages.ifEmpty { base.guard.allowedPackages },
            maxDurationMs = maxDurationMs,
            // 出现 shell 步骤时自动开启，同时由校验器要求用户显式同意
            allowShell = allowShell || steps.any { it.kind == SkillActionKind.SHELL } || base.guard.allowShell,
        )

        val definition = base.copy(
            displayName = displayName.ifBlank { base.displayName },
            key = key.ifBlank { base.key },
            description = description.ifBlank { base.description },
            category = category.ifBlank { base.category },
            emoji = emoji.ifBlank { base.emoji },
            parameters = parameters.ifEmpty { base.parameters },
            steps = steps,
            variables = variables.ifEmpty { base.variables },
            guard = guard,
            updatedAt = System.currentTimeMillis(),
        )

        return Result(definition, issues, steps.size)
    }

    private fun parseParameter(value: String): SkillParameter? {
        val parts = value.split('|').map { it.trim() }
        if (parts.isEmpty() || parts[0].isBlank()) return null
        val type = when (parts.getOrNull(1)?.lowercase()) {
            "数字", "number", "num" -> SkillParamType.NUMBER
            "布尔", "开关", "boolean", "bool" -> SkillParamType.BOOLEAN
            "选择", "枚举", "choice", "enum" -> SkillParamType.CHOICE
            else -> SkillParamType.TEXT
        }
        val required = parts.getOrNull(2)?.let { it.contains("必填") || it.equals("required", true) } ?: false
        val choices = parts.getOrNull(5)
            ?.split(',', '，')
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            .orEmpty()
        return SkillParameter(
            key = parts[0],
            label = parts[0],
            type = type,
            description = parts.getOrNull(3).orEmpty(),
            example = parts.getOrNull(4).orEmpty(),
            choices = choices,
            required = required,
        )
    }

    private fun parseAction(raw: String): SkillStep? {
        val body = raw.trim()
        if (body.isEmpty()) return null

        fun after(vararg prefixes: String): String? {
            prefixes.forEach { prefix ->
                if (body.startsWith(prefix, ignoreCase = true)) {
                    return body.substring(prefix.length).trim()
                }
            }
            return null
        }

        after("点坐标", "tap at", "tapat")?.let { args ->
            val coords = args.split(',', '，').mapNotNull { it.trim().toIntOrNull() }
            if (coords.size >= 2) {
                return SkillStep(kind = SkillActionKind.TAP_POINT, x = coords[0], y = coords[1], label = "点击坐标")
            }
            return null
        }

        after("精确点击", "点击=", "tap exact")?.let { text ->
            if (text.isNotBlank()) {
                return SkillStep(
                    kind = SkillActionKind.TAP_ELEMENT,
                    target = text,
                    exactMatch = true,
                    label = "精确点击 $text",
                )
            }
        }

        after("点击", "点", "tap", "click")?.let { text ->
            if (text.isNotBlank()) {
                return SkillStep(kind = SkillActionKind.TAP_ELEMENT, target = text, label = "点击 $text")
            }
        }

        after("清空输入", "clear and type")?.let { text ->
            return SkillStep(
                kind = SkillActionKind.INPUT_TEXT,
                target = text,
                clearFirst = true,
                label = "清空并输入",
            )
        }

        after("输入", "type", "input")?.let { text ->
            return SkillStep(kind = SkillActionKind.INPUT_TEXT, target = text, label = "输入 $text")
        }

        after("按", "press")?.let { keyRaw ->
            val key = normalizeKey(keyRaw) ?: return null
            return SkillStep(kind = SkillActionKind.PRESS_KEY, target = key, label = "按 $key")
        }

        // 单字快捷指令
        when (body) {
            "返回", "返回键", "back" ->
                return SkillStep(kind = SkillActionKind.PRESS_KEY, target = "back", label = "返回")

            "主页", "回主页", "桌面", "home" ->
                return SkillStep(kind = SkillActionKind.PRESS_KEY, target = "home", label = "回主页")

            "多任务", "后台", "recents" ->
                return SkillStep(kind = SkillActionKind.PRESS_KEY, target = "recents", label = "多任务")

            "回车", "确认", "enter" ->
                return SkillStep(kind = SkillActionKind.PRESS_KEY, target = "enter", label = "回车")
        }

        after("等待出现", "wait for", "waitfor")?.let { text ->
            val noTap = text.endsWith("(不点)") || text.endsWith("（不点）")
            val cleaned = text.removeSuffix("(不点)").removeSuffix("（不点）").trim()
            return SkillStep(
                kind = SkillActionKind.WAIT_FOR_TEXT,
                target = cleaned,
                tapAfter = !noTap,
                label = "等待出现 $cleaned",
            )
        }

        after("滑到", "滚动查找", "scroll until", "scrollto")?.let { text ->
            if (text.isNotBlank()) {
                val noTap = text.endsWith("(不点)") || text.endsWith("（不点）")
                val cleaned = text.removeSuffix("(不点)").removeSuffix("（不点）").trim()
                return SkillStep(
                    kind = SkillActionKind.SCROLL_UNTIL_FOUND,
                    target = cleaned,
                    tapAfter = !noTap,
                    label = "滚动查找 $cleaned",
                )
            }
        }

        after("打开链接", "open url", "openuri")?.let { uri ->
            return SkillStep(kind = SkillActionKind.OPEN_DEEPLINK, target = uri, label = "打开链接")
        }

        after("打开", "启动", "launch", "open app", "open")?.let { app ->
            if (app.isNotBlank()) {
                return SkillStep(kind = SkillActionKind.LAUNCH_APP, target = app, label = "启动 $app")
            }
        }

        after("等待", "wait")?.let { raw ->
            val ms = parseDuration(raw) ?: return null
            return SkillStep(kind = SkillActionKind.WAIT, durationMs = ms, label = "等待 $ms 毫秒")
        }

        after("记录界面到", "snapshot to")?.let { name ->
            return SkillStep(
                kind = SkillActionKind.SCREEN_SNAPSHOT,
                storeAs = name.trim(),
                label = "记录界面到 ${name.trim()}",
            )
        }

        after("记录界面", "snapshot")?.let {
            return SkillStep(
                kind = SkillActionKind.SCREEN_SNAPSHOT,
                storeAs = "screen",
                label = "记录界面",
            )
        }

        after("设置", "set")?.let { assignment ->
            val name = assignment.substringBefore('=').trim()
            val v = assignment.substringAfter('=', "").trim()
            if (name.isBlank() || v.isBlank()) return null
            return SkillStep(
                kind = SkillActionKind.SET_VARIABLE,
                storeAs = name,
                target = v,
                label = "设置变量 $name",
            )
        }

        after("执行命令", "shell")?.let { cmd ->
            if (cmd.isBlank()) return null
            return SkillStep(kind = SkillActionKind.SHELL, target = cmd, label = "执行命令")
        }

        after("滑动", "swipe")?.let { args ->
            val match = Regex("""(-?\d+)\s*,\s*(-?\d+)\s*(?:->|→|到)\s*(-?\d+)\s*,\s*(-?\d+)""")
                .find(args) ?: return null
            val (x1, y1, x2, y2) = match.destructured
            return SkillStep(
                kind = SkillActionKind.SWIPE,
                x = x1.toIntOrNull() ?: 500,
                y = y1.toIntOrNull() ?: 500,
                toX = x2.toIntOrNull(),
                toY = y2.toIntOrNull(),
                label = "滑动手势",
            )
        }

        val scrollDir = when (body) {
            "上滑", "向上滑动", "scroll up" -> "up"
            "下滑", "向下滑动", "scroll down" -> "down"
            "左滑", "向左滑动", "scroll left" -> "left"
            "右滑", "向右滑动", "scroll right" -> "right"
            else -> null
        }
        if (scrollDir != null) {
            return SkillStep(kind = SkillActionKind.SCROLL, target = scrollDir, label = "滚动 $scrollDir")
        }

        return null
    }

    private fun normalizeKey(raw: String): String? {
        val key = raw.trim().lowercase()
        return when (key) {
            "返回", "返回键", "back" -> "back"
            "主页", "桌面", "home" -> "home"
            "多任务", "后台", "recents" -> "recents"
            "回车", "确认", "enter" -> "enter"
            else -> null
        }
    }

    private fun parseDuration(raw: String): Long? {
        val text = raw.trim().lowercase()
        return when {
            text.endsWith("ms") -> text.removeSuffix("ms").trim().toLongOrNull()
            text.endsWith("毫秒") -> text.removeSuffix("毫秒").trim().toLongOrNull()
            text.endsWith("s") -> text.removeSuffix("s").trim().toDoubleOrNull()?.times(1000)?.toLong()
            text.endsWith("秒") -> text.removeSuffix("秒").trim().toDoubleOrNull()?.times(1000)?.toLong()
            else -> text.toLongOrNull()
        }?.coerceIn(0, 30_000)
    }

    private fun String.isTruthy(): Boolean =
        equals("是", true) || equals("true", true) || equals("on", true) || equals("1", true) ||
            equals("开启", true)

    // ---------------------------------------------------------------- 渲染

    /** 把技能定义渲染回速记文本，让两种编辑模式可以自由切换。 */
    fun render(def: SkillDefinition): String = buildString {
        appendLine("# AgentPaw 技能速记 —— 「---」上方是元信息，下方一行一个动作")
        appendLine("# 修改后切回高级模式即可看到等价的 JSON")
        appendLine("名称: ${def.displayName}")
        appendLine("标识: ${def.key}")
        appendLine("说明: ${def.description}")
        if (def.category.isNotBlank()) appendLine("分类: ${def.category}")
        if (def.emoji.isNotBlank()) appendLine("表情: ${def.emoji}")
        def.parameters.forEach { p ->
            val type = when (p.type) {
                SkillParamType.TEXT -> "文本"
                SkillParamType.NUMBER -> "数字"
                SkillParamType.BOOLEAN -> "布尔"
                SkillParamType.CHOICE -> "选择"
            }
            appendLine(
                "参数: ${p.key} | $type | ${if (p.required) "必填" else "可选"} | ${p.description} | ${p.example} | ${p.choices.joinToString(",")}",
            )
        }
        def.variables.forEach { (k, v) -> appendLine("变量: $k = $v") }
        if (def.guard.confirmBeforeRun) appendLine("守卫: 执行前确认 = 是")
        if (def.guard.allowedPackages.isNotEmpty()) {
            appendLine("守卫: 允许应用 = ${def.guard.allowedPackages.joinToString(", ")}")
        }
        appendLine("守卫: 总超时 = ${def.guard.maxDurationMs / 1000}")
        if (def.guard.allowShell) appendLine("守卫: 允许命令 = 是")
        appendLine(SEPARATOR)

        def.steps.forEach { step ->
            var line = renderStep(step)
            if (line.isBlank()) return@forEach
            if (!step.enabled) line = "# 已禁用: $line"
            if (step.onError == SkillErrorPolicy.CONTINUE) line += "（失败继续）"
            if (step.onError == SkillErrorPolicy.RETRY) line += "（重试${step.maxAttempts}）"
            if (step.condition.isNotBlank()) line = "如果 ${step.condition} 则 $line"
            appendLine(line)
        }
    }

    private fun renderStep(step: SkillStep): String = when (step.kind) {        SkillActionKind.LAUNCH_APP -> "打开 ${step.target}"
        SkillActionKind.TAP_ELEMENT -> if (step.exactMatch) "精确点击 ${step.target}" else "点 ${step.target}"
        SkillActionKind.TAP_POINT -> "点坐标 ${step.x},${step.y}"
        SkillActionKind.INPUT_TEXT -> if (step.clearFirst) "清空输入 ${step.target}" else "输入 ${step.target}"
        SkillActionKind.SWIPE -> "滑动 ${step.x},${step.y} -> ${step.toX ?: step.x},${step.toY ?: step.y}"
        SkillActionKind.SCROLL -> "滚动 ${step.target}"
        SkillActionKind.PRESS_KEY -> when (step.target.lowercase()) {
            "back" -> "返回"
            "home" -> "主页"
            "recents" -> "多任务"
            else -> "回车"
        }

        SkillActionKind.WAIT -> "等待 ${step.durationMs}ms"
        SkillActionKind.WAIT_FOR_TEXT -> "等待出现 ${step.target}${if (step.tapAfter) "" else "(不点)"}"
        SkillActionKind.SCROLL_UNTIL_FOUND ->
            "滑到 ${step.target}${if (step.tapAfter) "" else "(不点)"}"
        SkillActionKind.OPEN_DEEPLINK -> "打开链接 ${step.target}"
        SkillActionKind.SCREEN_SNAPSHOT -> "记录界面到 ${step.storeAs}"
        SkillActionKind.SET_VARIABLE -> "设置 ${step.storeAs} = ${step.target}"
        SkillActionKind.SHELL -> "执行命令 ${step.target}"
    }

    /**
     * 编辑器「插入动作」用的模板行。
     * 直接给一个能跑通的写法，用户只需要把中文占位换成自己的文字。
     */
    fun templateLine(kind: SkillActionKind): String = when (kind) {
        SkillActionKind.LAUNCH_APP -> "打开 微信"
        SkillActionKind.TAP_ELEMENT -> "点 搜索"
        SkillActionKind.TAP_POINT -> "点坐标 500,500"
        SkillActionKind.INPUT_TEXT -> "输入 {{keyword}}"
        SkillActionKind.SWIPE -> "滑动 500,700 -> 500,300"
        SkillActionKind.SCROLL -> "下滑"
        SkillActionKind.PRESS_KEY -> "返回"
        SkillActionKind.WAIT -> "等待 800ms"
        SkillActionKind.WAIT_FOR_TEXT -> "等待出现 加载中|Loading"
        SkillActionKind.SCROLL_UNTIL_FOUND -> "滑到 目标文字"
        SkillActionKind.OPEN_DEEPLINK -> "打开链接 https://example.com"
        SkillActionKind.SCREEN_SNAPSHOT -> "记录界面到 screen"
        SkillActionKind.SET_VARIABLE -> "设置 greeting = 你好"
        SkillActionKind.SHELL -> "执行命令 id"
    }
}
