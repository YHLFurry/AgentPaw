package com.paw.agent.core.skill

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** 技能动作自身的风险级别（与工具调用的 [com.paw.agent.core.agent.RiskLevel] 独立）。 */
enum class SkillRisk {
    NONE,
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL,
}

/**
 * 单个动作类型的元数据。
 *
 * 这份表是「单一事实来源」：编辑器用它决定显示哪些字段，校验器用它判断必填项，
 * 许愿提示词用它告诉模型每个动作接受什么参数，UI 用它渲染风险角标。
 */
data class SkillActionSpec(
    val kind: SkillActionKind,
    val label: String,
    val emoji: String,
    val summary: String,
    val llmHint: String,
    val risk: SkillRisk = SkillRisk.NONE,
    val needsTarget: Boolean = false,
    val targetHint: String = "",
    val needsPoint: Boolean = false,
    val needsDestination: Boolean = false,
    val usesDuration: Boolean = false,
    val usesTimeout: Boolean = false,
    val usesAttempts: Boolean = false,
    val usesTapAfter: Boolean = false,
    val usesClearFirst: Boolean = false,
    val usesStoreAs: Boolean = false,
) {
    val isRisky: Boolean get() = risk >= SkillRisk.MEDIUM
}

/**
 * 动作目录。顺序即编辑器里的展示顺序（由安全到高危）。
 */
object SkillCatalog {

    val actions: List<SkillActionSpec> = listOf(
        SkillActionSpec(
            kind = SkillActionKind.LAUNCH_APP,
            label = "启动应用",
            emoji = "🚀",
            summary = "按应用名或包名启动一个 App",
            llmHint = "target = 应用名或包名，例如「微信」或 com.tencent.mm",
            risk = SkillRisk.LOW,
            needsTarget = true,
            targetHint = "应用名 / 包名",
        ),
        SkillActionSpec(
            kind = SkillActionKind.TAP_ELEMENT,
            label = "点击文字",
            emoji = "👆",
            summary = "在当前界面找到指定文字并点击它",
            llmHint = "target = 界面上的文字（可用 | 分隔多个候选，如「搜索|Search」）；exactMatch 控制是否精确匹配",
            risk = SkillRisk.MEDIUM,
            needsTarget = true,
            targetHint = "要点击的文字",
        ),
        SkillActionSpec(
            kind = SkillActionKind.TAP_POINT,
            label = "点击坐标",
            emoji = "🎯",
            summary = "点击屏幕上的归一化坐标 (0~1000)",
            llmHint = "x/y 为 0..1000 归一化坐标，左上角是 (0,0)",
            risk = SkillRisk.MEDIUM,
            needsPoint = true,
        ),
        SkillActionSpec(
            kind = SkillActionKind.INPUT_TEXT,
            label = "输入文字",
            emoji = "⌨️",
            summary = "向当前焦点输入框输入文字",
            llmHint = "target = 要输入的文字，可包含 {{参数}}；clearFirst = 是否先清空",
            risk = SkillRisk.MEDIUM,
            needsTarget = true,
            targetHint = "输入内容",
            usesClearFirst = true,
        ),
        SkillActionSpec(
            kind = SkillActionKind.SWIPE,
            label = "滑动手势",
            emoji = "↔️",
            summary = "从一点滑动到另一点",
            llmHint = "x/y = 起点，toX/toY = 终点（均为 0..1000），durationMs = 手势时长",
            risk = SkillRisk.MEDIUM,
            needsPoint = true,
            needsDestination = true,
            usesDuration = true,
        ),
        SkillActionSpec(
            kind = SkillActionKind.SCROLL,
            label = "整屏滚动",
            emoji = "📜",
            summary = "按方向滚动一屏（上/下/左/右）",
            llmHint = "target = up|down|left|right；durationMs 控制手势速度",
            risk = SkillRisk.MEDIUM,
            needsTarget = true,
            targetHint = "up / down / left / right",
            usesDuration = true,
        ),
        SkillActionSpec(
            kind = SkillActionKind.PRESS_KEY,
            label = "系统按键",
            emoji = "🔘",
            summary = "返回 / 主页 / 多任务 / 回车",
            llmHint = "target = back|home|recents|enter",
            risk = SkillRisk.LOW,
            needsTarget = true,
            targetHint = "back / home / recents / enter",
        ),
        SkillActionSpec(
            kind = SkillActionKind.WAIT,
            label = "等待",
            emoji = "⏳",
            summary = "固定等待一段时间",
            llmHint = "durationMs = 等待毫秒数（0..30000）",
            usesDuration = true,
        ),
        SkillActionSpec(
            kind = SkillActionKind.WAIT_FOR_TEXT,
            label = "等待文字出现",
            emoji = "🔍",
            summary = "轮询界面直到出现指定文字（可选自动点击）",
            llmHint = "target = 等待的文字（| 分隔多个），timeoutMs = 超时，tapAfter = 是否点击",
            risk = SkillRisk.MEDIUM,
            needsTarget = true,
            targetHint = "等待出现的文字",
            usesTimeout = true,
            usesTapAfter = true,
        ),
        SkillActionSpec(
            kind = SkillActionKind.SCROLL_UNTIL_FOUND,
            label = "滚动查找",
            emoji = "🧭",
            summary = "边滚动边查找目标文字，找到后可点击",
            llmHint = "target = 目标文字，maxAttempts = 最多滚动次数，tapAfter = 是否点击",
            risk = SkillRisk.MEDIUM,
            needsTarget = true,
            targetHint = "目标文字",
            usesAttempts = true,
            usesTapAfter = true,
        ),
        SkillActionSpec(
            kind = SkillActionKind.SCREEN_SNAPSHOT,
            label = "记录界面",
            emoji = "📸",
            summary = "把当前前台包名与可见文字存进变量",
            llmHint = "storeAs = 变量名，后续可用 {{变量名}} 引用",
            usesStoreAs = true,
        ),
        SkillActionSpec(
            kind = SkillActionKind.SET_VARIABLE,
            label = "设置变量",
            emoji = "🧮",
            summary = "计算/拼接一个变量供后续步骤使用",
            llmHint = "storeAs = 变量名，target = 值（支持模板）",
            usesStoreAs = true,
        ),
        SkillActionSpec(
            kind = SkillActionKind.OPEN_DEEPLINK,
            label = "打开链接",
            emoji = "🔗",
            summary = "用系统或应用打开一个 URI / DeepLink",
            llmHint = "target = 完整 URI，例如 https://… 或 weixin://…",
            risk = SkillRisk.HIGH,
            needsTarget = true,
            targetHint = "URI / DeepLink",
        ),
        SkillActionSpec(
            kind = SkillActionKind.SHELL,
            label = "执行命令",
            emoji = "☠️",
            summary = "以 Root 身份执行 shell 命令（极高危）",
            llmHint = "target = shell 命令；只有用户明确要求时才可使用",
            risk = SkillRisk.CRITICAL,
            needsTarget = true,
            targetHint = "shell 命令",
        ),
    )

    private val byKind: Map<SkillActionKind, SkillActionSpec> =
        actions.associateBy { it.kind }

    fun spec(kind: SkillActionKind): SkillActionSpec =
        byKind[kind] ?: error("No catalog entry for $kind")

    fun isRisky(kind: SkillActionKind): Boolean = spec(kind).isRisky

    /** 危险到必须在技能级显式确认后才能执行的动作。 */
    fun requiresExplicitConfirmation(kind: SkillActionKind): Boolean =
        kind == SkillActionKind.SHELL || kind == SkillActionKind.OPEN_DEEPLINK

    /**
     * 给许愿模型看的动作清单。刻意写得像文档：模型直接照着填字段即可。
     */
    fun llmCatalog(): String = actions.joinToString("\n") { spec ->
        val fields = buildList {
            if (spec.needsTarget) add("target")
            if (spec.needsPoint) add("x,y")
            if (spec.needsDestination) add("toX,toY")
            if (spec.usesDuration) add("durationMs")
            if (spec.usesTimeout) add("timeoutMs")
            if (spec.usesAttempts) add("maxAttempts")
            if (spec.usesTapAfter) add("tapAfter")
            if (spec.usesClearFirst) add("clearFirst")
            if (spec.usesStoreAs) add("storeAs")
        }
        "- ${spec.kind}: ${spec.llmHint}（字段：${fields.ifEmpty { listOf("无额外字段") }.joinToString(",")}；风险：${spec.risk}）"
    }

    /**
     * 从参数定义生成标准 JSON Schema。
     *
     * 旧实现把 schema 当成一段自由文本存进数据库，模型看到一个无类型的对象；
     * 现在模型看到的是真实类型、枚举与必填列表，调用准确率显著更高。
     */
    fun schemaFor(def: SkillDefinition): String {
        val properties = buildJsonObject {
            def.parameters.forEach { param ->
                if (param.key.isBlank()) return@forEach
                putJsonObject(param.key) {
                    put(
                        "type",
                        when (param.type) {
                            SkillParamType.NUMBER -> "number"
                            SkillParamType.BOOLEAN -> "boolean"
                            else -> "string"
                        },
                    )
                    val desc = listOfNotNull(
                        param.description.takeIf { it.isNotBlank() },
                        param.example.takeIf { it.isNotBlank() }?.let { "例如：$it" },
                        param.defaultValue.takeIf { it.isNotBlank() }?.let { "默认：$it" },
                    ).joinToString("；")
                    if (desc.isNotBlank()) put("description", desc)
                    if (param.type == SkillParamType.CHOICE && param.choices.isNotEmpty()) {
                        put("enum", buildJsonArray { param.choices.forEach { add(it) } })
                    }
                }
            }
        }
        val required = def.parameters.filter { it.required && it.key.isNotBlank() }
        return buildJsonObject {
            put("type", "object")
            put("properties", JsonObject(properties))
            if (required.isNotEmpty()) {
                put("required", buildJsonArray { required.forEach { add(it.key) } })
            }
        }.toString()
    }

    /** 参数默认值表，执行前与模型实参合并。 */
    fun defaultsOf(def: SkillDefinition): Map<String, String> =
        def.parameters
            .filter { it.key.isNotBlank() && it.defaultValue.isNotBlank() }
            .associate { it.key to it.defaultValue }

    /** 静态风险画像：给编辑器与确认卡片用。 */
    fun riskOf(def: SkillDefinition): SkillRisk =
        def.steps.maxOfOrNull { spec(it.kind).risk } ?: SkillRisk.NONE

    internal fun jsonPrimitive(value: String): JsonPrimitive = JsonPrimitive(value)
}
