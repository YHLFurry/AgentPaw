package com.paw.agent.data.skill

import android.content.Context
import com.paw.agent.core.skill.CustomExecutableSkill
import com.paw.agent.core.skill.CustomSkillDefinition
import com.paw.agent.core.skill.SkillActionStep
import com.paw.agent.core.skill.SkillActionType
import com.paw.agent.core.skill.SkillRegistry
import com.paw.agent.core.skill.SkillToolAdapter
import com.paw.agent.device.PhoneController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * 用户自定义技能仓库，负责持久化、增删改查以及动态热加载到运行期工具注册表。
 */
class CustomSkillRepository(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val storageFile = File(File(context.filesDir, "skills").apply { mkdirs() }, "custom_skills.json")
    private val mutex = Mutex()

    private val _skills = MutableStateFlow<List<CustomSkillDefinition>>(emptyList())
    val skills: StateFlow<List<CustomSkillDefinition>> = _skills.asStateFlow()

    private val isLoaded = kotlinx.coroutines.CompletableDeferred<Unit>()

    init {
        scope.launch {
            loadInitial()
        }
    }

    suspend fun awaitLoaded() {
        isLoaded.await()
    }

    private suspend fun loadInitial() = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                val list = if (storageFile.exists()) {
                    runCatching {
                        json.decodeFromString<List<CustomSkillDefinition>>(storageFile.readText())
                    }.getOrElse { e ->
                        // 备份损坏文件，避免静默丢失
                        runCatching {
                            val corruptFile = File(storageFile.parentFile, "custom_skills_${System.currentTimeMillis()}.corrupt")
                            storageFile.copyTo(corruptFile, overwrite = true)
                            storageFile.delete()
                            cleanOldCorruptFiles(storageFile.parentFile, maxCount = 3)
                        }
                        val presets = defaultPresets()
                        persistLockedInternal(presets)
                        presets
                    }
                } else {
                    val presets = defaultPresets()
                    persistLockedInternal(presets)
                    presets
                }
                _skills.value = list
            } finally {
                if (!isLoaded.isCompleted) {
                    isLoaded.complete(Unit)
                }
            }
        }
    }

    private fun cleanOldCorruptFiles(dir: File?, maxCount: Int) {
        if (dir == null || !dir.exists()) return
        val corruptFiles = dir.listFiles { f -> f.name.startsWith("custom_skills_") && f.name.endsWith(".corrupt") } ?: return
        if (corruptFiles.size > maxCount) {
            corruptFiles.sortedByDescending { it.lastModified() }
                .drop(maxCount)
                .forEach { it.delete() }
        }
    }

    suspend fun saveSkill(skill: CustomSkillDefinition) = mutex.withLock {
        val current = _skills.value.toMutableList()
        val index = current.indexOfFirst { it.id == skill.id }
        if (index >= 0) {
            current[index] = skill
        } else {
            current.add(skill)
        }
        _skills.value = current
        persistLocked(current)
    }

    suspend fun deleteSkill(id: String) = mutex.withLock {
        val current = _skills.value.filterNot { it.id == id }
        _skills.value = current
        persistLocked(current)
    }

    suspend fun toggleSkill(id: String, enabled: Boolean) = mutex.withLock {
        val current = _skills.value.map {
            if (it.id == id) it.copy(enabled = enabled) else it
        }
        _skills.value = current
        persistLocked(current)
    }

    private suspend fun persistLocked(list: List<CustomSkillDefinition>) = withContext(Dispatchers.IO) {
        persistLockedInternal(list)
    }

    private fun persistLockedInternal(list: List<CustomSkillDefinition>) {
        runCatching {
            val tempFile = File.createTempFile("skills_", ".tmp", storageFile.parentFile)
            tempFile.writeText(json.encodeToString(list))
            if (!tempFile.renameTo(storageFile)) {
                tempFile.copyTo(storageFile, overwrite = true)
                tempFile.delete()
            }
        }
    }

    /**
     * 将已启用的自定义技能动态同步注入到指定的 [SkillRegistry] 中
     */
    fun syncToSkillRegistry(
        skillRegistry: SkillRegistry,
        toolRegistry: com.paw.agent.core.agent.ToolRegistry,
        phoneController: PhoneController,
    ) {
        scope.launch {
            awaitLoaded()
            val activeSkills = _skills.value.filter { it.enabled }
            activeSkills.forEach { def ->
                val executable = CustomExecutableSkill(def)
                skillRegistry.register(executable)
                toolRegistry.register(SkillToolAdapter(executable, phoneController))
            }
        }
    }

    private fun defaultPresets(): List<CustomSkillDefinition> = listOf(
        CustomSkillDefinition(
            id = "preset_wechat_search",
            name = "skill_wechat_quick_search",
            displayName = "微信快速搜索好友",
            description = "一键调起微信并进入搜索框，查找指定的好友或群聊",
            parametersSchema = """{"type":"object","properties":{"keyword":{"type":"string","description":"搜索关键词或好友名字"}},"required":["keyword"]}""",
            actions = listOf(
                SkillActionStep(SkillActionType.LAUNCH_APP, target = "com.tencent.mm", waitMillis = 1500L, description = "启动微信"),
                SkillActionStep(SkillActionType.TAP_ELEMENT, target = "搜索", waitMillis = 600L, description = "点击搜索按钮"),
                SkillActionStep(SkillActionType.INPUT_TEXT, target = "{{keyword}}", waitMillis = 500L, description = "输入好友姓名"),
            ),
            enabled = true,
        ),
        CustomSkillDefinition(
            id = "preset_clean_and_home",
            name = "skill_clean_recents_and_home",
            displayName = "清理后台并返回主屏",
            description = "进入多任务后台并回到手机主桌面",
            parametersSchema = """{"type":"object","properties":{}}""",
            actions = listOf(
                SkillActionStep(SkillActionType.PRESS_KEY, target = "recents", waitMillis = 700L, description = "呼出多任务管理"),
                SkillActionStep(SkillActionType.SWIPE, x = 500, y = 700, waitMillis = 600L, description = "清除最近应用卡片"),
                SkillActionStep(SkillActionType.PRESS_KEY, target = "home", waitMillis = 400L, description = "返回手机主屏幕"),
            ),
            enabled = true,
        ),
        CustomSkillDefinition(
            id = "preset_navigation_quick",
            name = "skill_quick_navigation",
            displayName = "地图一键导航",
            description = "调起高德或百度地图搜索目的地并准备导航",
            parametersSchema = """{"type":"object","properties":{"destination":{"type":"string","description":"目的地地点名称"}},"required":["destination"]}""",
            actions = listOf(
                SkillActionStep(SkillActionType.LAUNCH_APP, target = "高德地图", waitMillis = 2000L, description = "启动地图应用"),
                SkillActionStep(SkillActionType.TAP_ELEMENT, target = "搜索", waitMillis = 700L, description = "点击搜索栏"),
                SkillActionStep(SkillActionType.INPUT_TEXT, target = "{{destination}}", waitMillis = 600L, description = "输入目的地"),
                SkillActionStep(SkillActionType.PRESS_KEY, target = "enter", waitMillis = 1000L, description = "提交搜索"),
            ),
            enabled = false,
        ),
    )
}
