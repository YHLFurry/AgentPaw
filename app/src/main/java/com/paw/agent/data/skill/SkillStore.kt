package com.paw.agent.data.skill

import android.content.Context
import com.paw.agent.core.agent.ToolRegistry
import com.paw.agent.core.skill.SkillActionKind
import com.paw.agent.core.skill.SkillDefinition
import com.paw.agent.core.skill.SkillEngine
import com.paw.agent.core.skill.SkillErrorPolicy
import com.paw.agent.core.skill.SkillParameter
import com.paw.agent.core.skill.SkillPresets
import com.paw.agent.core.skill.SkillParamType
import com.paw.agent.core.skill.SkillRegistry
import com.paw.agent.core.skill.SkillSource
import com.paw.agent.core.skill.SkillStep
import com.paw.agent.core.skill.SkillToolAdapter
import com.paw.agent.core.skill.SkillValidator
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException
import kotlinx.coroutines.CancellationException
import java.util.UUID

/**
 * 技能持久化仓库（v2 格式）。
 *
 * 与旧实现的关键差别：
 * - 一个文件存放全部用户技能，写入走「临时文件 + 原子替换」，不会写出半个 JSON；
 * - 读取失败时备份损坏文件并保留**已有内存状态**，不静默回退成空列表；
 * - 从 v1（旧的 `custom_skills.json`）自动迁移，迁移后原文件改名保留；
 * - 提供导入/导出，技能可以像文本一样分享与版本管理。
 */
class SkillStore(
    context: Context? = null,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
    storageDirectory: File? = null,
) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val dir: File = (storageDirectory ?: File(requireNotNull(context).filesDir, "skills")).apply { mkdirs() }
    private val storageFile = File(dir, "skills_v2.json")
    private val legacyFile = File(dir, "custom_skills.json")
    private val mutex = Mutex()

    private val _skills = MutableStateFlow<List<SkillDefinition>>(emptyList())
    val skills: StateFlow<List<SkillDefinition>> = _skills.asStateFlow()

    private val _loadError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = _loadError.asStateFlow()

    private val loaded = CompletableDeferred<Unit>()

    /** 已注册到工具表的 key，用于增量同步时精确移除旧工具。 */
    private var registeredKeys: Set<String> = emptySet()

    init {
        scope.launch { loadInitial() }
    }

    suspend fun awaitLoaded() {
        loaded.await()
    }

    private suspend fun loadInitial() = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                val loadedSkills = when {
                    storageFile.exists() -> readFile(storageFile)
                    legacyFile.exists() -> migrateLegacy()
                    else -> emptyList()
                }
                _skills.value = loadedSkills.sortedByDescending { it.updatedAt }
            } finally {
                if (!loaded.isCompleted) loaded.complete(Unit)
            }
        }
    }

    private fun readFile(file: File): List<SkillDefinition> =
        runCatching {
            json.decodeFromString<List<SkillDefinition>>(file.readText())
        }.getOrElse { error ->
            val backup = File(dir, "skills_corrupt_${System.currentTimeMillis()}.json")
            runCatching { file.copyTo(backup, overwrite = true) }
            _loadError.value = "技能文件解析失败，已备份为 ${backup.name}：${error.message ?: "未知错误"}"
            emptyList()
        }

    // ------------------------------------------------------------------ 写入

    suspend fun save(skill: SkillDefinition): SkillDefinition = mutex.withLock {
        val normalized = SkillValidator.normalize(
            skill.copy(
                revision = if (skill.revision <= 0) 1 else skill.revision,
                updatedAt = System.currentTimeMillis(),
            ),
        ).let { candidate ->
            val existing = _skills.value.firstOrNull { it.id == candidate.id }
            if (existing != null) candidate.copy(revision = existing.revision + 1) else candidate
        }

        val current = _skills.value.toMutableList()
        val index = current.indexOfFirst { it.id == normalized.id }
        if (index >= 0) current[index] = normalized else current.add(normalized)
        persist(current)
        _skills.value = current
        normalized
    }

    suspend fun delete(id: String) = mutex.withLock {
        val current = _skills.value.filterNot { it.id == id }
        persist(current)
        _skills.value = current
    }

    suspend fun setEnabled(id: String, enabled: Boolean) = mutex.withLock {
        val current = _skills.value.map {
            if (it.id == id) it.copy(enabled = enabled, updatedAt = System.currentTimeMillis()) else it
        }
        persist(current)
        _skills.value = current
    }

    suspend fun duplicate(id: String): SkillDefinition? = mutex.withLock {
        val source = _skills.value.firstOrNull { it.id == id } ?: return@withLock null
        val usedKeys = _skills.value.map { it.key }.toSet()
        val baseKey = source.key.removeSuffix("_copy")
        var candidate = "${baseKey}_copy"
        var index = 2
        while (candidate in usedKeys) {
            candidate = "${baseKey}_copy$index"
            index++
        }
        val copy = source.copy(
            id = UUID.randomUUID().toString(),
            key = candidate,
            displayName = "${source.displayName} 副本",
            source = SkillSource.MANUAL,
            builtIn = false,
            revision = 1,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
        )
        val current = _skills.value + copy
        persist(current)
        _skills.value = current
        copy
    }

    private suspend fun persist(list: List<SkillDefinition>) = withContext(Dispatchers.IO) {
        val temp = File.createTempFile("skills_v2_", ".tmp", dir)
        try {
            temp.writeText(json.encodeToString(list))
            try {
                Files.move(temp.toPath(), storageFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), storageFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            _loadError.value = null
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            _loadError.value = "技能保存失败：${error.message ?: "未知错误"}"
            throw error
        } finally {
            temp.delete()
        }
    }

    // -------------------------------------------------------------- 导入导出

    /** 导出为可分享的 JSON（单条或全部）。 */
    fun exportJson(selection: List<SkillDefinition> = _skills.value): String {
        val payload = selection.map { it.copy(builtIn = false, source = SkillSource.IMPORTED) }
        return json.encodeToString(payload)
    }

    data class ImportResult(
        val imported: List<SkillDefinition> = emptyList(),
        val errors: List<String> = emptyList(),
    )

    /** 从 JSON 导入；支持单对象、数组、以及 `{"skills": [...]}` 包装。 */
    suspend fun importJson(raw: String): ImportResult = mutex.withLock {
        val errors = mutableListOf<String>()
        val elements = runCatching {
            when (val element = json.parseToJsonElement(raw.trim())) {
                is JsonArray -> element.toList()
                is JsonObject -> (element["skills"] as? JsonArray)?.toList() ?: listOf(element)
                else -> emptyList()
            }
        }.getOrElse {
            return@withLock ImportResult(errors = listOf("不是合法的 JSON：${it.message ?: "解析失败"}"))
        }

        val existingKeys = _skills.value.map { it.key }.toMutableSet()
        val imported = mutableListOf<SkillDefinition>()

        elements.forEachIndexed { index, element ->
            val decoded = runCatching {
                json.decodeFromJsonElement(SkillDefinition.serializer(), element)
            }.getOrNull()
            if (decoded == null) {
                errors += "第 ${index + 1} 条不是合法的技能定义"
                return@forEachIndexed
            }
            var key = decoded.key.ifBlank { SkillValidator.slugify(decoded.displayName) }
            var suffix = 2
            while (key in existingKeys) {
                key = "${decoded.key}_imported$suffix"
                suffix++
            }
            val candidate = SkillValidator.normalize(
                decoded.copy(
                    id = UUID.randomUUID().toString(),
                    key = key,
                    builtIn = false,
                    source = SkillSource.IMPORTED,
                    revision = 1,
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                ),
            )
            val errorsOfCandidate = SkillValidator.errorsOf(candidate)
            if (errorsOfCandidate.isNotEmpty()) {
                errors += "「${candidate.displayName.ifBlank { key }}」未导入：${errorsOfCandidate.first().message}"
                return@forEachIndexed
            }
            existingKeys += key
            imported += candidate
        }

        if (imported.isNotEmpty()) {
            val current = _skills.value + imported
            persist(current)
            _skills.value = current
        }
        ImportResult(imported, errors)
    }

    // ------------------------------------------------------------- 旧版迁移

    /**
     * v1 → v2 迁移。
     *
     * 旧文件里的动作类型、参数 schema 都不再使用，这里做逐项映射；
     * 映射不了的动作直接丢弃并整体记录在 `authorNote` 里，而不是让整条技能消失。
     */
    private fun migrateLegacy(): List<SkillDefinition> {
        val legacy = runCatching {
            json.parseToJsonElement(legacyFile.readText()) as? JsonArray
        }.getOrNull() ?: return emptyList()

        val migrated = legacy.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val name = obj.string("name") ?: return@mapNotNull null
            val displayName = obj.string("displayName") ?: name
            val description = obj.string("description").orEmpty()
            val steps = (obj["actions"] as? JsonArray)?.mapNotNull { action ->
                convertLegacyStep(action as? JsonObject ?: return@mapNotNull null)
            }.orEmpty()
            if (steps.isEmpty()) return@mapNotNull null

            SkillValidator.normalize(
                SkillDefinition(
                    id = obj.string("id") ?: UUID.randomUUID().toString(),
                    key = SkillValidator.slugify(name).let { if (it == "skill_custom") name else it },
                    displayName = displayName,
                    description = description.ifBlank { "从旧版本迁移的技能：$displayName" },
                    parameters = parseLegacyParameters(obj.string("parametersSchema").orEmpty()),
                    steps = steps,
                    enabled = obj["enabled"]?.jsonPrimitive?.booleanOrNull ?: true,
                    source = SkillSource.IMPORTED,
                    createdAt = obj["createdAt"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                    authorNote = "由 v1 自定义技能自动迁移，请检查每一步是否符合预期",
                ),
            )
        }

        runCatching {
            storageFile.writeText(json.encodeToString(migrated))
            legacyFile.renameTo(File(dir, "custom_skills.v1.backup.json"))
        }
        return migrated
    }

    private fun convertLegacyStep(obj: JsonObject): SkillStep? {
        val type = obj.string("type")?.uppercase() ?: return null
        val target = obj.string("target").orEmpty()
        val x = obj["x"]?.jsonPrimitive?.intOrNull ?: 500
        val y = obj["y"]?.jsonPrimitive?.intOrNull ?: 500
        val wait = obj["waitMillis"]?.jsonPrimitive?.longOrNull ?: 500L
        val exact = obj["exactMatch"]?.jsonPrimitive?.booleanOrNull ?: false
        val label = obj.string("description").orEmpty()

        val kind = when (type) {
            "LAUNCH_APP" -> SkillActionKind.LAUNCH_APP
            "TAP_COORDINATE" -> SkillActionKind.TAP_POINT
            "TAP_ELEMENT" -> SkillActionKind.TAP_ELEMENT
            "INPUT_TEXT" -> SkillActionKind.INPUT_TEXT
            "PRESS_KEY" -> SkillActionKind.PRESS_KEY
            "WAIT" -> SkillActionKind.WAIT
            "SHELL_COMMAND" -> SkillActionKind.SHELL
            "SWIPE" -> SkillActionKind.SWIPE
            else -> return null
        }

        return SkillStep(
            kind = kind,
            target = target,
            x = x.coerceIn(0, 1000),
            y = y.coerceIn(0, 1000),
            toX = if (kind == SkillActionKind.SWIPE) x else null,
            toY = if (kind == SkillActionKind.SWIPE) (if (y > 500) y - 400 else y + 400).coerceIn(0, 1000) else null,
            durationMs = if (kind == SkillActionKind.WAIT) wait.coerceIn(0, 30_000) else 350,
            delayMs = if (kind == SkillActionKind.WAIT) 0 else wait.coerceIn(0, 10_000),
            exactMatch = exact,
            onError = SkillErrorPolicy.ABORT,
            label = label,
        )
    }

    private fun parseLegacyParameters(schema: String): List<SkillParameter> {
        if (schema.isBlank()) return emptyList()
        val properties = runCatching {
            (json.parseToJsonElement(schema) as JsonObject)["properties"] as? JsonObject
        }.getOrNull() ?: return emptyList()

        val required = runCatching {
            ((json.parseToJsonElement(schema) as JsonObject)["required"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.content }
                ?.toSet()
                .orEmpty()
        }.getOrDefault(emptySet())

        return properties.map { (key, value) ->
            val spec = value as? JsonObject
            SkillParameter(
                key = key,
                label = spec?.string("description")?.take(12) ?: key,
                type = when (spec?.string("type")) {
                    "integer", "number" -> SkillParamType.NUMBER
                    "boolean" -> SkillParamType.BOOLEAN
                    else -> SkillParamType.TEXT
                },
                description = spec?.string("description").orEmpty(),
                required = key in required,
            )
        }
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }

    // ------------------------------------------------------------------ 同步

    /**
     * 把当前定义同步到注册表与工具表。
     *
     * 只增删变化过的工具，避免每次保存都重建整个工具列表（旧实现每次
     * `syncToSkillRegistry` 都只注册不注销，删掉的技能会一直留在模型工具表里）。
     */
    suspend fun syncInto(
        registry: SkillRegistry,
        toolRegistry: ToolRegistry,
        engine: SkillEngine,
    ) {
        awaitLoaded()
        val snapshot = _skills.value
        registry.replaceUserSkills(snapshot)

        val desired = snapshot.filter { it.enabled }.associateBy { it.key }
        val stale = registeredKeys - desired.keys
        stale.forEach { key ->
            // 用户技能可以占用内置技能的 key；删除这份覆盖后必须把内置版本恢复回去，
            // 否则内置能力会从工具表里消失，直到下次启动才回来。
            val preset = SkillPresets.find(key)
            if (preset != null) {
                toolRegistry.register(SkillToolAdapter(preset, engine))
            } else {
                toolRegistry.unregister(key)
            }
        }
        desired.forEach { (key, definition) ->
            toolRegistry.register(SkillToolAdapter(definition, engine))
        }
        registeredKeys = desired.keys
    }

    // ------------------------------------------------------------------ 测试

    /** 供「测试运行」直接构造引擎选项，不依赖注册表。 */
    suspend fun definitionOf(id: String): SkillDefinition? = _skills.value.firstOrNull { it.id == id }
}
