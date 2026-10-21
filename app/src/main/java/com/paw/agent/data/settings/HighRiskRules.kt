package com.paw.agent.data.settings

/**
 * 高危开关的纯逻辑层。
 *
 * 放在这里而不是直接写进 DataStore 实现，是为了让「约束」可被单元测试直接覆盖：
 * 依赖关系写在 UI 里只能防住点击，写在这里才能防住任何调用方（包括未来的
 * 命令行、备份恢复、自动化脚本）。
 */
data class HighRiskState(
    val allowHttp: Boolean = false,
    val allowPublicHttp: Boolean = false,
    val allowShellSkills: Boolean = false,
    val allowSensitiveScreen: Boolean = false,
    val rootModeEnabled: Boolean = false,
)

object HighRiskRules {

    data class Outcome(
        val state: HighRiskState,
        val applied: Boolean,
        val message: String? = null,
    )

    /**
     * 应用一次开关变更。
     *
     * 约束：
     * - 关闭「允许 HTTP」时，公网明文必须一起关闭（否则会出现“子开关开着、父开关关着”的幽灵状态）；
     * - 「公网明文」只能在「允许 HTTP」已开启时打开；
     * - 「技能执行 Root 命令」只能在 ROOT 执行模式已开启时打开。
     */
    fun toggle(state: HighRiskState, toggle: HighRiskToggle, enabled: Boolean): Outcome {
        if (!enabled) {
            return when (toggle) {
                HighRiskToggle.ALLOW_HTTP -> Outcome(
                    state.copy(allowHttp = false, allowPublicHttp = false),
                    applied = true,
                )

                HighRiskToggle.ALLOW_PUBLIC_HTTP -> Outcome(state.copy(allowPublicHttp = false), true)
                HighRiskToggle.ALLOW_SHELL_SKILLS -> Outcome(state.copy(allowShellSkills = false), true)
                HighRiskToggle.ALLOW_SENSITIVE_SCREEN -> Outcome(state.copy(allowSensitiveScreen = false), true)
            }
        }

        return when (toggle) {
            HighRiskToggle.ALLOW_HTTP ->
                Outcome(state.copy(allowHttp = true), applied = true)

            HighRiskToggle.ALLOW_PUBLIC_HTTP ->
                if (!state.allowHttp) {
                    Outcome(state, applied = false, message = "请先开启「允许 HTTP 传输」，再开启公网明文。")
                } else {
                    Outcome(state.copy(allowPublicHttp = true), applied = true)
                }

            HighRiskToggle.ALLOW_SHELL_SKILLS ->
                if (!state.rootModeEnabled) {
                    Outcome(state, applied = false, message = "技能执行 Root 命令依赖 ROOT 执行模式，请先在上方开启。")
                } else {
                    Outcome(state.copy(allowShellSkills = true), applied = true)
                }

            HighRiskToggle.ALLOW_SENSITIVE_SCREEN ->
                Outcome(state.copy(allowSensitiveScreen = true), applied = true)
        }
    }

    /** ROOT 模式关闭时，连带关闭依赖它的技能 shell 权限。 */
    fun onRootModeChanged(state: HighRiskState, enabled: Boolean): HighRiskState =
        state.copy(
            rootModeEnabled = enabled,
            allowShellSkills = if (enabled) state.allowShellSkills else false,
        )

    /** 计算「临时跳过风险确认」的截止时间戳；0 表示关闭。 */
    fun bypassUntil(minutes: Int, now: Long = System.currentTimeMillis()): Long {
        val clamped = minutes.coerceIn(0, AppSettings.MAX_RISK_BYPASS_MINUTES)
        return if (clamped == 0) 0L else now + clamped * 60_000L
    }

    /** 读取配置时的收敛：即使磁盘里存在非法组合，对外也只暴露安全值。 */
    fun sanitize(state: HighRiskState): HighRiskState = state.copy(
        allowPublicHttp = state.allowHttp && state.allowPublicHttp,
        allowShellSkills = state.rootModeEnabled && state.allowShellSkills,
    )
}
