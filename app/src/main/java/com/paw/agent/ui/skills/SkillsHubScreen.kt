package com.paw.agent.ui.skills

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.paw.agent.R
import com.paw.agent.core.skill.SkillCatalog
import com.paw.agent.core.skill.SkillDefinition
import com.paw.agent.core.skill.SkillRisk
import com.paw.agent.ui.components.adaptive.AppAlertDialog
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
import com.paw.agent.ui.components.adaptive.AppTopBarAction
import com.paw.agent.ui.components.adaptive.LocalTopBarClearance
import com.paw.agent.ui.theme.AppTheme

/**
 * 技能中心。
 *
 * 三个入口并列而不是藏在菜单里：许愿生成、手写新建、导入既有技能。
 * 列表把「内置技能」与「我的技能」分开，避免用户误删内置能力；
 * 内置技能只能复制，不能编辑——想改就先复制一份。
 */
@Composable
fun SkillsHubScreen(
    viewModel: SkillsViewModel,
    onBack: () -> Unit,
    onOpenEditor: () -> Unit,
    onOpenWish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val listState = rememberLazyListState()

    var pendingDelete by remember { mutableStateOf<SkillDefinition?>(null) }
    var showImport by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf("") }

    LaunchedEffect(state.message) {
        state.message?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.consumeMessage()
        }
    }

    LaunchedEffect(state.loadError) {
        state.loadError?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
    }

    AppScaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            AppTopAppBar(
                title = stringResource(R.string.skills_title),
                subtitle = stringResource(R.string.skills_subtitle_count, state.skills.size),
                isContentScrolled = listState.canScrollBackward,
                navigationIcon = {
                    AppTopBarAction(onClick = onBack) {
                        AppIcon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                        )
                    }
                },
                actions = {
                    AppTopBarAction(onClick = {
                        copyToClipboard(context, viewModel.exportAll())
                        Toast.makeText(context, "已复制全部技能 JSON 到剪贴板", Toast.LENGTH_SHORT).show()
                    }) {
                        AppIcon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = stringResource(R.string.skills_export),
                        )
                    }
                    AppTopBarAction(onClick = { showImport = true }) {
                        AppIcon(
                            imageVector = Icons.Default.Upload,
                            contentDescription = stringResource(R.string.skills_import),
                        )
                    }
                },
                color = AppTheme.colors.surfaceContainer,
            )
        },
        containerColor = AppTheme.colors.background,
    ) { innerPadding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            // 顶栏高度花在 contentPadding 上：首屏内容仍在顶栏下方，滚动时从渐隐带下穿过。
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                top = 12.dp + LocalTopBarClearance.current,
                bottom = 12.dp,
            ),
        ) {
            item {
                WishEntryCard(onClick = onOpenWish)
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AppOutlinedButton(
                        onClick = {
                            viewModel.startCreate()
                            onOpenEditor()
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        AppIcon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(SkillIconSize),
                        )
                        Spacer(Modifier.width(6.dp))
                        AppText(stringResource(R.string.skills_create), style = AppTheme.typography.labelLarge)
                    }
                    AppOutlinedButton(
                        onClick = { showImport = true },
                        modifier = Modifier.weight(1f),
                    ) {
                        AppIcon(
                            imageVector = Icons.Default.Upload,
                            contentDescription = null,
                            modifier = Modifier.size(SkillIconSize),
                        )
                        Spacer(Modifier.width(6.dp))
                        AppText(stringResource(R.string.skills_import), style = AppTheme.typography.labelLarge)
                    }
                }
            }

            state.testResult?.let { result ->
                item {
                    SkillTestResultCard(result = result, onDismiss = viewModel::clearTestResult)
                }
            }

            item {
                AppTextField(
                    value = state.query,
                    onValueChange = viewModel::onQueryChange,
                    placeholder = stringResource(R.string.skills_search_hint),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item {
                TextRow(
                    title = stringResource(R.string.skills_group_mine),
                    trailing = stringResource(R.string.skills_active_tools, viewModel.activeToolCount()),
                )
            }

            if (state.visibleSkills.isEmpty()) {
                item {
                    SkillEmptyHint(
                        if (state.query.isBlank()) {
                            stringResource(R.string.skills_empty)
                        } else {
                            stringResource(R.string.skills_empty_search)
                        },
                    )
                }
            } else {
                items(state.visibleSkills, key = { it.id }) { skill ->
                    UserSkillCard(
                        skill = skill,
                        testing = state.testRunning,
                        onToggle = { viewModel.toggleEnabled(skill) },
                        onEdit = {
                            viewModel.edit(skill)
                            onOpenEditor()
                        },
                        onDuplicate = { viewModel.duplicate(skill) },
                        onDelete = { pendingDelete = skill },
                        onTest = { viewModel.testRun(skill) },
                    )
                }
            }

            item {
                Spacer(Modifier.height(4.dp))
                TextRow(
                    title = stringResource(R.string.skills_group_builtin),
                    trailing = stringResource(R.string.skills_builtin_hint),
                )
            }

            items(state.builtIns, key = { it.key }) { skill ->
                BuiltInSkillCard(
                    skill = skill,
                    onCopy = {
                        viewModel.duplicate(skill)
                        Toast.makeText(context, "已复制为可编辑技能", Toast.LENGTH_SHORT).show()
                    },
                    onTest = { viewModel.testRun(skill) },
                )
            }

            item {
                AppText(
                    text = stringResource(R.string.skills_footer_hint),
                    style = AppTheme.typography.bodySmall,
                    color = AppTheme.colors.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
                )
            }
        }
    }

    pendingDelete?.let { skill ->
        AppAlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { AppText(stringResource(R.string.skills_delete_title)) },
            text = {
                AppText(stringResource(R.string.skills_delete_message, skill.displayName))
            },
            confirmButton = {
                AppButton(onClick = {
                    viewModel.delete(skill)
                    pendingDelete = null
                }) {
                    AppText(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                AppOutlinedButton(onClick = { pendingDelete = null }) {
                    AppText(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (showImport) {
        AppAlertDialog(
            onDismissRequest = { showImport = false },
            title = { AppText(stringResource(R.string.skills_import_title)) },
            text = {
                Column {
                    AppText(
                        text = stringResource(R.string.skills_import_hint),
                        style = AppTheme.typography.bodySmall,
                        color = AppTheme.colors.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    AppTextField(
                        value = importText,
                        onValueChange = { importText = it },
                        placeholder = "{ \"displayName\": ... }",
                        minLines = 5,
                        maxLines = 8,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                AppButton(onClick = {
                    viewModel.import(importText) { count, errors ->
                        val message = if (errors.isEmpty()) {
                            "已导入 $count 条技能"
                        } else {
                            "导入 $count 条，${errors.size} 条失败：${errors.first()}"
                        }
                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                    }
                    importText = ""
                    showImport = false
                }, enabled = importText.isNotBlank()) {
                    AppText(stringResource(R.string.action_import))
                }
            },
            dismissButton = {
                AppOutlinedButton(onClick = { showImport = false }) {
                    AppText(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun TextRow(title: String, trailing: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppText(
            text = title,
            style = AppTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = AppTheme.colors.onSurface,
        )
        AppText(
            text = trailing,
            style = AppTheme.typography.labelSmall,
            color = AppTheme.colors.onSurfaceVariant,
        )
    }
}

/** 「许愿」入口：整个功能最显眼的按钮，因为它是门槛最低的创建方式。 */
@Composable
private fun WishEntryCard(onClick: () -> Unit) {
    AppSurface(
        shape = RoundedCornerShape(18.dp),
        color = AppTheme.colors.primaryContainer,
        contentColor = AppTheme.colors.onPrimaryContainer,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(
                imageVector = Icons.Default.AutoAwesome,
                contentDescription = null,
                tint = AppTheme.colors.onPrimaryContainer,
                modifier = Modifier.size(26.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                AppText(
                    text = stringResource(R.string.skills_wish_entry_title),
                    style = AppTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = AppTheme.colors.onPrimaryContainer,
                )
                AppText(
                    text = stringResource(R.string.skills_wish_entry_subtitle),
                    style = AppTheme.typography.bodySmall,
                    color = AppTheme.colors.onPrimaryContainer,
                )
            }
            AppIcon(
                imageVector = Icons.Outlined.Lightbulb,
                contentDescription = null,
                tint = AppTheme.colors.onPrimaryContainer,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun UserSkillCard(
    skill: SkillDefinition,
    testing: Boolean,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onTest: () -> Unit,
) {
    AppCard(cornerRadius = 16.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppText(text = skill.emoji, style = AppTheme.typography.titleMedium)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AppText(
                            text = skill.displayName.ifBlank { skill.key },
                            style = AppTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = AppTheme.colors.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.width(8.dp))
                        SkillRiskBadge(SkillCatalog.riskOf(skill))
                    }
                    AppText(
                        text = skill.key,
                        style = AppTheme.typography.labelSmall,
                        color = AppTheme.colors.onSurfaceVariant,
                    )
                }
                AppSwitch(checked = skill.enabled, onCheckedChange = { onToggle() })
            }

            Spacer(Modifier.height(8.dp))
            AppText(
                text = skill.description,
                style = AppTheme.typography.bodySmall,
                color = AppTheme.colors.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.height(8.dp))
            AppText(
                text = stringResource(R.string.skills_step_count, skill.steps.size) +
                    " · " + skill.steps.joinToString(" → ") { SkillCatalog.spec(it.kind).emoji },
                style = AppTheme.typography.labelSmall,
                color = AppTheme.colors.primary,
            )

            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppIconButton(onClick = onTest, enabled = !testing) {
                    AppIcon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = stringResource(R.string.skills_test_run),
                    )
                }
                AppIconButton(onClick = onDuplicate) {
                    AppIcon(
                        imageVector = Icons.Outlined.Extension,
                        contentDescription = stringResource(R.string.skills_duplicate),
                    )
                }
                AppIconButton(onClick = onEdit) {
                    AppIcon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = stringResource(R.string.skills_edit),
                    )
                }
                AppIconButton(onClick = onDelete) {
                    AppIcon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = stringResource(R.string.action_delete),
                        tint = AppTheme.colors.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun BuiltInSkillCard(
    skill: SkillDefinition,
    onCopy: () -> Unit,
    onTest: () -> Unit,
) {
    AppCard(cornerRadius = 14.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppText(text = skill.emoji, style = AppTheme.typography.bodyLarge)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    AppText(
                        text = skill.displayName,
                        style = AppTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = AppTheme.colors.onSurface,
                    )
                    AppText(
                        text = skill.description,
                        style = AppTheme.typography.labelSmall,
                        color = AppTheme.colors.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                SkillRiskBadge(SkillRisk.LOW)
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                AppIconButton(onClick = onTest) {
                    AppIcon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.skills_test_run))
                }
                AppIconButton(onClick = onCopy) {
                    AppIcon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.skills_copy_as_mine))
                }
            }
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    manager.setPrimaryClip(ClipData.newPlainText("AgentPaw skills", text))
}
