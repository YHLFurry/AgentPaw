package com.paw.agent.data.skill

import com.paw.agent.core.skill.SkillActionKind
import com.paw.agent.core.skill.SkillDefinition
import com.paw.agent.core.skill.SkillStep
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SkillStoreTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `failed save preserves existing skills and reports failure`() = runTest {
        val dir = temp.newFolder()
        val store = SkillStore(scope = backgroundScope, storageDirectory = dir)
        store.awaitLoaded()
        val original = store.save(SkillDefinition(
            key = "skill_test", displayName = "测试技能", description = "用于持久化回归测试的技能",
            steps = listOf(SkillStep(kind = SkillActionKind.WAIT)),
        ))
        val target = File(dir, "skills_v2.json")
        assertTrue(target.delete())
        assertTrue(target.mkdir())
        File(target, "blocker").writeText("prevent replacement")
        val result = runCatching { store.save(original.copy(displayName = "未保存的改动")) }
        assertTrue(result.isFailure)
        assertEquals(original, store.skills.value.single())
        assertTrue(store.loadError.value.orEmpty().contains("保存失败"))
        assertTrue(dir.listFiles().orEmpty().none { it.extension == "tmp" })
    }
}
