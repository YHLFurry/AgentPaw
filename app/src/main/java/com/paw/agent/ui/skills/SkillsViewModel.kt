package com.paw.agent.ui.skills

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.paw.agent.core.agent.AgentContext
import com.paw.agent.core.llm.LlmClient
import com.paw.agent.core.llm.LlmConfig
import com.paw.agent.core.skill.SkillActionKind
import com.paw.agent.core.skill.SkillCatalog
import com.paw.agent.core.skill.SkillDefinition
import com.paw.agent.core.skill.SkillDsl
import com.paw.agent.core.skill.SkillEngine
import com.paw.agent.core.skill.SkillIssue
import com.paw.agent.core.skill.SkillIssueSeverity
import com.paw.agent.core.skill.SkillPresets
import com.paw.agent.core.skill.SkillRegistry
import com.paw.agent.core.skill.SkillRisk
import com.paw.agent.core.skill.SkillRunResult
import com.paw.agent.core.skill.SkillRunStatus
import com.paw.agent.core.skill.SkillSource
import com.paw.agent.core.skill.SkillValidator
import com.paw.agent.core.skill.SkillWishComposer
import com.paw.agent.core.skill.SkillWishResult
import com.paw.agent.data.skill.SkillStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class SkillEditorMode {
    /** 速记文本：一行一个动作。 */
    SIMPLE,

    /** 完整 JSON：参数、守卫、条件、错误策略全都能改。 */
    ADVANCED,
}

data class SkillsUiState(
    val skills: List<SkillDefinition> = emptyList(),
    val builtIns: List<SkillDefinition> = SkillPresets.builtIns,
    val query: String = "",
    val loadError: String? = null,
    val editing: SkillDefinition? = null,
    val editorMode: SkillEditorMode = SkillEditorMode.SIMPLE,
    val dslText: String = "",
    val jsonText: String = "",
    val issues: List<SkillIssue> = emptyList(),
    val testRunning: Boolean = false,
    val testResult: SkillRunResult? = null,
    val wishText: String = "",
    val wishRunning: Boolean = false,
    val wishError: String? = null,
    val wishPreview: SkillDefinition? = null,
    val wishIssues: List<SkillIssue> = emptyList(),
    val wishRepaired: Boolean = false,
    val message: String? = null,
) {
    val visibleSkills: List<SkillDefinition>
        get() = if (query.isBlank()) {
            skills
        } else {
            val q = query.trim()
            skills.filter {
                it.displayName.contains(q, ignoreCase = true) ||
                    it.description.contains(q, ignoreCase = true) ||
                    it.tags.any { tag -> tag.contains(q, ignoreCase = true) } ||
                    it.key.contains(q, ignoreCase = true)
            }
        }

    val editingIssues: List<SkillIssue>
        get() = issues

    val canSave: Boolean
        get() = editing != null && issues.none { it.isError }

    val editingRisk: SkillRisk
        get() = editing?.let { SkillCatalog.riskOf(it) } ?: SkillRisk.NONE
}

/**
 * 技能中心的全部状态与动作。
 *
 * 编辑器与许愿页共用一个 ViewModel（挂在导航图上），这样子页面来回切换时
 * 草稿不会丢——这是旧实现里「一返回就白写」的直接修复。
 */
class SkillsViewModel(
    private val store: SkillStore,
    private val registry: SkillRegistry,
    private val engine: SkillEngine,
    private val llmClient: LlmClient,
    private val sync: suspend () -> Unit,
) : ViewModel() {

    private val prettyJson = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }
    private val wishComposer = SkillWishComposer(llmClient)

    private val _uiState = MutableStateFlow(SkillsUiState())
    val uiState: StateFlow<SkillsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            store.awaitLoaded()
            _uiState.value = _uiState.value.copy(skills = store.skills.value)
        }
        viewModelScope.launch {
            store.skills.collect { list ->
                _uiState.value = _uiState.value.copy(skills = list)
            }
        }
        viewModelScope.launch {
            store.loadError.collect { error ->
                if (error != null) _uiState.value = _uiState.value.copy(loadError = error)
            }
        }
    }

    // ---------------------------------------------------------------- 列表

    fun onQueryChange(value: String) {
        _uiState.value = _uiState.value.copy(query = value)
    }

    fun toggleEnabled(skill: SkillDefinition) {
        launchStoreAction {
            store.setEnabled(skill.id, !skill.enabled)
            sync()
            notify(if (skill.enabled) "已停止「${skill.displayName}」" else "已启用「${skill.displayName}」")
        }
    }

    fun delete(skill: SkillDefinition) {
        launchStoreAction {
            store.delete(skill.id)
            sync()
            notify("已删除「${skill.displayName}」")
        }
    }

    fun duplicate(skill: SkillDefinition) {
        launchStoreAction {
            val copy = store.duplicate(skill.id)
            sync()
            notify(if (copy != null) "已复制为「${copy.displayName}」" else "复制失败")
        }
    }

    fun exportAll(): String = store.exportJson()

    fun import(text: String, onDone: (Int, List<String>) -> Unit) {
        launchStoreAction {
            val result = store.importJson(text)
            sync()
            onDone(result.imported.size, result.errors)
        }
    }

    // ---------------------------------------------------------------- 编辑

    fun startCreate() {
        val template = SkillPresets.blankTemplate()
        openEditor(template)
    }

    fun edit(skill: SkillDefinition) {
        openEditor(skill.copy())
    }

    /** 从许愿结果进入编辑器继续微调。 */
    fun editWishPreview() {
        val preview = _uiState.value.wishPreview ?: return
        _uiState.value = _uiState.value.copy(wishPreview = null)
        openEditor(preview)
    }

    private fun openEditor(skill: SkillDefinition) {
        val normalized = SkillValidator.normalize(skill)
        _uiState.value = _uiState.value.copy(
            editing = normalized,
            editorMode = SkillEditorMode.SIMPLE,
            dslText = SkillDsl.render(normalized),
            jsonText = prettyJson.encodeToString(SkillDefinition.serializer(), normalized),
            issues = SkillValidator.validate(normalized),
            testResult = null,
        )
    }

    fun closeEditor() {
        _uiState.value = _uiState.value.copy(editing = null, issues = emptyList(), testResult = null)
    }

    fun setEditorMode(mode: SkillEditorMode) {
        val state = _uiState.value
        val editing = state.editing ?: return
        if (state.editorMode == mode) return
        _uiState.value = when (mode) {
            // 切到 JSON 时用当前解析结果重新序列化，保证两边看到的是同一份定义。
            SkillEditorMode.ADVANCED -> state.copy(
                editorMode = mode,
                jsonText = prettyJson.encodeToString(SkillDefinition.serializer(), editing),
                issues = SkillValidator.validate(editing),
            )

            SkillEditorMode.SIMPLE -> state.copy(
                editorMode = mode,
                dslText = SkillDsl.render(editing),
                issues = SkillValidator.validate(editing),
            )
        }
    }

    fun onDslChange(text: String) {
        val base = _uiState.value.editing ?: SkillPresets.blankTemplate()
        val parsed = runCatching { SkillDsl.parse(text, base) }.getOrNull()
        val definition = parsed?.definition ?: base
        val issues = (parsed?.issues ?: emptyList()) + SkillValidator.validate(definition)
        val serializedJson = runCatching {
            prettyJson.encodeToString(SkillDefinition.serializer(), definition)
        }.getOrNull() ?: _uiState.value.jsonText

        _uiState.value = _uiState.value.copy(
            dslText = text,
            editing = definition,
            issues = issues,
            jsonText = serializedJson,
        )
    }

    fun onJsonChange(text: String) {
        val decoded = runCatching {
            prettyJson.decodeFromString(SkillDefinition.serializer(), text)
        }.getOrNull()
        val issues = if (decoded == null) {
            listOf(
                SkillIssue(
                    SkillIssueSeverity.ERROR,
                    "json_invalid",
                    "JSON 解析失败：请检查括号、引号与字段类型",
                ),
            )
        } else {
            SkillValidator.validate(decoded)
        }
        _uiState.value = _uiState.value.copy(
            jsonText = text,
            editing = decoded ?: _uiState.value.editing ?: SkillPresets.blankTemplate(),
            issues = issues,
        )
    }

    /** 高级模式里改完 JSON 后，把结构变更带回到速记文本（切回简易模式时自动发生）。 */
    fun formatJson() {
        val editing = _uiState.value.editing ?: return
        _uiState.value = _uiState.value.copy(
            jsonText = prettyJson.encodeToString(SkillDefinition.serializer(), editing),
        )
    }

    /** 简易模式：在末尾追加一条动作模板。 */
    fun appendDslLine(line: String) {
        val current = _uiState.value.dslText.trimEnd()
        onDslChange("$current\n$line")
    }

    /** 高级模式：结构化修改守卫，改完立刻重新校验并回写 JSON。 */
    fun updateGuard(transform: (com.paw.agent.core.skill.SkillGuard) -> com.paw.agent.core.skill.SkillGuard) {
        val editing = _uiState.value.editing ?: return
        val updated = editing.copy(guard = transform(editing.guard))
        applyStructuredEdit(updated)
    }

    /** 高级模式：启用/禁用单步。 */
    fun toggleStep(stepId: String, enabled: Boolean) {
        val editing = _uiState.value.editing ?: return
        val updated = editing.copy(
            steps = editing.steps.map { if (it.id == stepId) it.copy(enabled = enabled) else it },
        )
        applyStructuredEdit(updated)
    }

    private fun applyStructuredEdit(updated: SkillDefinition) {
        _uiState.value = _uiState.value.copy(
            editing = updated,
            jsonText = prettyJson.encodeToString(SkillDefinition.serializer(), updated),
            dslText = SkillDsl.render(updated),
            issues = SkillValidator.validate(updated),
        )
    }

    fun save(onSaved: (SkillDefinition) -> Unit = {}) {
        val state = _uiState.value
        val draft = state.editing ?: return
        if (state.issues.any { it.isError }) {
            notify("还有 ${state.issues.count { it.isError }} 处错误需要先修正")
            return
        }
        launchStoreAction {
            val normalized = SkillValidator.normalize(
                draft.copy(
                    // 新建技能时 source 保持空白模板的 MANUAL
                    source = if (draft.id.isBlank() || draft.source == SkillSource.PRESET) {
                        SkillSource.MANUAL
                    } else {
                        draft.source
                    },
                    builtIn = false,
                ),
            )
            val saved = store.save(normalized)
            sync()
            notify("已保存「${saved.displayName}」")
            _uiState.value = _uiState.value.copy(editing = null, issues = emptyList())
            onSaved(saved)
        }
    }

    // ---------------------------------------------------------------- 测试

    fun testRun(skill: SkillDefinition) {
        if (_uiState.value.testRunning) return
        _uiState.value = _uiState.value.copy(testRunning = true, testResult = null)
        viewModelScope.launch {
            val arguments = buildJsonObject {
                skill.parameters.forEach { param ->
                    if (param.key.isNotBlank()) {
                        put(param.key, param.defaultValue.ifBlank { param.example })
                    }
                }
            }.toString()
            val result = runCatching {
                engine.run(skill, arguments, AgentContext(conversationId = "skill_test"))
            }.getOrElse { error ->
                SkillRunResult(
                    status = SkillRunStatus.FAILED,
                    skillKey = skill.key,
                    message = "执行异常：${error.message ?: error::class.simpleName}",
                )
            }
            _uiState.value = _uiState.value.copy(testRunning = false, testResult = result)
        }
    }

    fun clearTestResult() {
        _uiState.value = _uiState.value.copy(testResult = null)
    }

    // ---------------------------------------------------------------- 许愿

    fun onWishChange(text: String) {
        _uiState.value = _uiState.value.copy(wishText = text, wishError = null)
    }

    fun clearWishPreview() {
        _uiState.value = _uiState.value.copy(
            wishPreview = null,
            wishIssues = emptyList(),
            wishError = null,
        )
    }

    fun generateFromWish(config: LlmConfig) {
        val state = _uiState.value
        if (state.wishRunning) return
        val intent = state.wishText.trim()
        if (intent.isEmpty()) {
            _uiState.value = state.copy(wishError = "先用一句话描述你想要的技能")
            return
        }
        _uiState.value = state.copy(
            wishRunning = true,
            wishError = null,
            wishPreview = null,
            wishIssues = emptyList(),
        )
        viewModelScope.launch {
            val existingKeys = store.skills.value.map { it.key }.toSet() + SkillPresets.builtInKeys
            when (val result = wishComposer.compose(intent, config, existingKeys)) {
                is SkillWishResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        wishRunning = false,
                        wishPreview = result.definition,
                        wishIssues = result.issues,
                        wishRepaired = result.repaired,
                    )
                }

                is SkillWishResult.Failure -> {
                    _uiState.value = _uiState.value.copy(
                        wishRunning = false,
                        wishError = result.message,
                    )
                }
            }
        }
    }

    fun saveWishPreview() {
        val preview = _uiState.value.wishPreview ?: return
        if (_uiState.value.wishIssues.any { it.isError }) {
            notify("生成的技能还有错误，请先进入编辑器修正")
            return
        }
        launchStoreAction {
            val saved = store.save(
                SkillValidator.normalize(
                    preview.copy(source = SkillSource.WISH, updatedAt = System.currentTimeMillis()),
                ).copy(enabled = true),
            )
            sync()
            _uiState.value = _uiState.value.copy(wishPreview = null, wishText = "")
            notify("「${saved.displayName}」已生成并启用")
        }
    }

    // ---------------------------------------------------------------- 杂项

    fun consumeMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }

    private fun notify(text: String) {
        _uiState.value = _uiState.value.copy(message = text)
    }

    private fun launchStoreAction(action: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notify("技能操作失败：${e.message ?: "请检查存储空间后重试"}")
            }
        }
    }

    /** 给界面展示步骤摘要用。 */
    fun stepSummary(skill: SkillDefinition): String = skill.steps.joinToString(" → ") { step ->
        SkillCatalog.spec(step.kind).label
    }

    /** 当前真正注入给模型的技能工具数量。 */
    fun activeToolCount(): Int = registry.activeCount()

    class Factory(
        private val store: SkillStore,
        private val registry: SkillRegistry,
        private val engine: SkillEngine,
        private val llmClient: LlmClient,
        private val sync: suspend () -> Unit,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SkillsViewModel(store, registry, engine, llmClient, sync) as T
    }
}
