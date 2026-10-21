package com.paw.agent.core.skill

import com.paw.agent.core.agent.AgentContext
import com.paw.agent.core.agent.AgentTool
import com.paw.agent.core.model.ToolDefinition

/**
 * 把一个 [SkillDefinition] 暴露成模型可调用的标准工具。
 *
 * 参数 schema 由 [SkillCatalog.schemaFor] 从参数定义生成——模型据此知道每个
 * 入参的类型、枚举与是否必填，而不是像旧实现那样收到一个空对象 schema。
 */
class SkillToolAdapter(
    val skill: SkillDefinition,
    private val engine: SkillEngine,
) : AgentTool {

    override val definition: ToolDefinition = ToolDefinition(
        name = skill.key,
        description = buildString {
            append("[SKILL] ${skill.displayName}")
            if (skill.description.isNotBlank()) append(" —— ${skill.description}")
            val risk = SkillCatalog.riskOf(skill)
            if (risk >= SkillRisk.HIGH) append("（高风险技能，执行前需用户确认）")
        },
        parametersSchema = SkillCatalog.schemaFor(skill),
    )

    override suspend fun execute(arguments: String, context: AgentContext): String =
        engine.modelFacingJson(engine.run(skill, arguments, context))
}

/**
 * 运行期技能注册表。
 *
 * 只保存定义，不保存「已构造好的工具」：引擎的选项会随资深设置实时变化，
 * 每次取工具列表时用当前引擎重新适配，避免设置改了但工具还拿着旧策略。
 */
class SkillRegistry(
    presets: List<SkillDefinition> = SkillPresets.builtIns,
) {
    private val builtIns: List<SkillDefinition> = presets

    @Volatile
    private var userSkills: List<SkillDefinition> = emptyList()

    fun replaceUserSkills(skills: List<SkillDefinition>) {
        userSkills = skills.toList()
    }

    fun userDefinitions(): List<SkillDefinition> = userSkills

    /** 内置 + 用户技能；key 冲突时用户定义覆盖内置。 */
    fun allSkills(): List<SkillDefinition> {
        val userKeys = userSkills.map { it.key }.toSet()
        return builtIns.filter { it.key !in userKeys } + userSkills
    }

    fun enabledSkills(): List<SkillDefinition> = allSkills().filter { it.enabled }

    fun find(key: String): SkillDefinition? = allSkills().firstOrNull { it.key == key }

    fun tools(engine: SkillEngine): List<AgentTool> = enabledSkills().map { SkillToolAdapter(it, engine) }

    /** 模型的工具列表里占了多少个位置，用于设置页展示。 */
    fun activeCount(): Int = enabledSkills().size
}
