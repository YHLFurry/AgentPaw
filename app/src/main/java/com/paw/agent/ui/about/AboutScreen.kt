package com.paw.agent.ui.about

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Security
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.paw.agent.R
import com.paw.agent.ui.components.PawMark
import com.paw.agent.ui.components.adaptive.AppIcon
import com.paw.agent.ui.components.adaptive.AppIconButton
import com.paw.agent.ui.components.adaptive.AppScaffold
import com.paw.agent.ui.components.adaptive.AppSurface
import com.paw.agent.ui.components.adaptive.AppText
import com.paw.agent.ui.components.adaptive.AppTopAppBar
import com.paw.agent.ui.settings.SettingsCard
import com.paw.agent.ui.settings.SettingsGroupLabel
import com.paw.agent.ui.settings.SettingsNavRow
import com.paw.agent.ui.settings.SettingsRowDivider
import com.paw.agent.ui.theme.AppTheme
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AboutScreen(
    expertMode: Boolean,
    onToggleExpertMode: (Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val scaleAnim = remember { Animatable(1f) }

    AppScaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            AppTopAppBar(
                title = stringResource(R.string.about_title),
                navigationIcon = {
                    AppIconButton(onClick = onBack) {
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(12.dp))

            // 醒目的大图标，支持长按解锁资深（专家）模式
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(112.dp)
                    .scale(scaleAnim.value)
                    .clip(CircleShape)
                    .combinedClickable(
                        onClick = {
                            scope.launch {
                                scaleAnim.animateTo(0.92f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
                                scaleAnim.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
                            }
                        },
                        onLongClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            val targetState = !expertMode
                            onToggleExpertMode(targetState)
                            scope.launch {
                                scaleAnim.animateTo(1.15f, spring(dampingRatio = Spring.DampingRatioLowBouncy))
                                scaleAnim.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
                            }
                            val msg = if (targetState) {
                                context.getString(R.string.about_expert_unlocked)
                            } else {
                                context.getString(R.string.about_expert_locked)
                            }
                            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                        },
                    ),
            ) {
                AppSurface(
                    shape = CircleShape,
                    color = AppTheme.colors.primaryContainer,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        PawMark(
                            size = 64.dp,
                            color = AppTheme.colors.onPrimaryContainer,
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            AppText(
                text = stringResource(R.string.about_app_name),
                style = AppTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )

            AppText(
                text = stringResource(R.string.about_tagline),
                style = AppTheme.typography.bodyMedium,
                color = AppTheme.colors.onSurfaceVariant,
            )

            if (expertMode) {
                Spacer(Modifier.height(8.dp))
                AppSurface(
                    shape = CircleShape,
                    color = AppTheme.colors.primary.copy(alpha = 0.15f),
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        AppIcon(
                            imageVector = Icons.Outlined.AutoAwesome,
                            contentDescription = null,
                            tint = AppTheme.colors.primary,
                            modifier = Modifier.size(16.dp),
                        )
                        AppText(
                            text = "资深（专家）模式已激活",
                            style = AppTheme.typography.labelSmall,
                            color = AppTheme.colors.primary,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            } else {
                Spacer(Modifier.height(6.dp))
                AppText(
                    text = stringResource(R.string.about_expert_hint),
                    style = AppTheme.typography.labelSmall,
                    color = AppTheme.colors.outline.copy(alpha = 0.7f),
                )
            }

            Spacer(Modifier.height(28.dp))

            // 版本与项目信息卡片
            val packageInfo = remember(context) {
                runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
            }
            val displayVersion = remember(packageInfo) {
                val name = packageInfo?.versionName?.removeSuffix("-debug") ?: "0.1.5"
                val code = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    packageInfo?.longVersionCode ?: 6L
                } else {
                    @Suppress("DEPRECATION")
                    packageInfo?.versionCode?.toLong() ?: 6L
                }
                "v$name (Build $code)"
            }

            SettingsGroupLabel("应用程序信息")
            SettingsCard {
                SettingsNavRow(
                    icon = Icons.Outlined.Info,
                    title = stringResource(R.string.settings_version),
                    summary = "版本与内部编译标识",
                    trailing = displayVersion,
                    onClick = {},
                )

                SettingsRowDivider()

                SettingsNavRow(
                    icon = Icons.Outlined.Favorite,
                    title = stringResource(R.string.about_developer),
                    summary = stringResource(R.string.about_developer_name),
                    onClick = {
                        val uri = Uri.parse("https://github.com/YHLFurry")
                        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                    },
                )
            }

            Spacer(Modifier.height(20.dp))

            // 开源社区与协议
            SettingsGroupLabel("开源与许可")
            SettingsCard {
                SettingsNavRow(
                    icon = Icons.Outlined.Code,
                    title = stringResource(R.string.about_open_source),
                    summary = stringResource(R.string.about_open_source_desc),
                    trailing = "GitHub",
                    onClick = {
                        val uri = Uri.parse("https://github.com/YHLFurry/AgentPaw")
                        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                    },
                )

                SettingsRowDivider()

                SettingsNavRow(
                    icon = Icons.Outlined.Security,
                    title = stringResource(R.string.about_license),
                    summary = stringResource(R.string.about_license_name),
                    onClick = {
                        val uri = Uri.parse("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                    },
                )
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}
