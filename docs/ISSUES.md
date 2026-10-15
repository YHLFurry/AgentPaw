# AgentPaw Issues 登记册（真实用户视角持续发现）

> 本文档由「真实用户体验测试员 + 产品 + QA + 全栈开发」视角，基于对代码、构建产物与测试运行的**实际调查**建立。
> 所有结论均以代码位置/运行输出为证据；无法实际运行验证的条目明确标注「待验证」。
> 更新日期：2026-10-15

---

## 0. 本轮实际检查范围（功能清单基线）

| 模块 | 检查方式 | 结论 |
|---|---|---|
| 工程构建与测试 | `./gradlew testDebugUnitTest` 实跑 | ✅ EXIT=0，25 个测试类共 **178** 项测试全部通过（README badge 写 177，轻微滞后，见 ISSUE-206） |
| Git 工作树 | `git status` | 干净（仅 `_probe_procs.txt` 未跟踪临时文件） |
| 聊天主流程 | ChatScreen/ChatViewModel/MessageBubble/AgentTaskRunner 全量阅读 | 骨架完整；断点卡片三态（风险确认/安全暂停/普通暂停）渲染逻辑正确 |
| 导航 | AgentPawApp | 两级设置结构、设置 ViewModel 共享草稿的设计正确 |
| 设置保存 | LlmSettingsViewModel + SettingsScreens | 校验错误码有 `errorText()` 映射到 string 资源，非缺陷 |
| 历史/回溯/对比 | HistoryScreen/HistoryDetailScreen/PersistentConversationRepository | 组合 key、navigationBarsPadding 等既有修复在位 |
| 三通道控制器 | HybridPhoneController + 三个子控制器 | 优先级调度正确；**发现停止检查遗漏（ISSUE-201）与 Root 缓存失效（ISSUE-202）** |
| 悬浮窗/前台服务 | AgentFloatingService + AgentExecutionController | **发现自毁竞态（ISSUE-203）与暂停态交互歧义（ISSUE-204）** |
| 风控与断点 | RiskActionGuard/ToolControlSignal/TaskBreakpoint/ResumeIntentDetector | 规则分层清晰；既有 SEC-001~011 修复在位 |
| 工具层 | AndroidPhoneTools 全部 12 工具 | **发现返回 JSON 字符串插值隐患（ISSUE-205）** |

未覆盖（需真机/模拟器，标注待验证）：Compose 实际渲染效果、无障碍手势真实派发、Root/Shizuku 真机通道、通知栏展示。

---

## ISSUE-201：`launch_app` / `open_deeplink` 绕过用户停止指令

- **优先级**：P1（安全 / 核心控制流）
- **类别**：功能缺陷 + 安全与权限
- **模块**：`agentpaw-core` / `device` / `HybridPhoneController.kt`
- **证据**：
  - `HybridPhoneController.kt` L131/L159/L191/L219/L251/L278/L384/L398/L412/L426：`tap`、`doubleTap`、`longPress`、`tapAtPixel`、`swipe`、`inputText`、`pressBack`、`pressHome`、`pressRecents`、`pressEnter` **全部**以 `if (userStopRequested()) return false` 开头；
  - L452 `launchApp` 与 L572 `openDeepLink` **没有**该检查（`takeScreenshot`/`getScreenState` 为只读感知，保持放行）。
- **复现步骤**：
  1. 用户发送任务，Agent 推理到需要 `launch_app`（或 `open_deeplink`）的一步；
  2. 用户在悬浮胶囊/聊天页点击「停止」；
  3. 观察：该步骤仍会通过 Root/Shizuku/Intent 把目标应用或 URI 拉起。
- **当前行为**：停止指令对「启动应用 / 打开 DeepLink」无效，违反「停止即全部中止」的产品契约（也是全部其他操作已实现的契约）。
- **预期行为**：与所有其他交互方法一致，停止标志置位后立即失败返回，不产生任何设备状态变更。
- **根因**：新增这两个方法时遗漏了统一的停止守卫；停止依赖每个方法自觉检查，没有集中门禁。
- **修复方案**：在两个方法入口补 `userStopRequested()` 检查（最小改动，与其他方法完全一致）。
- **验收标准**：单测验证 `requestGlobalStop()` 后 `launchApp`/`openDeepLink` 返回 false 且不触达任何子控制器；无停止时行为不变（既有测试覆盖）。
- **状态**：已修复并验证（见 TASKS.md TASK-201）。

---

## ISSUE-202：Root 权限探测结果缓存永不失效，ROOT 模式可能静默降级

- **优先级**：P1（功能 / 核心用户流程）
- **类别**：功能缺陷 + 数据一致性
- **模块**：`agentpaw-core` / `device` / `RootController.kt`、`DevicePermissionManager.kt`、`HybridPhoneController.kt`、`app` / `ui` / `ChatScreen.kt`、`AgentPawApp.kt`
- **证据**：
  - `RootController.kt` L27-41：`cachedRootAvailable` 首次探测后写入缓存，只有 `refreshAvailability()`/`checkAvailability()` 会更新，**没有任何过期机制**；
  - `HybridPhoneController.kt` L16：默认 `RootController()` 是**自己的实例**；`DevicePermissionManager.kt` L135 的 `rootController` 是**另一个实例**；
  - 全仓检索 `refreshAvailability` 调用方：只有设置页两个按钮（`SettingsScreens.kt` L577、`ExpertSettingsPage.kt` L88），刷新的都是 DevicePermissionManager 的实例；
  - `ChatScreen.kt` L207 ON_RESUME 时只 `remember { isRootAvailable() }` **读缓存**，不重新探测。
- **复现步骤**：
  1. 设备已 Root，但用户尚未在 Magisk 授权本应用（或首探时 `su -c id` 800ms 超时）；
  2. 此时任何一次 `isRootAvailable` 读取把 `false` 写入 `HybridPhoneController` 内部实例的缓存；
  3. 用户随后在系统/Magisk 完成授权，并在设置里开启「Root 模式」；
  4. 发送任务：控制器读到的仍是缓存的 `false`，**整个进程生命周期内**静默走 Shizuku/无障碍，ROOT 优先级形同虚设；
  5. 设置页点「测试 ROOT」显示成功（刷新的是另一个实例），与真实执行通道状态不一致，用户被误导。
- **当前行为**：Root 授权状态变化后不刷新；执行通道与设置 UI 可能展示不同的 Root 状态。
- **预期行为**：Root 状态在授权变化后（回到 APP、开启 Root 模式、设置页测试）应刷新，且执行通道与 UI 读取同一份状态。
- **根因**：双实例 + 无过期缓存 + 缺少变更触发点。
- **修复方案**：
  1. `HybridPhoneController` 默认 RootController 与 `DevicePermissionManager` **共享同一实例**（单一事实来源）；
  2. `DevicePermissionManager` 暴露 `refreshRootAvailability()`（IO 调度）；
  3. `ChatScreen` ON_RESUME 与 `AgentPawApp` 的 `rootModeEnabled` 开启时触发异步刷新（不阻塞合成）；
  4. ChatScreen 首次读取改为 `produceState` 异步探测，避免主线程阻塞。
- **验收标准**：单测验证共享实例接线与刷新传播；`assembleDebug` 通过；真机授权后回 APP 状态翻转（待真机验证）。
- **状态**：已修复并验证（见 TASKS.md TASK-202；真机部分待验证）。

---

## ISSUE-203：悬浮窗服务在任务结束后 1.5 秒内自毁，可能杀死紧随其后的新任务浮窗

- **优先级**：P2（稳定性 / 体验）
- **类别**：功能缺陷（竞态）
- **模块**：`app` / `ui` / `floating` / `AgentFloatingService.kt`、`agentpaw-core` / `device` / `floating` / `AgentFloatingVisibility.kt`
- **证据**：`AgentFloatingService.kt` L355-360：
  ```kotlin
  } else {
      updateNotification(...)
      delay(1500)
      stopSelf()
  }
  ```
  `Flow.collect` 在同一个协程内**顺序处理**发射：任务 A 结束 → 进入 else 分支 `delay(1500)`；这 1.5 秒内用户发送任务 B（`markStarted` 使 isRunning=true 的发射在队列中等待），1.5 秒后 `stopSelf()` 先执行，服务被销毁，任务 B 的前台通知与悬浮胶囊一起消失，直到系统按 START_STICKY 重启（重启时机不确定，且后台重启前台服务在 Android 14+ 可能被系统策略拒绝——正是 KNOWN_ISSUES ISSUE-002 的场景）。
- **复现步骤**：完成任务后 1.5 秒内立即发送新指令；观察浮窗胶囊与通知是否中途消失。
- **预期行为**：仅当延迟结束后状态仍是「未运行且未暂停」才自毁；期间有新任务则保留服务。
- **修复方案**：把「是否应当自毁」提取为纯函数 `shouldAutoStopService(isRunning, isPaused)`，延迟后重新读取状态再决定；补 JVM 单测。
- **验收标准**：纯函数单测覆盖四种状态组合；编译通过；服务接线使用同一函数。
- **状态**：已修复并验证（见 TASKS.md TASK-203）。

---

## ISSUE-204：暂停态悬浮胶囊交互歧义（按钮文案与点击区域矛盾）

- **优先级**：P2（UX）
- **类别**：UI / 交互
- **模块**：`app` / `ui` / `floating` / `AgentFloatingService.kt`
- **证据**：L215-223 动作按钮固定文案 `⏹ 停止`；L216-221 暂停时点击按钮执行 `requestResume()`；L353-354 暂停时状态文本写 `⏸ [第X步断点暂停] 点击继续`；但 L280-292 点击非按钮区域（展开态）执行的是 `startActivity(MainActivity)`。用户照着文本提示点胶囊主体，得到的却是打开 APP 主页，而不是继续执行。
- **复现步骤**：任务触发断点暂停 → 退到后台查看胶囊 → 点击状态文本区域。
- **预期行为**：暂停态按钮文案改为 `▶ 继续`；点击胶囊主体与按钮行为一致（恢复执行）。
- **修复方案**：按 `isPaused` 动态切换按钮文案与点击语义。
- **验收标准**：编译通过；行为与状态文本提示一致。真机点击验证待设备。
- **状态**：已修复并验证（见 TASKS.md TASK-204；真机点击待验证）。

---

## ISSUE-205：工具返回 JSON 使用字符串插值，特殊字符可产生损坏 JSON

- **优先级**：P3（数据一致性）
- **类别**：数据一致性 / 健壮性
- **模块**：`agentpaw-core` / `tool` / `android` / `AndroidPhoneTools.kt`
- **证据**：`LaunchAppTool` L357 `"""{"status":"success","launched":"$appName"}"""`、`DeepLinkTool` L402 `"""{"status":"success","opened":"$uri"}"""`。`appName`/`uri` 来自 LLM 生成的参数，可能包含 `"` 或 `\`，直接插值会产出结构损坏的 JSON（SEC-007 已修复 `input_text` 的同类问题，这两个工具被遗漏）。
- **影响**：损坏 JSON 进入会话历史与 LLM 上下文；`ToolControlSignal.parse` 有 `runCatching` 兜底不会崩溃，但上下文质量下降。
- **修复方案**：与 SEC-007 一致，改用 `buildJsonObject` 结构化序列化；补单测（含引号/反斜杠/中文）。
- **验收标准**：单测验证含 `"`、`\`、中文的入参返回可解析 JSON。
- **状态**：已修复并验证（见 TASKS.md TASK-205）。

---

## ISSUE-206：文档测试计数滞后（178 vs 177）

- **优先级**：P3（文档）
- **证据**：`testDebugUnitTest` 实跑统计 25 个 XML 合计 `tests=178`，`failures=0`；README badge 与 PROJECT_OVERVIEW 写 177。
- **修复**：文档同步为实际值，并注明以实跑为准。
- **状态**：已修复（见 TASKS.md TASK-206）。

---

## 修复后回归检查记录

| 检查项 | 命令 | 结果 |
|---|---|---|
| 全模块单元测试 | `./gradlew testDebugUnitTest` | ✅ **BUILD SUCCESSFUL**，28 个测试类共 **195** 项测试全部通过、0 失败（基线 178 + 本轮新增 17 项） |
| Debug APK 构建 | `./gradlew :app:assembleDebug` | ✅ **BUILD SUCCESSFUL**，产出 `app/build/outputs/apk/debug/app-debug.apk`（22.6 MB，2026-10-16 00:01 构建） |
| 新增测试是否真能变红 | 修复前对每个新测试先跑一遍 | ✅ ISSUE-201：回退修复前 4 项红 → 修复后 5 项全绿；ISSUE-202：回退旧接线后 4 项全红 → 修复后全绿；ISSUE-205：回退插值后引号用例红 → 修复后 5 项全绿 |
| 构建环境备注 | — | 首次 `assembleDebug` 曾因旧会话残留的 Gradle 守护进程并发写 dex 输出触发一次 `AccessDeniedException`（瞬时文件锁，非代码问题），重试即成功 |

**待真机验证（本环境无模拟器/设备，未标记为已验证）**：RT-001 Root 授权翻转后执行通道切换、RT-002 暂停态胶囊点击恢复、RT-003 宽限期竞态实测、RT-004 Compose 双主题实际渲染。见 KNOWN_ISSUES.md 第 5 节。
