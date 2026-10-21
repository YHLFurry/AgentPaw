package com.paw.agent.ui.skills

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.paw.agent.core.skill.SkillCatalog
import com.paw.agent.core.skill.SkillDefinition
import com.paw.agent.core.skill.SkillIssue
import com.paw.agent.core.skill.SkillIssueSeverity
import com.paw.agent.core.skill.SkillRisk
import com.paw.agent.core.skill.SkillRunResult
import com.paw.agent.core.skill.SkillRunStatus
import com.paw.agent.core.skill.SkillStep
import com.paw.agent.core.skill.SkillStepStatus
import com.paw.agent.ui.components.adaptive.AppButton
import com.paw.agent.ui.components.adaptive.AppSurface
import com.paw.agent.ui.components.adaptive.AppText
import com.paw.agent.ui.components.adaptive.AppTextButton
import com.paw.agent.ui.theme.AppTheme

/** 风险角标。颜色从“中性”到“错误”，一眼能分辨这条技能敢不敢直接跑。 */
@Composable
fun SkillRiskBadge(risk: SkillRisk, modifier: Modifier = Modifier) {
    val (label, bg, fg) = when (risk) {
        SkillRisk.NONE -> Triple("安全", AppTheme.colors.surfaceVariant, AppTheme.colors.onSurfaceVariant)
        SkillRisk.LOW -> Triple("低风险", AppTheme.colors.secondaryContainer, AppTheme.colors.onSecondaryContainer)
        SkillRisk.MEDIUM -> Triple("需留意", AppTheme.colors.tertiaryContainer, AppTheme.colors.onTertiaryContainer)
        SkillRisk.HIGH -> Triple("高风险", AppTheme.colors.errorContainer, AppTheme.colors.onErrorContainer)
        SkillRisk.CRITICAL -> Triple("极高危", AppTheme.colors.error, AppTheme.colors.onError)
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        AppText(
            text = label,
            style = AppTheme.typography.labelSmall,
            color = fg,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** 校验/解析诊断。错误在前，最多显示 8 条，避免对话框被刷屏。 */
@Composable
fun SkillIssueList(
    issues: List<SkillIssue>,
    modifier: Modifier = Modifier,
    maxItems: Int = 8,
) {
    if (issues.isEmpty()) return
    val ordered = issues.sortedBy { it.severity.ordinal }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ordered.take(maxItems).forEach { issue ->
            val color = when (issue.severity) {
                SkillIssueSeverity.ERROR -> AppTheme.colors.error
                SkillIssueSeverity.WARNING -> AppTheme.colors.onSurfaceVariant
                SkillIssueSeverity.INFO -> AppTheme.colors.onSurfaceVariant
            }
            val prefix = when (issue.severity) {
                SkillIssueSeverity.ERROR -> "✕"
                SkillIssueSeverity.WARNING -> "!"
                SkillIssueSeverity.INFO -> "i"
            }
            Row(verticalAlignment = Alignment.Top) {
                AppText(
                    text = prefix,
                    style = AppTheme.typography.labelMedium,
                    color = color,
                )
                Spacer(Modifier.width(6.dp))
                AppText(
                    text = buildString {
                        if (issue.line != null) append("第 ${issue.line} 行 ")
                        append(issue.message)
                    },
                    style = AppTheme.typography.bodySmall,
                    color = color,
                )
            }
        }
        if (ordered.size > maxItems) {
            AppText(
                text = "还有 ${ordered.size - maxItems} 条提示…",
                style = AppTheme.typography.labelSmall,
                color = AppTheme.colors.onSurfaceVariant,
            )
        }
    }
}

/** 单个步骤的一行摘要，编辑器与许愿预览共用。 */
@Composable
fun SkillStepRow(
    index: Int,
    step: SkillStep,
    modifier: Modifier = Modifier,
) {
    val spec = SkillCatalog.spec(step.kind)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        AppText(
            text = "${index + 1}.",
            style = AppTheme.typography.labelMedium,
            color = AppTheme.colors.onSurfaceVariant,
            modifier = Modifier.width(22.dp),
        )
        AppText(text = spec.emoji, style = AppTheme.typography.bodyMedium)
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            AppText(
                text = step.label.ifBlank { spec.label },
                style = AppTheme.typography.bodyMedium,
                color = if (step.enabled) AppTheme.colors.onSurface else AppTheme.colors.onSurfaceVariant,
            )
            val detail = buildString {
                append(spec.label)
                if (step.target.isNotBlank()) append("：${step.target}")
                if (step.kind == com.paw.agent.core.skill.SkillActionKind.TAP_POINT) {
                    append("(${step.x}, ${step.y})")
                }
                if (step.condition.isNotBlank()) append(" ｜ 当 ${step.condition}")
                if (!step.enabled) append(" ｜ 已禁用")
            }
            AppText(
                text = detail,
                style = AppTheme.typography.labelSmall,
                color = AppTheme.colors.onSurfaceVariant,
            )
        }
    }
}

/** 技能的整体步骤预览。 */
@Composable
fun SkillStepList(
    skill: SkillDefinition,
    modifier: Modifier = Modifier,
    limit: Int = 12,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        skill.steps.take(limit).forEachIndexed { index, step ->
            SkillStepRow(index = index, step = step)
        }
        if (skill.steps.size > limit) {
            AppText(
                text = "还有 ${skill.steps.size - limit} 步…",
                style = AppTheme.typography.labelSmall,
                color = AppTheme.colors.onSurfaceVariant,
                modifier = Modifier.padding(start = 28.dp, top = 2.dp),
            )
        }
    }
}

/** 测试运行结果：逐条展示每一步成功/失败原因，而不是只回一句“成功”。 */
@Composable
fun SkillTestResultCard(
    result: SkillRunResult,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val statusText = when (result.status) {
        SkillRunStatus.SUCCESS -> "执行成功"
        SkillRunStatus.FAILED -> "执行失败"
        SkillRunStatus.REQUIRES_CONFIRMATION -> "需要确认"
        SkillRunStatus.SAFETY_PAUSED -> "安全暂停"
        SkillRunStatus.CANCELLED -> "已取消"
    }
    val statusColor = when (result.status) {
        SkillRunStatus.SUCCESS -> AppTheme.colors.primary
        SkillRunStatus.FAILED -> AppTheme.colors.error
        else -> AppTheme.colors.tertiaryContainer
    }

    AppSurface(
        shape = RoundedCornerShape(16.dp),
        color = AppTheme.colors.surfaceContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppText(
                    text = "测试结果 · $statusText",
                    style = AppTheme.typography.titleSmall,
                    color = statusColor,
                    fontWeight = FontWeight.SemiBold,
                )
                AppTextButton(text = "关闭", onClick = onDismiss)
            }
            if (result.message.isNotBlank()) {
                AppText(
                    text = result.message,
                    style = AppTheme.typography.bodySmall,
                    color = AppTheme.colors.onSurfaceVariant,
                )
            }
            if (result.steps.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                result.steps.forEach { step ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        AppText(
                            text = when (step.status) {
                                SkillStepStatus.SUCCESS -> "✓"
                                SkillStepStatus.SKIPPED -> "–"
                                SkillStepStatus.PAUSED -> "⏸"
                                SkillStepStatus.FAILED -> "✕"
                            },
                            style = AppTheme.typography.labelMedium,
                            color = when (step.status) {
                                SkillStepStatus.SUCCESS -> AppTheme.colors.primary
                                SkillStepStatus.FAILED -> AppTheme.colors.error
                                else -> AppTheme.colors.onSurfaceVariant
                            },
                            modifier = Modifier.width(20.dp),
                        )
                        Column(Modifier.weight(1f)) {
                            AppText(
                                text = step.label,
                                style = AppTheme.typography.bodySmall,
                                color = AppTheme.colors.onSurface,
                            )
                            if (step.message.isNotBlank()) {
                                AppText(
                                    text = step.message,
                                    style = AppTheme.typography.labelSmall,
                                    color = AppTheme.colors.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 高危操作的确认弹窗内容（简单的强调块）。 */
@Composable
fun SkillDangerNotice(text: String, modifier: Modifier = Modifier) {
    AppSurface(
        shape = RoundedCornerShape(12.dp),
        color = AppTheme.colors.errorContainer,
        contentColor = AppTheme.colors.onErrorContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            AppText(text = "⚠️", style = AppTheme.typography.bodyMedium)
            Spacer(Modifier.width(8.dp))
            AppText(
                text = text,
                style = AppTheme.typography.bodySmall,
                color = AppTheme.colors.onErrorContainer,
            )
        }
    }
}

/** 行内小图标按钮的占位尺寸，保证不同图标视觉重量一致。 */
internal val SkillIconSize = 20.dp

@Composable
internal fun SkillEmptyHint(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 28.dp),
        contentAlignment = Alignment.Center,
    ) {
        AppText(
            text = text,
            style = AppTheme.typography.bodySmall,
            color = AppTheme.colors.onSurfaceVariant,
        )
    }
}

@Composable
internal fun SkillPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    AppButton(onClick = onClick, modifier = modifier, enabled = enabled) {
        AppText(text = text, style = AppTheme.typography.labelLarge)
    }
}

@Composable
internal fun SkillGhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    com.paw.agent.ui.components.adaptive.AppOutlinedButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
    ) {
        AppText(text = text, style = AppTheme.typography.labelLarge)
    }
}

@Composable
internal fun SkillDot(color: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(color),
    )
}
