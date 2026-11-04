package com.paw.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.paw.agent.core.skill.SkillInterpolator
import com.paw.agent.core.skill.SkillPresets
import com.paw.agent.core.skill.SkillValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 必须在 Android 上运行：宿主 JVM 的正则引擎会接受导致 Android ICU 初始化崩溃的裸 `}`。 */
@RunWith(AndroidJUnit4::class)
class SkillInterpolatorInstrumentedTest {
    @Test fun creatingSkillValidatesTemplateWithoutInitializerCrash() {
        val template = SkillValidator.normalize(SkillPresets.blankTemplate())
        assertTrue(SkillValidator.validate(template).none { it.isError })
        SkillPresets.builtIns.forEach { skill ->
            assertTrue("Invalid built-in: ${skill.key}", SkillValidator.validate(skill).none { it.isError })
        }
    }

    @Test fun bothPlaceholderFormsFallbackAndDottedNamesWorkWithIcu() {
        val template = "{{ name }} / \${name} / {{missing|备用}} / {{profile.name}}"
        val result = SkillInterpolator.render(template, mapOf("name" to "小明", "profile.name" to "小红"))
        assertEquals("小明 / 小明 / 备用 / 小红", result.text)
        assertFalse(result.hasMissing)
        assertEquals(listOf("name", "name", "missing", "profile.name"),
            SkillInterpolator.referencedVariables(template))
    }

    @Test fun missingVariablesAndLiteralBracesDoNotBreakMatching() {
        val result = SkillInterpolator.render("{literal} {{unknown}} \${other}", emptyMap())
        assertEquals("{literal}  ", result.text)
        assertEquals(listOf("unknown", "other"), result.missing)
        assertTrue(SkillInterpolator.referencedVariables("home").isEmpty())
    }
}
