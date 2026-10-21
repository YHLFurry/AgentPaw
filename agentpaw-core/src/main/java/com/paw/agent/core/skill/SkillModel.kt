package com.paw.agent.core.skill

import kotlinx.serialization.Serializable

/**
 * 技能定义的模式版本。
 *
 * v1 是已废弃的旧实现（固定 8 种动作 + 字符串拼接的 JSON），v2 起重新设计：
 * 动作、参数、变量、条件、守卫全部结构化，并可无损往返于「速记文本 / JSON」两种表示。
 */
const val SKILL_SCHEMA_VERSION: Int = 2

/** 技能来源，用于在界面上区分内置、手写、AI 许愿与导入。 */
@Serializable
enum class SkillSource {
    /** 内置只读预设，不可编辑，可复制。 */
    PRESET,

    /** 用户在编辑器里手写。 */
    MANUAL,

    /** 由「许愿式 AI」根据自然语言生成。 */
    WISH,

    /** 从文件 / 剪贴板导入。 */
    IMPORTED,
}

/** 参数类型。只做四种，够覆盖真实场景，也让许愿生成的 JSON 更好约束。 */
@Serializable
enum class SkillParamType {
    TEXT,
    NUMBER,
    BOOLEAN,
    CHOICE,
}

/**
 * 技能入参定义。
 *
 * 这些定义会被翻译成标准 JSON Schema 交给大模型，因此模型看到的是
 * 「带类型、带说明、带枚举」的参数，而不是一段自由文本。
 */
@Serializable
data class SkillParameter(
    val key: String = "",
    val label: String = "",
    val type: SkillParamType = SkillParamType.TEXT,
    val description: String = "",
    val required: Boolean = false,
    val defaultValue: String = "",
    val choices: List<String> = emptyList(),
    val example: String = "",
) {
    /** 供 JSON Schema 与校验器共用的参数名规则。 */
    val isKeyValid: Boolean
        get() = KEY_PATTERN.matches(key)

    companion object {
        val KEY_PATTERN = Regex("^[a-zA-Z_][a-zA-Z0-9_]{0,31}$")
    }
}

/** 单步失败后的处理策略。 */
@Serializable
enum class SkillErrorPolicy {
    /** 终止整个技能并返回失败。 */
    ABORT,

    /** 记录后继续执行下一步。 */
    CONTINUE,

    /** 按 [SkillStep.maxAttempts] 重试，仍失败则终止。 */
    RETRY,
}

/**
 * 技能动作类型。
 *
 * 相比旧实现新增了「等待文本出现」「滚动直到找到」「记录屏幕快照」
 * 「设置变量」等能力，使一次技能执行可以在不回到大模型的情况下完成
 * 观察—判断—再动作的小闭环。
 */
@Serializable
enum class SkillActionKind {
    LAUNCH_APP,
    TAP_POINT,
    TAP_ELEMENT,
    INPUT_TEXT,
    SWIPE,
    SCROLL,
    PRESS_KEY,
    WAIT,
    WAIT_FOR_TEXT,
    SCROLL_UNTIL_FOUND,
    OPEN_DEEPLINK,
    SCREEN_SNAPSHOT,
    SET_VARIABLE,
    SHELL,
}

/**
 * 一个可执行步骤。
 *
 * 字段刻意保持扁平：任何字段都可以在「高级模式」里直接编辑，序列化后仍然是
 * 一份人类可读的 JSON。未知字段通过 [options] 透传，便于后续扩展时旧版本不崩。
 */
@Serializable
data class SkillStep(
    val id: String = "",
    val kind: SkillActionKind = SkillActionKind.WAIT,
    val label: String = "",
    /** 主参数：应用名/文本/按键名/URI/命令/变量值。支持 `{{参数}}` 模板。 */
    val target: String = "",
    /** 归一化坐标，0..1000。 */
    val x: Int = 500,
    val y: Int = 500,
    val toX: Int? = null,
    val toY: Int? = null,
    /** 手势时长。WAIT 步骤用它表示等待毫秒数。 */
    val durationMs: Long = 350L,
    /** 本步执行完成后的稳定等待，避免动画未结束就进入下一步。 */
    val delayMs: Long = 400L,
    /** 需要轮询的步骤（等待文本/滚动查找）的整体超时。 */
    val timeoutMs: Long = 4_000L,
    /** 需要轮询的步骤的最大尝试次数（含首次）。 */
    val maxAttempts: Int = 4,
    val exactMatch: Boolean = false,
    /** 找到目标后是否自动点击。 */
    val tapAfter: Boolean = true,
    /** 输入前是否先清空输入框。 */
    val clearFirst: Boolean = false,
    /** 条件表达式；为空表示无条件执行。语法见 [SkillExpression]。 */
    val condition: String = "",
    val onError: SkillErrorPolicy = SkillErrorPolicy.ABORT,
    /** 把本步结果写入该变量，供后续步骤的模板与条件使用。 */
    val storeAs: String = "",
    val enabled: Boolean = true,
    val note: String = "",
    val options: Map<String, String> = emptyMap(),
)

/**
 * 技能级守卫。
 *
 * 这些是「默认安全」的显式表达：危险动作不是靠执行期临时判断，而是在技能
 * 定义里就声明清楚，这样校验器、编辑器与执行引擎看到的是同一份事实。
 */
@Serializable
data class SkillGuard(
    /** 执行前必须由用户显式确认（含 shell / 支付 / 发送类步骤时自动置位）。 */
    val confirmBeforeRun: Boolean = false,
    /** 非空时，只允许在这些前台包名内执行交互动作。 */
    val allowedPackages: List<String> = emptyList(),
    /** 整个技能的硬超时。 */
    val maxDurationMs: Long = 180_000L,
    /** 是否允许在敏感（支付/密码）页面继续执行；默认禁止，命中即安全暂停。 */
    val allowSensitiveScreen: Boolean = false,
    /** 是否允许包含 Root shell 步骤。执行期还会再受资深设置开关约束。 */
    val allowShell: Boolean = false,
)

/**
 * 完整技能定义。
 *
 * [key] 就是暴露给大模型的工具名；[description] 决定了模型能否在正确时机调用它，
 * 因此校验器会对过短的描述直接报错。
 */
@Serializable
data class SkillDefinition(
    val schemaVersion: Int = SKILL_SCHEMA_VERSION,
    val id: String = "",
    val key: String = "",
    val displayName: String = "",
    val description: String = "",
    val emoji: String = "🧩",
    val category: String = "通用",
    val tags: List<String> = emptyList(),
    val parameters: List<SkillParameter> = emptyList(),
    val steps: List<SkillStep> = emptyList(),
    val variables: Map<String, String> = emptyMap(),
    val guard: SkillGuard = SkillGuard(),
    val enabled: Boolean = true,
    val source: SkillSource = SkillSource.MANUAL,
    val builtIn: Boolean = false,
    val revision: Int = 1,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val authorNote: String = "",
) {
    /** 便捷视图：所有可被模板引用的变量名（参数 + 静态变量）。 */
    val variableKeys: Set<String>
        get() = parameters.map { it.key }.filter { it.isNotBlank() }.toSet() + variables.keys
}

/** 校验问题的严重级别。 */
enum class SkillIssueSeverity {
    ERROR,
    WARNING,
    INFO,
}

/**
 * 一条可定位的校验诊断。`stepId`/`line` 至少有一个可用于把用户带到出错位置。
 */
data class SkillIssue(
    val severity: SkillIssueSeverity,
    val code: String,
    val message: String,
    val stepId: String? = null,
    val field: String? = null,
    val line: Int? = null,
) {
    val isError: Boolean get() = severity == SkillIssueSeverity.ERROR
}

/** 单步执行结果。 */
enum class SkillStepStatus {
    SUCCESS,
    SKIPPED,
    FAILED,
    PAUSED,
}

data class SkillStepReport(
    val stepId: String,
    val kind: SkillActionKind,
    val label: String,
    val status: SkillStepStatus,
    val message: String = "",
    val durationMs: Long = 0L,
    val attempts: Int = 1,
)

enum class SkillRunStatus {
    SUCCESS,
    FAILED,
    /** 需要用户显式授权后才能继续（整条技能会带 `confirmed` 重放，见 SkillEngine）。 */
    REQUIRES_CONFIRMATION,
    /** 命中敏感页面，必须由用户手动完成该步骤。 */
    SAFETY_PAUSED,
    /** 用户中止。 */
    CANCELLED,
}

/**
 * 技能执行结果。
 *
 * [steps] 是完整的分步轨迹，既回传给模型用于自我修正，也在界面的「测试运行」
 * 面板里逐条展示——旧实现只回一句 `status: success`，出问题时无法定位。
 */
data class SkillRunResult(
    val status: SkillRunStatus,
    val skillKey: String,
    val message: String = "",
    val steps: List<SkillStepReport> = emptyList(),
    val variables: Map<String, String> = emptyMap(),
    /** 需要确认时，直接就是给 Runner 解析的控制信号 JSON。 */
    val controlPayload: String? = null,
) {
    val isSuccess: Boolean get() = status == SkillRunStatus.SUCCESS

    val executedSteps: Int get() = steps.count { it.status == SkillStepStatus.SUCCESS }

    val failedStep: SkillStepReport? get() = steps.firstOrNull { it.status == SkillStepStatus.FAILED }
}

/** 执行期策略，由宿主应用（设置开关）注入，核心模块不认识 DataStore。 */
data class SkillRunOptions(
    /** 资深设置中的「允许技能执行 Root 命令」。 */
    val allowShellSteps: Boolean = false,
    /** 资深设置中的临时跳过风险确认（有有效期，由宿主判定）。 */
    val bypassRiskConfirmation: Boolean = false,
    /** 是否允许在敏感页面上执行（资深设置，默认关闭）。 */
    val allowSensitiveScreen: Boolean = false,
)
