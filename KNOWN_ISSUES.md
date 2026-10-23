# AgentPaw 已知问题、技术债与风险登记册 (KNOWN_ISSUES)

> 2026-10-24：图片附件（GitHub #63）、历史分页、流式性能、资源预算、密码节点识别、媒体清理、处理耗时与构建/本地化优化见 [后续优化记录](docs/FOLLOW_UP_2026-10-24.md)。下方旧测试数和旧实现描述作为历史记录保留，不代表当前状态。

> 本文档用于记录 AgentPaw 运行期间的已知缺陷、边缘条件限制、技术债务及应对方案。
> 更新时间：2026-10-15
>
> 2026-10-15 轮次：完成一轮真实用户视角巡检（docs/ISSUES.md），修复 ISSUE-201~205，
> 全部经「先红后绿」测试验证。本轮新增待验证项见文末第 5 节。

---

## 1. 运行时与系统特权层限制

### ISSUE-001: Android 8 ~ 10 纯无障碍模式下无法获取原生截屏
- **现象描述**：在 Android 8.0 ~ 10 (API 26~29) 系统上，若未激活 Root 或 Shizuku，仅依靠无障碍服务运行时，`AgentAccessibilityService` 无法调用 `takeScreenshot()`（该 API 于 Android 11 / API 30 引入）。
- **影响范围**：多模态视觉感知依赖屏幕截图，低版本系统若无 Root/Shizuku 仅能通过 UI 节点树检索执行基础自动化。
- **缓解策略**：
  1. `HybridPhoneController` 在没有 Root/Shizuku 且系统版本 < Android 11 时，截屏返回 null。
  2. UI 层面在关于与设置页中明示“推荐使用 Android 11+ 或配合 Shizuku/Root 开启多模态视觉能力”。

### ISSUE-002: Android 14+ 前台服务后台启动异常边界
- **现象描述**：Android 14 (API 34) 对 `startForegroundService` 施加了严格的前台运行限制。若系统因某种极特殊外部唤醒在应用处于纯后台时启动 `AgentFloatingService`，可能抛出 `ForegroundServiceStartNotAllowedException`。
- **影响范围**：极低概率，目前任务主要由用户在前台主动输入或点击卡片触发。
- **缓解策略**：已在 `AgentTaskRunner` 中通过 `context?.let { runCatching { AgentFloatingService.start(it) } }` 进行安全包裹，防止宿主应用由于系统策略崩溃。

---

## 2. 外部依赖与网络层风险

### ISSUE-003: DuckDuckGo 网页免 Key 抓取的不稳定性
- **现象描述**：`DuckDuckGoSearchBackend` 依赖解析 `html.duckduckgo.com` 的 HTML 页面。在特定网络环境或高频并发请求下，DuckDuckGo 会返回 403 / 验证码挑战，导致搜索结果为空。
- **影响范围**：Agent 调用 `web_search` 工具时可能偶发无结果。
- **缓解策略**：
  1. 当前测试已将依赖真实公网的 `DuckDuckGoSearchBackendLiveTest` 标记为 `@Ignore`，确保本地 CI 构建的完全气密性。
  2. 后续可在 `DuckDuckGoSearchBackend` 增加更友好的异常捕获与重试机制，或支持自定义搜索引擎 API（如 SearXNG / Google Custom Search）。

### ISSUE-004: Miuix KMP 依赖与 Kotlin 编译器版本约束
- **现象描述**：Miuix 库 0.9.2+ 版本引入了依赖 Kotlin 2.4+ 与 AGP 9 的 Compose Foundation 1.12.0，其 metadata 2.4.0 无法被当前稳定的 Kotlin 2.2.21 编译器读取。
- **影响范围**：若无意中升级 Miuix 至 0.9.2+，会导致编译阶段报 `compiled with an incompatible version of Kotlin`。
- **缓解策略**：
  1. 在 `gradle/libs.versions.toml` 与 `app/build.gradle.kts` 中严格锁死 `miuix = "0.9.1"`。
  2. 在 `app/build.gradle.kts` 中通过 `resolutionStrategy.force("org.jetbrains.kotlin:kotlin-stdlib:2.2.21")` 强制对齐标准库版本。

---

## 3. 数据与性能技术债

### ISSUE-005: 会话反序列化缺乏分页机制
- **现象描述**：`PersistentConversationRepository` 在启动时通过 `loadAllFromDisk()` 一次性扫描并反序列化 `conversations/` 目录下全部历史 JSON。
- **影响范围**：若单设备产生上百场任务或单会话包含大量交互轮次，冷启动耗时和初始内存占用会轻度上升。
- **缓解策略**：虽然图片已通过 `images/` 解耦为物理文件并仅存储 `file://` 路径，避免了超大 Base64 导致内存溢出，但中长期建议引入按需加载或轻量 SQLite/Room 数据库索引。

### ISSUE-006: 文档测试统计数据轻微滞后 - [已修复]
- **现象描述**：`README_CN.md` 与 `README.md` 中的 Badge 记录单元测试数与当前工程实际测试数不一致。
- **影响范围**：纯文档展示差异，不影响任何业务逻辑。
- **缓解策略**：2026-10-15 轮次已统一改为与实跑一致的表述（不再写死易滞后的具体数字拆分，Badge 以实跑 195 项为准）。

---

## 4. 审计安全缺陷修复记录 (Security Vulnerabilities & Remediations)

### SEC-001: 高风险确认执行状态短路死锁 (P0) - [已修复并验证]
- **位置**：`AgentTaskRunner.kt` → `confirmAndExecuteRiskAction`
- **根因**：确认执行函数先将 `_isGenerating.value = true`，然后在异步协程内调用 `resumeBreakpoint()`。而 `resumeBreakpoint()` 开头含有门禁 `if (_isGenerating.value) return`。导致续跑被自身状态短路拦截，任务直接卡住且 `_isGenerating` 永远无法复位。
- **修复方案**：`confirmAndExecuteRiskAction` 在调用 `resumeBreakpoint` 前显式复位 `_isGenerating.value = false`，外层添加 `try-catch` 并在失败时复位状态与通知 UI。

### SEC-002: `click_element` 坐标 bounds 绕过全屏支付/删除风控 (P1) - [已修复并验证]
- **位置**：`AndroidPhoneTools.kt` → `ClickElementTool`，`RiskActionGuard.kt`
- **根因**：风控评估仅传入 `targetElemText`；当模型传入自定义 bounds 但无法精准匹配节点树时，`targetElemText` 为空，绕过了全屏支付正则判断，随后仍按像素中心点击。
- **修复方案**：强制将全屏 `allText` 以及目标文本共同纳入风险评估。在 `AndroidPhoneToolsTest` 补充单测验证拦截。

### SEC-003: `open_deeplink` 缺乏协议黑名单与高危拦截 (P1) - [已修复并验证]
- **位置**：`AndroidPhoneTools.kt` → `DeepLinkTool`，`HybridPhoneController.kt`，`RiskActionGuard.kt`
- **根因**：仅对特定支付 scheme 实施二次确认，其余任意 URI（如 `intent:`, `file:`, `content:`, `package:`）均可直接通过 `am start -d` 拉起。
- **修复方案**：引入 `FORBIDDEN_DEEPLINK_SCHEMES` 禁止列表（`file`, `content`, `intent`, `package`, `javascript`, `data`, `jar`, `android.resource`），控制器与工具双重拦截，并在 `RiskActionGuard` 评为 CRITICAL。

### SEC-004: 敏感屏截图仍回传云端大模型 (P1) - [已修复并验证]
- **位置**：`AndroidPhoneTools.kt` → `TakeScreenshotTool`
- **根因**：检测到 `isSensitive(allText)` 为真时，仅在返回 JSON 中增加 `safety_alert` 字段，未清空 `image_base64`，导致敏感支付或密码屏仍作为多模态输入上传。
- **修复方案**：命中敏感屏且未显式授权时，立即返回 `is_safety_pause: true` 并坚决不携带 `image_base64`。补充自动化单测验证无图片泄露。

### SEC-005 ~ SEC-011: 其余中高危缺陷加固 - [全部修复并验证]
- **SEC-005**: `network_security_config.xml` 关闭全局明文流量，收敛至仅本地回环与内网调试地址。
- **SEC-006**: `CustomSkillModel.kt` 自定义技能单步执行统一接入 `SafetyGuard.checkScreenAndRisk`。
- **SEC-007**: `InputTextTool` 改用 `buildJsonObject` 结构化序列化返回结果，杜绝反斜杠/双引号导致 JSON 破坏。
- **SEC-008**: `HybridPhoneController` 与 `AgentAccessibilityService` 剪贴板输入后立即调用 `clearClipboardSafely`。
- **SEC-009**: `Agent.kt` 本地图片读取强制校验合法图像后缀及 10MB 大小上限，禁止跨路径读取数据库/私有配置。
- **SEC-010**: `OpenAiCompatibleClient.isLocalOrPrivateAddress` 补充 IPv6 ULA (`fc00::/7`) 与 Link-Local (`fe80::/10`)，并防范 `fc`/`fd` 前缀的欺骗域名。
- **SEC-011**: `HybridPhoneController.pressEnter` 无障碍层增加原生焦点节点 `ACTION_IME_ENTER` 触发。

---

## 5. 2026-10-15 巡检轮次：已修复缺陷与遗留待验证项

### 已修复（均有先红后绿测试证据，详见 docs/ISSUES.md 与 TASKS.md 第五节）

- **ISSUE-201 (P1)**：`HybridPhoneController.launchApp` / `openDeepLink` 遗漏 `userStopRequested()` 停止守卫——用户按下停止胶囊后 Agent 仍会拉起目标应用或打开 DeepLink。已与其余全部交互方法对齐补上门禁。
- **ISSUE-202 (P1)**：Root 探测缓存永不失效 + `HybridPhoneController` 与 `DevicePermissionManager` 双实例背离——用户在授权后开启 Root 模式，执行通道仍读陈旧 `false` 静默降级。已改为共享单实例 + 前台恢复/开启 Root 模式时主动刷新。
- **ISSUE-203 (P2)**：悬浮窗服务「任务结束」后无条件 `delay(1500) + stopSelf()`，与紧随其后的新任务竞态，导致新任务浮窗与前台通知被提前销毁。已改为延迟后重新校验状态。
- **ISSUE-204 (P2)**：断点暂停态悬浮胶囊按钮文案与点击语义矛盾（显示停止、提示继续、点主体却打开主页）。已统一为恢复语义。
- **ISSUE-205 (P3)**：`launch_app` / `open_deeplink` 返回 JSON 字符串插值，含引号/反斜杠入参产生损坏 JSON。已改结构化序列化。

### 遗留待验证（需真机 / 模拟器，当前环境无法执行，不得标记为已验证）

| 项 | 内容 |
|---|---|
| RT-001 | ISSUE-202 真机验证：Magisk 授权root后返回 APP，执行通道是否确实切换到 ROOT（JVM 单测已覆盖接线与缓存语义） |
| RT-002 | ISSUE-204 真机验证：暂停态点击胶囊主体/按钮均触发恢复执行 |
| RT-003 | ISSUE-203 真机验证：任务结束 1.5s 内连续发起新任务，浮窗与通知持续在线 |
| RT-004 | Compose 界面实际渲染（双主题）、无障碍手势真实派发、通知栏展示——本轮仅完成代码级审查与 JVM 测试 |



---

## 6. 2026-10-20 轮次：Skill 子系统彻底重构与高危开关

> 方法：先审计定位根因 → 删除旧实现 → 从零重建 → 用能变红的单测锁定行为 → 全量回归 + Debug 构建。
> 完整审计报告见 `docs/AUDIT_2026-10-20.md`。本轮实跑 **243 项单测 0 失败**，`assembleDebug` 产出 APK。

### 已修复

- **ISSUE-301 (P0)**：旧 `CustomExecutableSkill` 的 `SHELL_COMMAND` 只检查 `isRootAvailable`，任意技能（含导入技能）都能执行 `su -c`，绕过全部全局开关。→ 新引擎双层门禁：技能 `guard.allowShell` + 宿主 `SkillRunOptions.allowShellSteps`（资深设置，且依赖 ROOT 模式）。
- **ISSUE-302 (P0)**：旧流程「执行到危险步骤才确认」，确认后 Runner 用同一工具名重放整条技能，造成重复点击/输入/发送。→ 改为执行前静态风险预检；确认发生在第一步之前；运行期新风险改为安全暂停，不自动续跑。
- **ISSUE-303 (P1)**：`network_security_config.xml` 只放行 4 个域名，与文档宣称的「内网 HTTP 可用」矛盾，真机上局域网模型直接失败。→ 平台层放行 cleartext，判定移至 `NetworkPolicy`；默认拒绝所有 http，资深设置两级放开。
- **ISSUE-304 (P1)**：`syncToSkillRegistry` 只注册不注销，删除的技能仍留在模型工具表。→ `SkillStore.syncInto` 记录已注册 key，按差量注销/注册。
- **ISSUE-305 (P1)**：技能预留名硬编码，缺 `shell_command`/`web_search`/`delegate_task`，技能可覆盖内置工具。→ 统一收敛到 `SkillValidator.RESERVED_TOOL_NAMES`。
- **ISSUE-306 (P1)**：技能写入异常被 `runCatching` 静默吞掉，用户以为保存成功。→ 记录并在技能页展示错误；损坏文件自动备份。
- **ISSUE-307 (P1)**：旧 `parametersSchema` 是自由文本，模型收到空对象 schema。→ 由参数定义生成真实 JSON Schema（类型/枚举/必填）。
- **ISSUE-308 (P1)**：首次启动时工具表懒加载与技能异步注册竞争，首条指令可能看不到技能。→ Application `onCreate` 预同步 + 每次变更后同步。
- **ISSUE-309 (P2)**：旧编辑器删除步骤用闭包捕获 index，Compose 复用会改错步骤；测试执行仅以 Toast 展示长字符串。→ 新编辑器整体重写；测试运行返回 `SkillRunResult` 逐步展示。

### 遗留（未在本轮修复，已登记审计报告）

| 编号 | 级别 | 内容 |
|---|---|---|
| A-04 | P1 | `SafetyGuard` 敏感页关键词表仅 10 条，覆盖不全（建议复用 `RiskActionGuard.PAYMENT_REGEX`） |
| A-05 | P1 | `ToolControlSignal.parse` 仍保留 `contains("\"requires_confirmation\"")` 子串兜底，可能误触发 |
| F-06 | P1 | `Agent.kt` 的 `assistantId` 恒为 null，流式消息共用常量 id |
| Q-01/Q-02 | P2 | `values-zh-rCN` 仍缺 21 条旧字符串；默认（英文）资源中专家模式条目为中文 |
| Q-03 | P2 | 版本号已把 `version.txt` 对齐到 0.2.1，但 README 中 core 依赖示例仍写 0.1.5 |

### 待真机验证（不得标记为已验证）

| 项 | 内容 |
|---|---|
| RT-101 | 开启/关闭「允许 HTTP 传输」后，请求是否立即被放行/拒绝（JVM 单测已覆盖策略语义） |
| RT-102 | 技能「测试运行」在真机上的逐步轨迹与失败定位是否准确 |
| RT-103 | 许愿生成在真实模型上的首次通过率、自动修正触发率 |
| RT-104 | 敏感页暂停 → 用户手动完成 → 恢复执行的全链路 |
| RT-105 | 技能 shell 双层开关：ROOT 模式关闭后，技能 shell 是否同步失效且界面同步 |
