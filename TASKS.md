# AgentPaw 任务管理与进度清单 (TASKS)

> 本文档用于跟踪 AgentPaw 的核心研发任务、待办事项、修复计划及实际验证状态。
> 更新时间：2026-10-10

---

## 状态定义说明

- `TODO`：尚未开始。
- `IN_PROGRESS`：正在实施。
- `BLOCKED`：存在明确外部阻塞。
- `DONE`：已完成，满足对应验收标准。
- `VERIFIED`：已完成，且获得了真实构建/测试/命令输出证据验证。

---

## 一、 已完成并经过验证的基线能力 (Verified Milestones)

| 任务 ID | 模块 | 任务描述 | 状态 | 验证证据 |
|---|---|---|---|---|
| **TASK-001** | `core/controller` | 实现三通道阶梯式执行控制器（`ROOT > Shizuku > Accessibility`） | `VERIFIED` | `HybridPhoneControllerPriorityTest.kt` 全部 7 项优先级测试通过 |
| **TASK-002** | `core/security` | 修复大模型 API Key 明文存储问题，接入 Android Keystore AES-256-GCM 硬件加密 | `VERIFIED` | `KeystoreSecretStorage.kt`，JVM 降级单测与真机安全校验 |
| **TASK-003** | `core/guard` | 建立 `RiskActionGuard` 四级高危操作评估与拦截机制 | `VERIFIED` | `RiskActionGuardTest.kt` 全部 7 项规则分类与拦截测试通过 |
| **TASK-004** | `core/masker` | 建立 `SensitiveDataMasker` 敏感凭据与长媒体串脱敏引擎 | `VERIFIED` | `SensitiveDataMaskerTest.kt` 全部测试通过 |
| **TASK-005** | `core/llm` | 实现 OpenAI 兼容 SSE 流式客户端与多模态结构化传输 | `VERIFIED` | `OpenAiCompatibleClientTest.kt` & `MultimodalDtoTest.kt` 通过 |
| **TASK-006** | `core/breakpoint`| 建立断点现场捕获与自然语言续操意图解析（`ResumeIntentDetector`） | `VERIFIED` | `ResumeIntentDetectorTest.kt` 全部 6 项意图与提示词构建测试通过 |
| **TASK-007** | `core/shell` | 纯 Kotlin 进程内脚本沙箱与路径防穿透防护 | `VERIFIED` | `InterpreterTest.kt` 全部 29 项语法解析与隔离测试通过 |
| **TASK-008** | `core/tools` | 手机基础操作工具箱 Schema 与交互信号实现 | `VERIFIED` | `AndroidPhoneToolsTest.kt` & `ToolControlSignalTest.kt` 全部通过 |
| **TASK-009** | `core/device` | 截屏分辨率自适应与单图 384KB 字节上限压降处理 | `VERIFIED` | `AdaptiveScreenshotProcessorTest.kt` 通过 |
| **TASK-010** | `app/runner` | 解耦 `AgentTaskRunner`，实现独立于 Activity 生命周期并持久化运行任务 | `VERIFIED` | 前台服务常驻，崩溃自愈机制落盘测试正常 |
| **TASK-011** | `app/data` | 实现会话持久化与初始化竞态保护（`isInitialized` 状态门控） | `VERIFIED` | `PersistentConversationRepositoryTest.kt` 通过 |
| **TASK-012** | `app/ui` | 双生主题适配（Material 3 & Miuix KMP 0.9.1）与即时热切换 | `VERIFIED` | Compose 主题动态切换与组件预览编译正常 |
| **TASK-013** | `app/build` | 全工程构建与 APK 产物打包 | `VERIFIED` | `./gradlew.bat :app:assembleDebug` 成功产出 `app-debug.apk` |

---

## 二、 待优化与近期技术债务清单 (Backlog & Technical Debt)

| 任务 ID | 优先级 | 任务分类 | 任务内容与缺陷描述 | 状态 | 依赖与验收标准 |
|---|---|---|---|---|---|
| **TASK-101** | `P2` | 文档一致性 | 同步更新 `README.md` 与 `README_CN.md` 中的单元测试总数 Badge（精确更新至 177 项全部通过） | `VERIFIED` | 双语文档与当前 177 项单元测试保持精确一致 |
| **TASK-102** | `P2` | 鲁棒性与异常防护 | `AgentFloatingService.start` 与 `MainActivity` 增加 Android 14+ 前台服务异常防御包裹 | `VERIFIED` | `runCatching` 拦截 `ForegroundServiceStartNotAllowedException`，提供降级日志 |
| **TASK-103** | `P2` | 网络与搜索优化 | `DuckDuckGoSearchBackend` 增加网络重试与 HTTP 403/429 限流友好降级提示机制 | `VERIFIED` | 2 次尝试重试机制，限流返回友好通告，`DuckDuckGoSearchBackendTest` 验证通过 |
| **TASK-104** | `P3` | 性能与内存优化 | `PersistentConversationRepository` 冷启动会话历史按文件修改时间截取上限 (`MAX_HISTORY_ITEMS`) | `VERIFIED` | `PersistentConversationRepositoryTest` 验证超量历史安全截取 |
| **TASK-105** | `P3` | 路线图新特性 | 探索端侧轻量视觉目标检测（UI Element Detector）减少大模型截屏频次 | `TODO` | 预研端侧 TFLite / ONNX 方案接入架构 |
| **TASK-106** | `P3` | 路线图新特性 | 任务操作流一键导出诊断包（包含脱敏日志与步骤截图链） | `TODO` | 生成标准 zip 诊断归档包便于复盘 |

---

## 三、 真实用户体验与交互缺陷修复清单 (User Experience & Interaction Remediations)

| 问题 ID | 优先级 | 类别 | 缺陷与体验问题说明 | 状态 | 修复文件与验证证据 |
|---|---|---|---|---|---|
| **ISSUE-UX-001** | `P1 (高)` | 稳定性 / 崩溃 | 历史详情页消息列表由于空/重复工具调用 ID 导致 Compose `IllegalArgumentException: Key was already used` 崩溃 | `VERIFIED` | [`HistoryDetailScreen.kt`](file:///d:/Projects/AgentPaw/app/src/main/java/com/paw/agent/ui/history/HistoryDetailScreen.kt)，`itemsIndexed` 组合键 `"${message.id}_$index"` |
| **ISSUE-UX-002** | `P2 (中)` | UI / 交互 | 历史详情页底部操作栏与全面屏系统手势导览条重叠遮挡 | `VERIFIED` | [`HistoryDetailScreen.kt`](file:///d:/Projects/AgentPaw/app/src/main/java/com/paw/agent/ui/history/HistoryDetailScreen.kt)，增加 `.navigationBarsPadding()` |
| **ISSUE-UX-003** | `P1 (高)` | 核心业务流 | 会话历史回溯恢复（Restore）时的并发竞态与残留断点污染问题 | `VERIFIED` | [`PersistentConversationRepository.kt`](file:///d:/Projects/AgentPaw/app/src/main/java/com/paw/agent/data/conversation/PersistentConversationRepository.kt) 同步更新，`AgentPawApplication.kt` 联动重置断点，单测验证通过 |
| **ISSUE-UX-004** | `P2 (中)` | 权限 / 功能 | 设置页“自动化控制通道”缺失 Android 13+ (API 33+) 通知权限开关与状态感知 | `VERIFIED` | [`SettingsScreens.kt`](file:///d:/Projects/AgentPaw/app/src/main/java/com/paw/agent/ui/settings/SettingsScreens.kt)，添加 `PermissionRow` 与动态权限请求器 |
| **ISSUE-UX-005** | `P2 (中)` | 引导 / 易用性 | 首页对话空状态歧义引导与引导卡片缺失（已配置 API Key 时仍显示未配置警告） | `VERIFIED` | [`Common.kt`](file:///d:/Projects/AgentPaw/app/src/main/java/com/paw/agent/ui/components/Common.kt)，支持快捷启航建议卡片与区分欢迎文案 |
| **ISSUE-UX-006** | `P2 (中)` | 引导 / 易用性 | 输入栏在未配置 API Key 状态下误导为可发送，无直达配置指引 | `VERIFIED` | [`ChatScreen.kt`](file:///d:/Projects/AgentPaw/app/src/main/java/com/paw/agent/ui/chat/ChatScreen.kt)，动态占位提示、设置图标引导色与快捷点击直达 |

---

## 四、 只读审计安全缺陷加固清单 (Audit Remediation Items)

| 任务 ID | 优先级 | 模块 | 缺陷与风险说明 | 状态 | 涉及文件与验收标准 |
|---|---|---|---|---|---|
| **TASK-SEC-001** | `P0 (严重)` | `app/runner` | 修复 `confirmAndExecuteRiskAction` 执行授权动作后因 `_isGenerating` 导致 `resumeBreakpoint` 短路卡死的问题 | `VERIFIED` | [`AgentTaskRunner.kt`](file:///d:/Projects/AgentPaw/app/src/main/java/com/paw/agent/runner/AgentTaskRunner.kt) 状态复位，`AgentExecutionControllerTest` 与编译验证通过 |
| **TASK-SEC-002** | `P1 (高)` | `core/tools` | 修复 `click_element` 未匹配节点时仅按 bounds 点击绕过全屏支付/删除风控的问题 | `VERIFIED` | [`AndroidPhoneTools.kt`](file:///d:/Projects/AgentPaw/agentpaw-core/src/main/java/com/paw/agent/core/tool/android/AndroidPhoneTools.kt) 全局文本检测，`AndroidPhoneToolsTest` 验证拦截 |
| **TASK-SEC-003** | `P1 (高)` | `core/tools` | `open_deeplink` 补充 URI scheme 协议白名单，禁止危险协议（如 `intent:`, `file:`, `content:` 等） | `VERIFIED` | [`AndroidPhoneTools.kt`](file:///d:/Projects/AgentPaw/agentpaw-core/src/main/java/com/paw/agent/core/tool/android/AndroidPhoneTools.kt)，`AndroidPhoneToolsTest` 阻断单测通过 |
| **TASK-SEC-004** | `P1 (高)` | `core/tools` | 敏感（密码/支付）屏幕在 `take_screenshot` 中禁止将图像 Base64 发往云端 LLM，统一触发安全暂停 | `VERIFIED` | [`AndroidPhoneTools.kt`](file:///d:/Projects/AgentPaw/agentpaw-core/src/main/java/com/paw/agent/core/tool/android/AndroidPhoneTools.kt)，`AndroidPhoneToolsTest` 验证无图像数据泄露 |
| **TASK-SEC-005** | `P1 (高)` | `app/security`| 收紧 `network_security_config.xml` 全局明文放行，仅对 localhost/127.0.0.1/10.0.2.2/local 放行 | `VERIFIED` | [`network_security_config.xml`](file:///d:/Projects/AgentPaw/app/src/main/res/xml/network_security_config.xml)，`:app:assembleDebug` 产物解析构建通过 |
| **TASK-SEC-006** | `P1 (高)` | `core/skill` | 自定义 Skill 步骤（`TAP_COORDINATE`, `TAP_ELEMENT`, `INPUT_TEXT`）接入同一套 `SafetyGuard` 风险门控 | `VERIFIED` | [`CustomSkillModel.kt`](file:///d:/Projects/AgentPaw/agentpaw-core/src/main/java/com/paw/agent/core/skill/CustomSkillModel.kt)，全套单元测试通过 |
| **TASK-SEC-007** | `P2 (中)` | `core/tools` | `InputTextTool` 成功响应修复字符串直接插值，采用结构化序列化防转义破坏 JSON | `VERIFIED` | [`AndroidPhoneTools.kt`](file:///d:/Projects/AgentPaw/agentpaw-core/src/main/java/com/paw/agent/core/tool/android/AndroidPhoneTools.kt)，`AndroidPhoneToolsTest` 引号单测通过 |
| **TASK-SEC-008** | `P2 (中)` | `core/device` | 剪贴板中转输入后自动清空剪贴板，防止凭据与输入内容残留 | `VERIFIED` | [`HybridPhoneController.kt`](file:///d:/Projects/AgentPaw/agentpaw-core/src/main/java/com/paw/agent/device/HybridPhoneController.kt) 安全清空剪贴板 |
| **TASK-SEC-009** | `P2 (中)` | `core/agent` | `Agent.kt` 本地图片读取增加安全路径沙箱与后缀白名单校验，禁止跨目录读取任意敏感文件 | `VERIFIED` | [`Agent.kt`](file:///d:/Projects/AgentPaw/agentpaw-core/src/main/java/com/paw/agent/core/agent/Agent.kt) 仅限合法图像扩展名与 10MB 限额 |
| **TASK-SEC-010** | `P2 (中)` | `core/llm` | `OpenAiCompatibleClient.isLocalOrPrivateAddress` 补充 IPv6 私有地址 (ULA/Link-Local) 及防伪造校验 | `VERIFIED` | [`OpenAiCompatibleClientSecurityTest.kt`](file:///d:/Projects/AgentPaw/agentpaw-core/src/test/java/com/paw/agent/core/llm/OpenAiCompatibleClientSecurityTest.kt) 验证通过 |
| **TASK-SEC-011** | `P2 (中)` | `core/device` | `HybridPhoneController.pressEnter` 无障碍通道增加原生焦点节点 `ACTION_IME_ENTER` 触发 | `VERIFIED` | [`HybridPhoneController.kt`](file:///d:/Projects/AgentPaw/agentpaw-core/src/main/java/com/paw/agent/device/HybridPhoneController.kt) 编译通过与单元测试通过 |

