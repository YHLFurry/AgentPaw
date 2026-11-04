package com.paw.agent.core.skill

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 技能核心：校验器 / 表达式 / 速记格式。
 *
 * 这些测试刻意不依赖 Android，也不依赖大模型——引擎的正确性必须能在 JVM 上
 * 被反复验证，否则「技能能存但跑不通」这类问题只能靠真机碰运气。
 */
class SkillDefinitionTest {

    // ------------------------------------------------------------- 校验器

    @Test
    fun `validator rejects invalid key, missing description and empty steps`() {
        val def = SkillDefinition(
            key = "Bad Key!",
            displayName = "x",
            description = "",
            steps = emptyList(),
        )
        val codes = SkillValidator.validate(def).filter { it.isError }.map { it.code }
        assertTrue(codes.contains("key_invalid"))
        assertTrue(codes.contains("desc_blank"))
        assertTrue(codes.contains("steps_empty"))
    }

    @Test
    fun `validator rejects reserved tool name`() {
        val def = SkillDefinition(
            key = "tap",
            displayName = "伪装成点击",
            description = "这是一个足够长的描述文本",
            steps = listOf(SkillStep(kind = SkillActionKind.PRESS_KEY, target = "back")),
        )
        assertTrue(SkillValidator.validate(def).any { it.code == "key_reserved" })
    }

    @Test
    fun `shell step without guard allowShell is an error`() {
        val def = SkillDefinition(
            key = "skill_danger",
            displayName = "危险技能",
            description = "执行一条系统命令用于测试校验",
            steps = listOf(SkillStep(kind = SkillActionKind.SHELL, target = "id")),
        )
        assertTrue(SkillValidator.validate(def).any { it.code == "shell_not_allowed" })
    }

    @Test
    fun `unknown variable reference is an error`() {
        val def = SkillDefinition(
            key = "skill_var",
            displayName = "变量测试",
            description = "引用一个不存在的变量用于测试校验",
            steps = listOf(SkillStep(kind = SkillActionKind.INPUT_TEXT, target = "{{missing}}")),
        )
        assertTrue(SkillValidator.validate(def).any { it.code == "var_unknown" })
    }

    @Test
    fun `declared parameter can be referenced`() {
        val def = SkillDefinition(
            key = "skill_var_ok",
            displayName = "变量测试",
            description = "引用一个已声明的参数用于测试校验",
            parameters = listOf(SkillParameter(key = "keyword", required = true)),
            steps = listOf(SkillStep(kind = SkillActionKind.INPUT_TEXT, target = "{{keyword}}")),
        )
        assertTrue(SkillValidator.validate(def).none { it.code == "var_unknown" })
    }

    @Test
    fun `condition syntax error is reported on the step`() {
        val def = SkillDefinition(
            key = "skill_cond",
            displayName = "条件测试",
            description = "写错条件表达式用于测试校验",
            steps = listOf(
                SkillStep(kind = SkillActionKind.PRESS_KEY, target = "back", condition = "a == = b"),
            ),
        )
        val issue = SkillValidator.validate(def).firstOrNull { it.code == "step_condition_invalid" }
        assertNotNull(issue)
        assertNotNull(issue!!.stepId)
    }

    @Test
    fun `normalize fills ids and derives a legal key`() {
        val raw = SkillDefinition(displayName = "微信 搜索")
        val normalized = SkillValidator.normalize(raw) { 1_000L }
        assertTrue(normalized.id.isNotBlank())
        assertTrue(SkillValidator.KEY_PATTERN.matches(normalized.key))
        assertEquals(1_000L, normalized.createdAt)
    }

    @Test
    fun `normalize makes duplicate step ids unique`() {
        val raw = SkillDefinition(
            displayName = "重复步骤",
            steps = listOf(
                SkillStep(id = "dup", kind = SkillActionKind.PRESS_KEY, target = "back"),
                SkillStep(id = "dup", kind = SkillActionKind.PRESS_KEY, target = "home"),
            ),
        )
        val normalized = SkillValidator.normalize(raw) { 1L }
        assertEquals(listOf("dup", "dup_2"), normalized.steps.map { it.id })
    }

    // ------------------------------------------------------------- 表达式

    @Test
    fun `expression evaluates comparisons, booleans and functions`() {
        val vars = mapOf("mode" to "fast", "count" to "3", "text" to "hello world")

        assertTrue(SkillExpression.evaluate("mode == \"fast\"", vars))
        assertFalse(SkillExpression.evaluate("mode == \"slow\"", vars))
        assertTrue(SkillExpression.evaluate("count >= 3", vars))
        assertTrue(SkillExpression.evaluate("""mode == "fast" && count > 2""", vars))
        assertTrue(SkillExpression.evaluate("""mode == "slow" || count == 3""", vars))
        assertTrue(SkillExpression.evaluate("""contains(text, "world")""", vars))
        assertTrue(SkillExpression.evaluate("""notEmpty(mode) && empty(unknown)""", vars))
        assertTrue(SkillExpression.evaluate("len(text) > 5", vars))
    }

    @Test
    fun `blank condition means always true`() {
        assertTrue(SkillExpression.evaluate("", emptyMap()))
    }

    @Test
    fun `broken expression evaluates to false and reports a syntax error`() {
        assertFalse(SkillExpression.evaluate("a == = b", emptyMap()))
        assertNotNull(SkillExpression.validate("a == = b"))
        assertEquals(null, SkillExpression.validate("mode == \"fast\""))
    }

    // ------------------------------------------------------------ 模板插值

    @Test
    fun `interpolator supports braces, dollars and fallback`() {
        val vars = mapOf("name" to "小明", "empty" to "")
        assertEquals("你好 小明", SkillInterpolator.render("你好 {{name}}", vars).text)
        assertEquals("你好 小明", SkillInterpolator.render("你好 \${name}", vars).text)
        assertEquals("你好 陌生人", SkillInterpolator.render("你好 {{empty|陌生人}}", vars).text)

        val missing = SkillInterpolator.render("{{nobody}}", vars)
        assertTrue(missing.hasMissing)
        assertEquals(listOf("nobody"), missing.missing)
    }

    @Test fun `interpolator preserves literal braces and extracts both placeholder forms`() {
        val template = "{literal} {{ name }} \${profile.name} {{missing|备用}}"
        assertEquals(listOf("name", "profile.name", "missing"), SkillInterpolator.referencedVariables(template))
        val result = SkillInterpolator.render(template, mapOf("name" to "小明", "profile.name" to "小红"))
        assertEquals("{literal} 小明 小红 备用", result.text)
        assertFalse(result.hasMissing)
    }

    // -------------------------------------------------------------- 速记

    @Test
    fun `dsl parses metadata, parameters, guards and actions`() {
        val text = """
            名称: 微信搜索好友
            标识: skill_wechat_search
            说明: 启动微信并搜索指定好友，适用于快速定位联系人
            分类: 社交
            参数: keyword | 文本 | 必填 | 搜索关键词 | 小明
            守卫: 允许应用 = com.tencent.mm
            守卫: 总超时 = 60
            ---
            打开 微信
            点 搜索|Search
            输入 {{keyword}}
            回车
        """.trimIndent()

        val result = SkillDsl.parse(text)
        assertTrue(result.issues.none { it.isError })
        val def = result.definition!!
        assertEquals("微信搜索好友", def.displayName)
        assertEquals("skill_wechat_search", def.key)
        assertEquals(1, def.parameters.size)
        assertTrue(def.parameters.first().required)
        assertEquals(listOf("com.tencent.mm"), def.guard.allowedPackages)
        assertEquals(60_000L, def.guard.maxDurationMs)
        assertEquals(4, def.steps.size)
        assertEquals(SkillActionKind.LAUNCH_APP, def.steps[0].kind)
        assertEquals(SkillActionKind.TAP_ELEMENT, def.steps[1].kind)
        assertEquals(SkillActionKind.INPUT_TEXT, def.steps[2].kind)
        assertEquals("enter", def.steps[3].target)
        assertEquals("step_4", def.steps[3].id)
    }

    @Test
    fun `dsl reports unknown lines with their line number`() {
        val text = "名称: x\n---\n打开 微信\n这一行看不懂\n回车"
        val result = SkillDsl.parse(text)
        val issue = result.issues.first { it.code == "dsl_action_unknown" }
        assertEquals(4, issue.line)
    }

    @Test
    fun `dsl supports condition prefix and failure policy suffix`() {
        val text = """
            ---
            如果 {{mode}} == "fast" 则 点 搜索
            点 确定（重试3）
            回车（失败继续）
        """.trimIndent()
        val result = SkillDsl.parse(
            text,
            SkillDefinition(parameters = listOf(SkillParameter(key = "mode"))),
        )
        assertTrue(result.issues.none { it.isError })
        val steps = result.definition!!.steps
        assertEquals("{{mode}} == \"fast\"", steps[0].condition)
        assertEquals(SkillErrorPolicy.RETRY, steps[1].onError)
        assertEquals(3, steps[1].maxAttempts)
        assertEquals(SkillErrorPolicy.CONTINUE, steps[2].onError)
        // 括号里的策略标签不能污染目标文本
        assertEquals("确定", steps[1].target)
    }

    @Test
    fun `dsl round trips through render`() {
        val original = SkillDefinition(
            key = "skill_round_trip",
            displayName = "往返测试",
            description = "验证渲染与解析能够无损往返的技能定义",
            parameters = listOf(
                SkillParameter(key = "keyword", label = "关键词", required = true, example = "abc"),
            ),
            steps = listOf(
                SkillStep(id = "step_1", kind = SkillActionKind.LAUNCH_APP, target = "微信"),
                SkillStep(id = "step_2", kind = SkillActionKind.TAP_ELEMENT, target = "搜索", exactMatch = true),
                SkillStep(id = "step_3", kind = SkillActionKind.INPUT_TEXT, target = "{{keyword}}", clearFirst = true),
                SkillStep(id = "step_4", kind = SkillActionKind.WAIT, durationMs = 800),
                SkillStep(id = "step_5", kind = SkillActionKind.SCROLL_UNTIL_FOUND, target = "确认", tapAfter = false),
            ),
            guard = SkillGuard(confirmBeforeRun = true, allowedPackages = listOf("com.tencent.mm")),
        )

        val rendered = SkillDsl.render(original)
        val parsed = SkillDsl.parse(rendered, original)
        assertTrue(parsed.issues.none { it.isError })

        val def = parsed.definition!!
        assertEquals(original.displayName, def.displayName)
        assertEquals(original.key, def.key)
        assertEquals(original.steps.size, def.steps.size)
        assertEquals(original.steps.map { it.kind }, def.steps.map { it.kind })
        assertEquals(original.steps.map { it.target }, def.steps.map { it.target })
        assertTrue(def.steps[1].exactMatch)
        assertTrue(def.steps[2].clearFirst)
        assertEquals(800L, def.steps[3].durationMs)
        assertFalse(def.steps[4].tapAfter)
        assertTrue(def.guard.confirmBeforeRun)
        assertEquals(listOf("com.tencent.mm"), def.guard.allowedPackages)
    }

    @Test
    fun `dsl rejects shell step unless guard allows it`() {
        val result = SkillDsl.parse("名称: x\n---\n执行命令 id")
        val def = result.definition!!
        assertTrue("含 shell 的速记应自动开启 allowShell", def.guard.allowShell)
        // 仍会提醒用户这是高危动作
        assertTrue(SkillValidator.validate(def).any { it.code == "shell_confirm" })
    }
}
