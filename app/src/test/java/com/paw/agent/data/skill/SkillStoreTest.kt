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

    @Test fun `built in skills can be copied repeatedly and survive reload`() = runTest {
        val dir = temp.newFolder()
        val store = SkillStore(scope = backgroundScope, storageDirectory = dir)
        store.awaitLoaded()
        val preset = com.paw.agent.core.skill.SkillPresets.builtIns.first()
        val first = requireNotNull(store.duplicate(preset.id))
        val second = requireNotNull(store.duplicate(preset.id))
        assertTrue(first.id != preset.id && second.id != first.id)
        assertTrue(first.key != preset.key && second.key != first.key)
        assertTrue(!first.builtIn)
        assertEquals(preset.steps, first.steps)
        val reloaded = SkillStore(scope = backgroundScope, storageDirectory = dir)
        reloaded.awaitLoaded()
        assertEquals(setOf(first.key, second.key), reloaded.skills.value.map { it.key }.toSet())
    }

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

    @Test fun `import budgets reject huge deep and excessive payloads before mutation`() = runTest {
        val store = SkillStore(scope = backgroundScope, storageDirectory = temp.newFolder())
        store.awaitLoaded()
        for (payload in listOf(" ".repeat(1024 * 1024 + 1), "[".repeat(65) + "0" + "]".repeat(65),
            "[" + List(201) { "{}" }.joinToString(",") + "]")) {
            val result = store.importJson(payload)
            assertTrue(result.errors.isNotEmpty())
            assertTrue(store.skills.value.isEmpty())
        }
    }

    @Test fun `import downgrades sensitive override and invalid definitions cannot be saved`() = runTest {
        val store = SkillStore(scope = backgroundScope, storageDirectory = temp.newFolder())
        store.awaitLoaded()
        val def = com.paw.agent.core.skill.SkillPresets.builtIns.first().copy(
            guard = com.paw.agent.core.skill.SkillGuard(allowSensitiveScreen = true))
        val result = store.importJson(store.exportJson(listOf(def)))
        assertEquals(1, result.imported.size)
        assertTrue(result.warnings.isNotEmpty())
        assertTrue(!result.imported.single().guard.allowSensitiveScreen)
        assertTrue(runCatching { store.save(def.copy(key = "bad key")) }.isFailure)
        store.deleteAll()
        assertTrue(store.skills.value.isEmpty())
    }


    @Test fun `save before initial loading preserves existing disk skills`() = runTest {
        val dir = temp.newFolder()
        val old = com.paw.agent.core.skill.SkillPresets.blankTemplate().copy(id = "old", key = "skill_old")
        File(dir, "skills_v2.json").writeText(kotlinx.serialization.json.Json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(SkillDefinition.serializer()), listOf(old)))
        val store = SkillStore(scope = backgroundScope, storageDirectory = dir)
        store.save(old.copy(id = "new", key = "skill_new"))
        assertEquals(setOf("skill_old", "skill_new"), store.skills.value.map { it.key }.toSet())
    }

    @Test fun `failed import is atomic so correcting and retrying cannot duplicate valid entries`() = runTest {
        val store = SkillStore(scope = backgroundScope, storageDirectory = temp.newFolder())
        val valid = com.paw.agent.core.skill.SkillPresets.blankTemplate().copy(key = "skill_first")
        val invalid = valid.copy(key = "bad key")
        val failed = store.importJson(store.exportJson(listOf(valid, invalid)))
        assertTrue(failed.errors.isNotEmpty())
        assertTrue(failed.imported.isEmpty())
        assertTrue(store.skills.value.isEmpty())
        val retried = store.importJson(store.exportJson(listOf(valid, invalid.copy(key = "skill_second"))))
        assertEquals(2, retried.imported.size)
        assertEquals(setOf("skill_first", "skill_second"), store.skills.value.map { it.key }.toSet())
    }

    @Test fun `saving a duplicate user key cannot shadow another skill`() = runTest {
        val store = SkillStore(scope = backgroundScope, storageDirectory = temp.newFolder())
        val saved = store.save(com.paw.agent.core.skill.SkillPresets.blankTemplate())
        assertTrue(runCatching { store.save(saved.copy(id = "another-id")) }.isFailure)
        assertEquals(listOf(saved), store.skills.value)
    }

    @Test fun `import size budget measures UTF8 bytes rather than character count`() = runTest {
        val store = SkillStore(scope = backgroundScope, storageDirectory = temp.newFolder())
        val result = store.importJson("\"" + "界".repeat(400_000) + "\"")
        assertTrue(result.errors.single().contains("1 MB"))
    }

}
