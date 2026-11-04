package com.paw.agent.ui.skills

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.runtime.remember
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.paw.agent.R
import com.paw.agent.core.llm.LlmConfig
import com.paw.agent.core.skill.SkillCatalog
import com.paw.agent.ui.components.adaptive.AppButton
import com.paw.agent.ui.components.adaptive.AppCircularProgressIndicator
import com.paw.agent.ui.components.adaptive.AppIcon
import com.paw.agent.ui.components.adaptive.AppIconButton
import com.paw.agent.ui.components.adaptive.AppScaffold
import com.paw.agent.ui.components.adaptive.AppSurface
import com.paw.agent.ui.components.adaptive.AppText
import com.paw.agent.ui.components.adaptive.AppTextField
import com.paw.agent.ui.components.adaptive.AppTopAppBar
import com.paw.agent.ui.components.adaptive.AppTopBarAction
import com.paw.agent.ui.components.adaptive.LocalTopBarClearance
import com.paw.agent.ui.settings.SettingsCard
import com.paw.agent.ui.settings.SettingsGroupLabel
import com.paw.agent.ui.theme.AppTheme

/**
 * 「许愿式」技能生成。
 *
 * 用户只需要写一句人话，例如「每天帮我把微信里老板的消息置顶」；
 * 生成结果先以**可审查的预览**呈现（步骤、参数、风险等级、校验问题），
 * 确认后才落库启用。生成不等于放行——这是刻意保留的一道人工闸门。
 */
@Composable
fun SkillWishScreen(
    viewModel: SkillsViewModel,
    llmConfig: LlmConfig,
    onBack: () -> Unit,
    onEditPreview: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val preview = state.wishPreview
    val configReady = llmConfig.isUsable
    val scrollState = rememberScrollState()
    val isContentScrolled by remember(scrollState) { derivedStateOf { scrollState.value > 0 } }

    AppScaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            AppTopAppBar(
                title = stringResource(R.string.skills_wish_title),
                isContentScrolled = isContentScrolled,
                navigationIcon = {
                    AppTopBarAction(onClick = onBack) {
                        AppIcon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                        )
                    }
                },
                color = AppTheme.colors.surfaceContainer,
            )
        },
        containerColor = AppTheme.colors.background,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(scrollState)
                // 顶栏高度作为可滚动内容的顶部间距，内容从玻璃渐隐带下穿过。
                .padding(
                    start = 16.dp,
                    end = 16.dp,
                    top = 12.dp + LocalTopBarClearance.current,
                    bottom = 12.dp,
                ),
        ) {
            AppSurface(
                shape = RoundedCornerShape(18.dp),
                color = AppTheme.colors.primaryContainer,
                contentColor = AppTheme.colors.onPrimaryContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AppIcon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = AppTheme.colors.onPrimaryContainer,
                            modifier = Modifier.size(22.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        AppText(
                            text = stringResource(R.string.skills_wish_lead),
                            style = AppTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = AppTheme.colors.onPrimaryContainer,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    AppText(
                        text = stringResource(R.string.skills_wish_lead_body),
                        style = AppTheme.typography.bodySmall,
                        color = AppTheme.colors.onPrimaryContainer,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            SettingsGroupLabel(stringResource(R.string.skills_wish_input_label))
            AppTextField(
                value = state.wishText,
                onValueChange = viewModel::onWishChange,
                placeholder = stringResource(R.string.skills_wish_placeholder),
                minLines = 4,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(10.dp))
            AppText(
                text = stringResource(R.string.skills_wish_examples),
                style = AppTheme.typography.labelSmall,
                color = AppTheme.colors.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                WISH_EXAMPLES.forEach { example ->
                    AppSurface(
                        shape = RoundedCornerShape(10.dp),
                        color = AppTheme.colors.surfaceContainer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.onWishChange(example) },
                    ) {
                        AppText(
                            text = example,
                            style = AppTheme.typography.bodySmall,
                            color = AppTheme.colors.onSurface,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        )
                    }
                }
            }

            if (!configReady) {
                Spacer(Modifier.height(10.dp))
                SkillDangerNotice(stringResource(R.string.skills_wish_no_config))
            }

            state.wishError?.let { error ->
                Spacer(Modifier.height(10.dp))
                SkillDangerNotice(error)
            }

            Spacer(Modifier.height(16.dp))
            AppButton(
                onClick = { viewModel.generateFromWish(llmConfig) },
                enabled = configReady && !state.wishRunning && state.wishText.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.wishRunning) {
                    AppCircularProgressIndicator()
                    Spacer(Modifier.width(8.dp))
                    AppText(
                        text = stringResource(R.string.skills_wish_generating),
                        style = AppTheme.typography.labelLarge,
                    )
                } else {
                    AppText(
                        text = stringResource(R.string.skills_wish_generate),
                        style = AppTheme.typography.labelLarge,
                    )
                }
            }

            if (preview != null) {
                Spacer(Modifier.height(20.dp))
                SettingsGroupLabel(stringResource(R.string.skills_wish_preview))
                SettingsCard {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AppText(text = preview.emoji, style = AppTheme.typography.titleMedium)
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                AppText(
                                    text = preview.displayName,
                                    style = AppTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = AppTheme.colors.onSurface,
                                )
                                AppText(
                                    text = preview.key,
                                    style = AppTheme.typography.labelSmall,
                                    color = AppTheme.colors.onSurfaceVariant,
                                )
                            }
                            SkillRiskBadge(SkillCatalog.riskOf(preview))
                        }

                        Spacer(Modifier.height(8.dp))
                        AppText(
                            text = preview.description,
                            style = AppTheme.typography.bodySmall,
                            color = AppTheme.colors.onSurfaceVariant,
                        )

                        if (preview.parameters.isNotEmpty()) {
                            Spacer(Modifier.height(10.dp))
                            AppText(
                                text = stringResource(R.string.skills_wish_params),
                                style = AppTheme.typography.labelMedium,
                                color = AppTheme.colors.onSurface,
                            )
                            preview.parameters.forEach { param ->
                                AppText(
                                    text = "· ${param.key}${if (param.required) "（必填）" else ""} — ${param.description}",
                                    style = AppTheme.typography.labelSmall,
                                    color = AppTheme.colors.onSurfaceVariant,
                                )
                            }
                        }

                        Spacer(Modifier.height(10.dp))
                        AppText(
                            text = stringResource(R.string.skills_wish_steps, preview.steps.size),
                            style = AppTheme.typography.labelMedium,
                            color = AppTheme.colors.onSurface,
                        )
                        Spacer(Modifier.height(4.dp))
                        SkillStepList(preview)
                    }
                }

                if (state.wishIssues.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    SettingsCard {
                        Column(Modifier.padding(16.dp)) {
                            AppText(
                                text = stringResource(R.string.skills_diagnostics),
                                style = AppTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = AppTheme.colors.onSurface,
                            )
                            Spacer(Modifier.height(6.dp))
                            SkillIssueList(state.wishIssues)
                        }
                    }
                }

                if (state.wishRepaired) {
                    Spacer(Modifier.height(8.dp))
                    AppText(
                        text = stringResource(R.string.skills_wish_repaired),
                        style = AppTheme.typography.labelSmall,
                        color = AppTheme.colors.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(14.dp))
                AppButton(
                    onClick = { viewModel.saveWishPreview() },
                    enabled = state.wishIssues.none { it.isError },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    AppText(
                        text = stringResource(R.string.skills_wish_accept),
                        style = AppTheme.typography.labelLarge,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SkillGhostButton(
                        text = stringResource(R.string.skills_wish_tune),
                        onClick = {
                            viewModel.editWishPreview()
                            onEditPreview()
                        },
                        modifier = Modifier.weight(1f),
                    )
                    SkillGhostButton(
                        text = stringResource(R.string.skills_wish_regenerate),
                        onClick = { viewModel.generateFromWish(llmConfig) },
                        modifier = Modifier.weight(1f),
                        enabled = configReady && !state.wishRunning,
                    )
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

private val WISH_EXAMPLES = listOf(
    "打开微信，搜索我输入的名字，然后点进第一个聊天",
    "打开设置，找到「显示」并把亮度调到最高",
    "在淘宝里搜索我输入的商品，滚动找到第一个「加入购物车」并点击",
)
