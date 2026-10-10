# AgentPaw 已知问题、技术债与风险登记册 (KNOWN_ISSUES)

> 本文档用于记录 AgentPaw 运行期间的已知缺陷、边缘条件限制、技术债务及应对方案。
> 更新时间：2026-10-10

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

### ISSUE-006: 文档测试统计数据轻微滞后
- **现象描述**：`README_CN.md` 与 `README.md` 中的 Badge 记录单元测试数为 139 项，而当前工程实际测试已扩张至 169 项。
- **影响范围**：纯文档展示差异，不影响任何业务逻辑。
- **缓解策略**：可在下一次版本发布或文档维护批次中一并更新。

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

