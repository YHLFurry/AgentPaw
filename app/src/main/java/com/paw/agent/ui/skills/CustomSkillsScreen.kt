package com.paw.agent.ui.skills

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.paw.agent.AppContainer
import com.paw.agent.R
import com.paw.agent.core.agent.AgentContext
import com.paw.agent.core.skill.CustomExecutableSkill
import com.paw.agent.core.skill.CustomSkillDefinition
import com.paw.agent.core.skill.SkillActionStep
import com.paw.agent.core.skill.SkillActionType
import com.paw.agent.ui.components.adaptive.AppButton
import com.paw.agent.ui.components.adaptive.AppCard
import com.paw.agent.ui.components.adaptive.AppIcon
import com.paw.agent.ui.components.adaptive.AppIconButton
import com.paw.agent.ui.components.adaptive.AppOutlinedButton
import com.paw.agent.ui.components.adaptive.AppScaffold
import com.paw.agent.ui.components.adaptive.AppSurface
import com.paw.agent.ui.components.adaptive.AppSwitch
import com.paw.agent.ui.components.adaptive.AppText
import com.paw.agent.ui.components.adaptive.AppTextField
import com.paw.agent.ui.components.adaptive.AppTopAppBar
import com.paw.agent.ui.settings.SettingsCard
import com.paw.agent.ui.settings.SettingsRowDivider
import com.paw.agent.ui.theme.AppTheme
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
fun CustomSkillsScreen(
    container: AppContainer,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val skills by container.customSkillRepository.skills.collectAsStateWithLifecycle()

    var editingSkill by remember { mutableStateOf<CustomSkillDefinition?>(null) }
    var isCreatingNew by remember { mutableStateOf(false) }

    AppScaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            AppTopAppBar(
                title = stringResource(R.string.skills_title),
                navigationIcon = {
                    AppIconButton(onClick = onBack) {
                        AppIcon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                        )
                    }
                },
                actions = {
                    AppIconButton(onClick = {
                        isCreatingNew = true
                        editingSkill = CustomSkillDefinition(
                            id = UUID.randomUUID().toString(),
                            name = "skill_custom_" + System.currentTimeMillis().toString().takeLast(4),
                            displayName = "我的新技能",
                            description = "执行自定义手机自动化操作",
                            actions = listOf(
                                SkillActionStep(type = SkillActionType.PRESS_KEY, target = "home", description = "返回桌面"),
                            ),
                            enabled = true,
                        )
                    }) {
                        AppIcon(
                            imageVector = Icons.Default.Add,
                            contentDescription = stringResource(R.string.skills_create),
                        )
                    }
                },
                color = AppTheme.colors.surfaceContainer,
            )
        },
        containerColor = AppTheme.colors.background,
    ) { innerPadding ->
        if (skills.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                AppText(
                    text = stringResource(R.string.skills_empty),
                    color = AppTheme.colors.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    AppText(
                        text = stringResource(R.string.skills_summary),
                        style = AppTheme.typography.bodySmall,
                        color = AppTheme.colors.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }

                items(skills, key = { it.id }) { skill ->
                    SkillItemCard(
                        skill = skill,
                        onToggle = { enabled ->
                            scope.launch {
                                container.customSkillRepository.toggleSkill(skill.id, enabled)
                                container.customSkillRepository.syncToSkillRegistry(
                                    container.skillRegistry,
                                    container.toolRegistry,
                                    container.phoneController,
                                )
                            }
                        },
                        onEdit = {
                            isCreatingNew = false
                            editingSkill = skill
                        },
                        onDelete = {
                            scope.launch {
                                container.customSkillRepository.deleteSkill(skill.id)
                                container.skillRegistry.unregister(skill.name)
                                container.toolRegistry.unregister(skill.name)
                                Toast.makeText(context, "已删除技能: ${skill.displayName}", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onTestRun = {
                            scope.launch {
                                Toast.makeText(context, "正在测试执行: ${skill.displayName}...", Toast.LENGTH_SHORT).show()
                                val result = withContext(Dispatchers.IO) {
                                    runCatching {
                                        val executable = CustomExecutableSkill(skill)
                                        executable.execute(
                                            arguments = "{}",
                                            phoneController = container.phoneController,
                                            context = AgentContext("test_context", 0),
                                        )
                                    }.getOrElse { "Error: ${it.message ?: "未知异常"}" }
                                }
                                Toast.makeText(context, "执行完成: $result", Toast.LENGTH_LONG).show()
                            }
                        },
                    )
                }
            }
        }

        // 编辑/新建技能对话框
        editingSkill?.let { skillToEdit ->
            SkillEditDialog(
                initial = skillToEdit,
                isNew = isCreatingNew,
                onDismiss = { editingSkill = null },
                onSave = { updated ->
                    scope.launch {
                        container.customSkillRepository.saveSkill(updated)
                        container.customSkillRepository.syncToSkillRegistry(
                            container.skillRegistry,
                            container.toolRegistry,
                            container.phoneController,
                        )
                        Toast.makeText(context, "技能已保存并投入使用！", Toast.LENGTH_SHORT).show()
                        editingSkill = null
                    }
                },
            )
        }
    }
}

@Composable
private fun SkillItemCard(
    skill: CustomSkillDefinition,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onTestRun: () -> Unit,
) {
    AppCard(
        cornerRadius = 16.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    AppIcon(
                        imageVector = Icons.Outlined.Extension,
                        contentDescription = null,
                        tint = AppTheme.colors.primary,
                        modifier = Modifier.size(22.dp),
                    )
                    Column {
                        AppText(
                            text = skill.displayName,
                            style = AppTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        AppText(
                            text = skill.name,
                            style = AppTheme.typography.labelSmall,
                            color = AppTheme.colors.onSurfaceVariant,
                        )
                    }
                }

                AppSwitch(
                    checked = skill.enabled,
                    onCheckedChange = onToggle,
                )
            }

            Spacer(Modifier.height(8.dp))

            AppText(
                text = skill.description,
                style = AppTheme.typography.bodySmall,
                color = AppTheme.colors.onSurfaceVariant,
            )

            Spacer(Modifier.height(10.dp))

            AppText(
                text = "编排步骤数: ${skill.actions.size} 步",
                style = AppTheme.typography.labelSmall,
                color = AppTheme.colors.primary,
            )

            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppIconButton(onClick = onTestRun) {
                    AppIcon(imageVector = Icons.Outlined.PlayCircle, contentDescription = "测试执行")
                }
                AppIconButton(onClick = onEdit) {
                    AppIcon(imageVector = Icons.Default.Edit, contentDescription = "编辑")
                }
                AppIconButton(onClick = onDelete) {
                    AppIcon(imageVector = Icons.Default.Delete, contentDescription = "删除")
                }
            }
        }
    }
}

@Composable
private fun SkillEditDialog(
    initial: CustomSkillDefinition,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (CustomSkillDefinition) -> Unit,
) {
    var name by remember { mutableStateOf(initial.name) }
    var displayName by remember { mutableStateOf(initial.displayName) }
    var description by remember { mutableStateOf(initial.description) }
    val steps = remember { mutableStateListOf(*initial.actions.toTypedArray()) }
    val context = LocalContext.current

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        AppSurface(
            shape = RoundedCornerShape(20.dp),
            color = AppTheme.colors.surfaceContainer,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 24.dp),
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                AppText(
                    text = if (isNew) "创建自定义技能" else "编辑技能",
                    style = AppTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )

                Spacer(Modifier.height(16.dp))

                AppTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = "显示名称",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(10.dp))

                AppTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = "技能唯一标识 (例如 skill_my_action)",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(10.dp))

                AppTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = "技能意图描述 (供 AI 模型识别并调用)",
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppText(
                        text = "动作执行序列 (${steps.size})",
                        style = AppTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    AppOutlinedButton(onClick = {
                        steps.add(
                            SkillActionStep(
                                type = SkillActionType.PRESS_KEY,
                                target = "back",
                                description = "按返回键",
                            ),
                        )
                    }) {
                        AppText("添加步骤")
                    }
                }

                Spacer(Modifier.height(8.dp))

                steps.forEachIndexed { index, step ->
                    StepItemEditor(
                        index = index + 1,
                        step = step,
                        onChange = { updated -> steps[index] = updated },
                        onRemove = { steps.removeAt(index) },
                    )
                    Spacer(Modifier.height(8.dp))
                }

                Spacer(Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppOutlinedButton(onClick = onDismiss) {
                        AppText("取消")
                    }
                    Spacer(Modifier.width(12.dp))
                    AppButton(onClick = {
                        val trimmedName = name.trim()
                        if (trimmedName.isBlank() || !trimmedName.matches(Regex("^[a-zA-Z0-9_]+$"))) {
                            Toast.makeText(context, "技能标识必须由字母、数字或下划线组成且不能为空", Toast.LENGTH_SHORT).show()
                            return@AppButton
                        }
                        onSave(
                            initial.copy(
                                name = trimmedName,
                                displayName = displayName.trim().ifBlank { trimmedName },
                                description = description.trim(),
                                actions = steps.toList(),
                            ),
                        )
                    }) {
                        AppText("保存并投入使用")
                    }
                }
            }
        }
    }
}

@Composable
private fun StepItemEditor(
    index: Int,
    step: SkillActionStep,
    onChange: (SkillActionStep) -> Unit,
    onRemove: () -> Unit,
) {
    AppCard(
        cornerRadius = 12.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppText(
                    text = "第 $index 步: ${step.type.name}",
                    style = AppTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = AppTheme.colors.primary,
                )
                AppIconButton(onClick = onRemove, modifier = Modifier.size(24.dp)) {
                    AppIcon(imageVector = Icons.Default.Delete, contentDescription = "删除此步骤")
                }
            }

            Spacer(Modifier.height(6.dp))

            // 动作类型选择：横向支持所有 8 种技能动作
            androidx.compose.foundation.lazy.LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(SkillActionType.entries.size) { idx ->
                    val actionType = SkillActionType.entries[idx]
                    val isSelected = step.type == actionType
                    AppSurface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isSelected) AppTheme.colors.primaryContainer else AppTheme.colors.surface,
                        modifier = Modifier
                            .clickable { onChange(step.copy(type = actionType)) }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    ) {
                        AppText(
                            text = actionType.name,
                            style = AppTheme.typography.labelSmall,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
            }

            Spacer(Modifier.height(6.dp))

            if (step.type == SkillActionType.TAP_COORDINATE) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AppTextField(
                        value = step.x.toString(),
                        onValueChange = { onChange(step.copy(x = it.toIntOrNull() ?: step.x)) },
                        label = "X (0..1000)",
                        modifier = Modifier.weight(1f),
                    )
                    AppTextField(
                        value = step.y.toString(),
                        onValueChange = { onChange(step.copy(y = it.toIntOrNull() ?: step.y)) },
                        label = "Y (0..1000)",
                        modifier = Modifier.weight(1f),
                    )
                }
            } else if (step.type == SkillActionType.WAIT) {
                AppTextField(
                    value = step.waitMillis.toString(),
                    onValueChange = { onChange(step.copy(waitMillis = it.toLongOrNull() ?: step.waitMillis)) },
                    label = "等待时长 (毫秒)",
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                AppTextField(
                    value = step.target,
                    onValueChange = { onChange(step.copy(target = it)) },
                    label = "参数 / 目标 (支持 {{keyword}} 变量)",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
