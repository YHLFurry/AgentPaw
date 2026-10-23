package com.paw.agent.core.skill

/**
 * 内置技能预设。
 *
 * 它们不再是硬编码的 Kotlin 类，而是用同一套 [SkillDefinition] 描述的数据。
 * 这样做的好处：
 * - 内置技能与新引擎共享同一条执行与校验路径，不存在「内置能跑、自定义跑不通」；
 * - 用户可以一键复制成自己的技能再改；
 * - 新增预设不需要改执行引擎。
 */
object SkillPresets {

    private const val NOW = 1_700_000_000_000L

    val builtIns: List<SkillDefinition> = listOf(
        SkillDefinition(
            id = "preset_return_home",
            key = "skill_return_home",
            displayName = "返回主屏",
            description = "回到 Android 主屏幕并确认当前前台应用，适合在开始新任务或切换上下文前重置界面状态。",
            emoji = "🏠",
            category = "系统",
            tags = listOf("导航", "重置"),
            steps = listOf(
                SkillStep(
                    id = "step_1",
                    kind = SkillActionKind.PRESS_KEY,
                    target = "home",
                    label = "回主页",
                    delayMs = 300,
                ),
                SkillStep(
                    id = "step_2",
                    kind = SkillActionKind.PRESS_KEY,
                    target = "home",
                    label = "再按一次确保回到桌面",
                    delayMs = 300,
                ),
                SkillStep(
                    id = "step_3",
                    kind = SkillActionKind.SCREEN_SNAPSHOT,
                    storeAs = "screen",
                    label = "记录当前界面",
                    delayMs = 0,
                ),
            ),
            guard = SkillGuard(maxDurationMs = 30_000),
            source = SkillSource.PRESET,
            builtIn = true,
            createdAt = NOW,
            updatedAt = NOW,
            authorNote = "内置预设，可复制后修改",
        ),
        SkillDefinition(
            id = "preset_open_and_search",
            key = "skill_open_and_search",
            displayName = "打开应用并搜索",
            description = "启动指定应用，等待搜索入口出现后输入关键词并提交。适合「在某个 App 里搜一下 X」这类任务，比逐步截图省很多 token。",
            emoji = "🔎",
            category = "效率",
            tags = listOf("搜索", "启动"),
            parameters = listOf(
                SkillParameter(
                    key = "app_name",
                    label = "应用",
                    type = SkillParamType.TEXT,
                    description = "应用名称或包名",
                    required = true,
                    example = "微信",
                ),
                SkillParameter(
                    key = "keyword",
                    label = "关键词",
                    type = SkillParamType.TEXT,
                    description = "要搜索的内容",
                    required = true,
                    example = "张三",
                ),
            ),
            steps = listOf(
                SkillStep(
                    id = "step_1",
                    kind = SkillActionKind.LAUNCH_APP,
                    target = "{{app_name}}",
                    label = "启动应用",
                    delayMs = 800,
                ),
                SkillStep(
                    id = "step_2",
                    kind = SkillActionKind.WAIT_FOR_TEXT,
                    target = "搜索|Search|搜索框",
                    label = "等待搜索入口",
                    timeoutMs = 6_000,
                    maxAttempts = 8,
                    tapAfter = true,
                    delayMs = 400,
                ),
                SkillStep(
                    id = "step_3",
                    kind = SkillActionKind.INPUT_TEXT,
                    target = "{{keyword}}",
                    label = "输入关键词",
                    delayMs = 200,
                ),
                SkillStep(
                    id = "step_4",
                    kind = SkillActionKind.PRESS_KEY,
                    target = "enter",
                    label = "提交搜索",
                    delayMs = 800,
                ),
                SkillStep(
                    id = "step_5",
                    kind = SkillActionKind.SCREEN_SNAPSHOT,
                    storeAs = "screen",
                    label = "记录结果页",
                    delayMs = 0,
                ),
            ),
            guard = SkillGuard(maxDurationMs = 60_000),
            source = SkillSource.PRESET,
            builtIn = true,
            createdAt = NOW,
            updatedAt = NOW,
            authorNote = "内置预设，可复制后修改",
        ),
        SkillDefinition(
            id = "preset_scroll_and_find",
            key = "skill_scroll_and_find",
            displayName = "滚动查找并点击",
            description = "在长列表或信息流中边滚动边查找指定文字，找到后自动点击。适合商品、聊天记录、设置项等需要翻找的目标。",
            emoji = "🧭",
            category = "效率",
            tags = listOf("滚动", "查找"),
            parameters = listOf(
                SkillParameter(
                    key = "target_text",
                    label = "目标文字",
                    type = SkillParamType.TEXT,
                    description = "要查找的文字，多个候选用 | 分隔",
                    required = true,
                    example = "确认收货",
                ),
            ),
            steps = listOf(
                SkillStep(
                    id = "step_1",
                    kind = SkillActionKind.SCROLL_UNTIL_FOUND,
                    target = "{{target_text}}",
                    label = "滚动查找目标",
                    maxAttempts = 5,
                    tapAfter = true,
                    timeoutMs = 20_000,
                    delayMs = 400,
                ),
            ),
            guard = SkillGuard(maxDurationMs = 45_000),
            source = SkillSource.PRESET,
            builtIn = true,
            createdAt = NOW,
            updatedAt = NOW,
            authorNote = "内置预设，可复制后修改",
        ),
    )

    val builtInKeys: Set<String> = builtIns.map { it.key }.toSet()

    fun find(key: String): SkillDefinition? = builtIns.firstOrNull { it.key == key }

    /** 新建技能的空白模板：给一个能立刻跑通的最小步骤与合法名称，降低上手门槛。 */
    fun blankTemplate(now: Long = System.currentTimeMillis()): SkillDefinition = SkillDefinition(
        id = "",
        key = "skill_custom_1",
        displayName = "新建技能",
        description = "点击桌面主页的快捷技能操作，可在下方编辑具体步骤",
        emoji = "🧩",
        category = "通用",
        steps = listOf(
            SkillStep(
                id = "step_1",
                kind = SkillActionKind.PRESS_KEY,
                target = "home",
                label = "先回到桌面",
                delayMs = 300,
            ),
        ),
        source = SkillSource.MANUAL,
        createdAt = now,
        updatedAt = now,
    )
}
