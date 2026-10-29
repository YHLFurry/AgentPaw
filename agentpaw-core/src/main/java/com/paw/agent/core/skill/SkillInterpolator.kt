package com.paw.agent.core.skill

/** 模板渲染结果。[missing] 是被引用但未赋值的变量名，用于给出可解释的警告。 */
data class InterpolatedText(
    val text: String,
    val missing: List<String>,
) {
    val hasMissing: Boolean get() = missing.isNotEmpty()
}

/**
 * 技能模板渲染。
 *
 * 支持三种写法，互相等价，想怎么写就怎么写：
 * - `{{keyword}}`
 * - `${keyword}`
 * - `{{keyword|没有填关键词}}` —— 变量为空时使用竖线后的兜底值
 *
 * 刻意不支持任意表达式：模板只做替换，逻辑必须写在 `condition` 里，
 * 这样执行轨迹才是可解释、可复现的。
 */
object SkillInterpolator {

    private val PATTERN = Regex(
        // Android 使用 ICU，未转义的右花括号会在对象初始化时抛 PatternSyntaxException。
        """\{\{\s*([a-zA-Z_][a-zA-Z0-9_.]*)\s*(?:\|\s*([^}]*))?\}\}|\$\{\s*([a-zA-Z_][a-zA-Z0-9_.]*)\s*\}""",
    )

    fun render(template: String, variables: Map<String, String>): InterpolatedText {
        if (template.isEmpty()) return InterpolatedText("", emptyList())
        val missing = LinkedHashSet<String>()

        val rendered = PATTERN.replace(template) { match ->
            val name = match.groupValues[1].ifBlank { match.groupValues[3] }
            val fallback = match.groupValues[2]
            val value = variables[name]
            when {
                !value.isNullOrEmpty() -> value
                fallback.isNotEmpty() -> fallback
                else -> {
                    missing += name
                    ""
                }
            }
        }
        return InterpolatedText(rendered, missing.toList())
    }

    /** 提取模板中引用的变量名（用于校验「引用了不存在的参数」）。 */
    fun referencedVariables(template: String): List<String> =
        PATTERN.findAll(template).mapNotNull { match ->
            match.groupValues[1].ifBlank { match.groupValues[3] }.takeIf { it.isNotBlank() }
        }.toList()
}
