# AgentPaw 产品体验扫描与增量改进

日期：2026-10-26（Asia/Singapore）。本轮基于本地工作区完成扫描、实现与验证。

## 扫描发现

### 结构与用户入口

| 范围 | 实现与入口 |
| --- | --- |
| 应用入口 | `AgentPawApplication` / `AppContainer` 手动组装依赖；`MainActivity` 是唯一应用 Activity |
| UI 架构 | Kotlin、Jetpack Compose、Navigation Compose、ViewModel + Repository、StateFlow；未发现 Fragment / XML 页面、Room / SQLite 或 WorkManager 实现 |
| 页面 | 聊天为首页；顶栏进入历史、技能、新对话、设置。设置分模型、生成参数、手机控制、外观及专家页；历史可查看详情和比较；技能可导入导出、编辑和许愿生成 |
| ViewModel | `ChatViewModel`、`LlmSettingsViewModel`、`AppearanceSettingsViewModel`、`SkillsViewModel` |
| 任务链路 | 聊天 → 应用级 `AgentTaskRunner` → `Agent` 模型/工具循环 → `HybridPhoneController` → Root / Shizuku / 无障碍；通过断点显式续操，包含风险操作确认和敏感页暂停 |
| 设置 | `DataStoreSettingsRepository` 使用 Preferences DataStore，API Key 经 Android Keystore 加密；模型配置有校验、连接测试、模型列表和诊断 |
| 历史与附件 | `PersistentConversationRepository` 保存会话 JSON 与摘要索引，图片在私有目录；`ImageAttachmentStore` 导入时缩放、压缩、移除原元数据；没有关系型数据库 |
| 技能 | `SkillStore` / `SkillRegistry` / `SkillEngine`；版本化 JSON、参数校验、风险策略与逐步执行结果 |
| 网络 | `OpenAiCompatibleClient` 使用 OkHttp，支持 SSE、请求超时、取消传播、结构化错误与传输策略；搜索由 DuckDuckGo backend 提供 |
| 权限 | `DevicePermissionManager` 统一处理无障碍、Root、Shizuku、悬浮窗、通知及 OEM 设置入口；无障碍服务、Shizuku provider 在 core Manifest 注册 |
| 后台与通知 | `AgentFloatingService` 为前台服务，提供进度通知、停止按钮和悬浮状态；`AgentExecutionController` 用 StateFlow 同步进度/暂停状态。执行协程独立于页面 ViewModel |
| 通用 UI | `AppTheme` + adaptive 组件支持 Material / Miuix，提供对话框、Snackbar、按钮及进度组件；颜色和字号主要复用主题 token |

已检查两模块源码目录、构建/CI、Manifest、资源与备份配置，并重点阅读入口、导航、聊天、设置、历史、技能、存储、网络、设备权限和前台服务的关键路径；本轮深度修改聚焦聊天入口与恢复体验，不代表已逐行形式化审计整个执行引擎。

### 优先问题

1. 新用户只能从欢迎页进入设置总页再找模型服务，缺少简短配置路径与数据发送说明。
2. 草稿仅在 ViewModel 内存中，系统回收进程后无法恢复；直接从历史切换会话也可能携带旧草稿。
3. 新对话按钮立即停止任务、丢弃断点与未发送附件，缺少防误触保护。
4. 通知检查只处理 Android 13+ 的权限位，忽略通知总开关；拒绝后反复申请可能无反馈；Root 仪表盘会遮住通知提示，设置页也有相同申请问题。
5. 聊天页保存失败只有错误文字；历史加载和筛选无结果会误显示首次使用欢迎页。消息列表注释声称避免重复 key，实际仍以消息 ID 为 key，异常旧记录存在崩溃风险。

## 已实施的五项改进

1. **配置直达与入门说明**：欢迎页按钮和未配置时的输入区按钮直接进入模型服务。补充保存、测试连接、设备控制授权的顺序，以及 API Key 本机加密和任务/截图发送到所选模型服务的说明。欢迎内容支持滚动，减少小屏/大字下按钮不可达的风险。
2. **文字草稿恢复与会话隔离**：通过已有 Lifecycle 的 `SavedStateHandle` 恢复系统保存的文字草稿；等待仓库初始化完成再核对会话 ID。发送或明确清空后同步清除保存值；历史切换清理旧草稿/附件，新建后立即输入的新草稿不会被异步会话事件清除。
3. **新对话防误触**：存在运行任务、断点、文字、图片或导入中的附件时，先说明停止/清除的影响并允许取消；没有待处理内容时仍保持一步新建。复用双主题对话框。
4. **通知恢复闭环**：同时检查通知总开关及运行时权限。聊天与设置共用 `rememberNotificationPermissionAction`：需要权限时按需申请；已授权但通知被关闭或旧系统直接进入通知设置；拒绝时显示可取消的恢复说明，包含永久拒绝后系统不再弹窗的情况。通知提示独立于 Root 仪表盘和其他授权提示。
5. **聊天异常和状态恢复**：保存错误旁提供已有仓库重试入口，并通过 polite live region 提醒辅助阅读；加载历史显示主题适配进度，筛选无结果提供“全部”操作并保存筛选选择；聊天列表改用位置 key，容忍重复消息 ID。

新增 UI 文案均提供英文与简体中文，没有增加运行时依赖或改动模型/工具执行协议。

## 验证结果

环境：PowerShell、JDK 21、本机既有 Gradle/Android SDK 缓存，离线模式。Gradle 需要写用户缓存锁文件，因此在沙箱外运行已授权的本地构建。

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21.0.10'
.\gradlew.bat :app:compileDebugKotlin --offline --console=plain --max-workers=2
.\gradlew.bat :app:testDebugUnitTest --offline --console=plain --max-workers=2
.\gradlew.bat testDebugUnitTest :app:assembleDebug :app:lintDebug :agentpaw-core:lintDebug :app:assembleDebugAndroidTest --offline --console=plain --max-workers=2
git diff --check
adb devices -l
```

最终组合命令返回 **BUILD SUCCESSFUL**。其后仅更新了权限方法的说明注释，无行为代码变更。

| 检查 | 结果 |
| --- | --- |
| app Debug JVM 单元测试 | 70 项全部通过；本轮新增 5 项草稿回归测试 |
| core Debug JVM 单元测试 | 283 项：281 通过、2 条件跳过、0 失败 |
| app / core Debug Lint | 0 错误；分别 104 / 24 条警告，未为清零既有警告扩大范围 |
| Debug APK | `app/build/outputs/apk/debug/app-debug.apk` 构建成功 |
| Android 测试 APK | `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` 构建成功 |
| 双主题 UI 回归用例 | 新增 4 个场景 × 2 个主题：新对话确认/取消、保存重试及重复 ID、模型直达、历史加载状态；仅完成编译，未执行设备测试 |
| 差异空白检查 | 通过 |
| 设备 | ADB 返回空设备列表；未连接真机或运行中的模拟器 |

单元回归覆盖：延迟初始化后恢复草稿、忽略其他会话草稿、初始化前新编辑优先、发送清空与新会话立即编辑、外部会话切换清理草稿/附件。两条 core 条件跳过沿用现有公网与符号链接环境限制。

## 边界与后续验证

- `SavedStateHandle` 恢复的是 Android 已保存的任务状态，面向系统回收后的返回；不承诺强制停止、清除应用数据或移除最近任务后恢复，也没有新增磁盘草稿库。未发送图片不写入系统 Bundle，避免大数据状态开销；本轮保证的是文字草稿。
- 未自动重跑失败的手机操作。已有任务可能已经点击、发送或修改外部应用，盲目重跑有重复执行风险；继续复用已有断点与显式确认路径。
- 通知权限拒绝/永久拒绝、系统设置往返、Root/Shizuku、OEM 后台限制、TalkBack、双主题窄屏大字仍需 API 26 与 API 33+ 设备回归。构建成功不能代替实际界面验证。
- 扫描开始时已经存在 `AgentTaskRunner`、`TaskCheckpointStore`、相关测试及 `AUDIT_2026-10-26.md` 的未提交改动。本轮予以保留；对已有 `ChatViewModelTest` 仅增补草稿测试，不将先前调度修复归为本轮成果。
