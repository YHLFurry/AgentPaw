# AgentPaw 项目全局概览 (PROJECT_OVERVIEW)

> 本文档根据代码库真实代码、构建配置与测试验证结果建立，真实反映 AgentPaw 的架构设计与工程现状。
> 更新时间：2026-10-10

---

## 1. 项目基本信息

- **项目名称**：AgentPaw 🐾（萌爪 Agent）
- **仓库地址**：[https://github.com/YHLFurry/AgentPaw](https://github.com/YHLFurry/AgentPaw)
- **当前 Git 分支**：`feat/controller-priority-root-shizuku-accessibility`
- **项目定位**：运行在 Android 手机上的自主智能体（Autonomous Agent）交互与执行框架。具备多模态视觉感知、多轮工具调用、阶梯式混合控制（ROOT > Shizuku > 无障碍）、断点续操恢复与高风险动作拦截能力。
- **发布版本**：v0.1.5 (Build 6)
- **开源协议**：Apache License 2.0

---

## 2. 技术栈规格

| 维度 | 规格 / 选型 | 说明 |
|---|---|---|
| **目标平台** | Android 8.0+ (API 26+) | 深度适配 Android 8.0 ~ Android 15 (minSdk 26, targetSdk 36, compileSdk 37) |
| **编程语言** | Kotlin 2.2.21 | 跨模块统一对齐 stdlib，兼顾 JVM 17 目标字节码 |
| **构建系统** | Gradle 8.13 + AGP 8.13.2 | JDK 21，启用配置缓存与并行构建 |
| **UI 表现层** | Jetpack Compose + Miuix KMP | Compose BOM 2026.06.01, Material 3 与 Miuix (HyperOS 风格) 双主题热切换 |
| **异步流与并发** | Kotlin Coroutines 1.10.2 + Flow | Channel (Conflated) + Mutex + StateFlow 状态编排 |
| **数据序列化** | kotlinx.serialization 1.9.0 | JSON 严格模式 + 容错解析 |
| **本地网络与 LLM** | OkHttp 4.12.0 + SSE 流式协议 | 标准 OpenAI 兼容协议，支持文本 + 结构化多模态 `image_url` |
| **凭据安全** | Android Keystore | 硬件隔离 AES-256-GCM 加密，严禁 API Key 明文落盘 |
| **特权与执行** | 混合三通道引擎 | ROOT (`su`) > Shizuku Binder (API 13.1.5) > 无障碍服务 (`AccessibilityService`) |
| **自动化测试** | JUnit 4 + Coroutines Test | 169 项全量自动化单元测试（147 核心 + 22 应用） |

---

## 3. 目录结构与模块划分

```text
AgentPaw/
├── agentpaw-core/                 # 核心独立库模块（可发布至 Maven，解耦无 Compose 依赖）
│   ├── src/main/AndroidManifest.xml # 声明 ShizukuProvider 与 AgentAccessibilityService
│   ├── src/main/java/com/paw/agent/
│   │   ├── core/
│   │   │   ├── agent/             # Agent 核心推理循环 (Agent.kt)、风险门控 (RiskActionGuard.kt)、
│   │   │   │                      # 数据脱敏 (SensitiveDataMasker.kt)、步间节奏 (AdaptivePacingEngine.kt)、
│   │   │   │                      # 断点续操 (breakpoint/TaskBreakpoint.kt, ResumeIntentDetector.kt)
│   │   │   ├── llm/               # OpenAI 兼容客户端 (OpenAiCompatibleClient.kt)、多模态 DTO
│   │   │   ├── model/             # 领域模型 (Conversation, Message, ToolCall, ToolDefinition, ToolResult)
│   │   │   ├── search/            # 免 Key 网页搜索 (DuckDuckGoSearchBackend.kt)
│   │   │   ├── shell/             # 纯 Kotlin 进程内沙箱脚本引擎 (Lexer, Parser, Interpreter)
│   │   │   ├── skill/             # 技能模型与热注册 (AgentSkill.kt, CustomSkillModel.kt)
│   │   │   └── tool/              # 12项手机控制工具、子智能体委派、沙箱脚本工具
│   │   └── device/                # 设备控制与系统特权层
│   │       ├── HybridPhoneController.kt    # 三通道加权调度中心 (ROOT > Shizuku > Accessibility)
│   │       ├── AdaptiveScreenshotProcessor.kt # 自适应分辨率与 384KB 截屏压缩限制
│   │       ├── DevicePermissionManager.kt  # 运行时授权检测与 Root 免跳转静默激活
│   │       ├── ShellEscape.kt              # Shell 指令严格防注入转义
│   │       ├── accessibility/              # AgentAccessibilityService 手势与 UI 节点树检索
│   │       ├── root/                       # RootController (基于 su 执行底层事件与截屏)
│   │       ├── shizuku/                    # ShizukuController & ShizukuInitializer (Binder 通道)
│   │       └── floating/                   # 悬浮停止按钮与前台应用状态监控
│   └── src/test/java/             # 147 项核心功能单元测试
├── app/                           # Android 宿主应用模块 (Jetpack Compose 交互界面)
│   ├── src/main/AndroidManifest.xml # 宿主 Manifest (MainActivity, AgentFloatingService, 权限与规则)
│   ├── src/main/java/com/paw/agent/
│   │   ├── AgentPawApplication.kt # 应用程序入口与 AppContainer 依赖注入容器
│   │   ├── MainActivity.kt        # 单 Activity 架构与边缘无感适配
│   │   ├── data/
│   │   │   ├── conversation/      # 持久化会话仓储 (PersistentConversationRepository.kt, 图片解耦落盘)
│   │   │   ├── security/          # 凭据硬件加密 (KeystoreSecretStorage.kt)
│   │   │   ├── settings/          # DataStoreSettingsRepository.kt
│   │   │   └── skill/             # 自定义技能持久化 (CustomSkillRepository.kt)
│   │   ├── runner/
│   │   │   └── AgentTaskRunner.kt # 独立于 UI 生命周期的任务执行调度器、断点状态机、崩溃恢复
│   │   └── ui/
│   │       ├── chat/              # 聊天主页面 (ChatScreen.kt, ChatViewModel.kt, MessageBubble.kt)
│   │       ├── history/           # 任务历史、详情与双任务多维对比 (HistoryScreen.kt, TaskCompareScreen.kt)
│   │       ├── skills/            # 自定义技能工坊管理界面 (CustomSkillsScreen.kt)
│   │       ├── settings/          # 设置主页与各二级页面 (模型、生成、控制、外观、资深专家)
│   │       ├── about/             # 关于界面与萌爪长按解锁资深模式 (AboutScreen.kt)
│   │       ├── floating/          # 跨应用前台服务与悬浮状态胶囊 (AgentFloatingService.kt)
│   │       ├── navigation/        # Compose 路由导航中枢 (AgentPawApp.kt)
│   │       └── theme/             # Material 3 & Miuix 双生设计主题体系
│   └── src/test/java/             # 22 项 UI 与数据仓储单元测试
├── docs/                          # 技术架构审计与路线图 (AGENT_AUDIT_AND_ROADMAP.md)
└── build.gradle.kts / settings.gradle.kts / gradle.properties
```

---

## 4. 核心功能及模块职责

1. **核心 Agent 推理引擎 (`agentpaw-core`)**
   - 驱动感知-思考-行动循环，多轮 Tool Calling 交互。
   - 工具结果脱敏 (`SensitiveDataMasker`) 与 Token 消耗防护。
   - 四级高风险动作评估与用户确认门控 (`RiskActionGuard`)。
   - 步间智能自适应节奏 (`AdaptivePacingEngine`)。
2. **三通道混合手机控制器 (`HybridPhoneController`)**
   - 严格执行层级：`ROOT > Shizuku > Accessibility`。
   - 纯 Root 模式免无障碍驱动；Shizuku 次优 ADB 特权；无障碍兜底保证兼容性。
   - 智能文本输入：支持直接注入、Root/Shizuku 剪贴板中转注入与软键盘保底。
3. **断点续操与任务恢复系统 (`AgentTaskRunner` & `TaskBreakpoint`)**
   - 中途停止或高危拦截时生成完整快照并落盘。
   - 自然语言续操意图检测 (`ResumeIntentDetector`)，动态注入防重复上下文。
   - 进程被杀或系统回收后自动恢复现场并提醒用户。
4. **数据安全与凭据保护 (`KeystoreSecretStorage`)**
   - Android Keystore AES-256-GCM 硬件加密存储大模型 API Key。
   - 严格配置系统备份规则，禁止将密钥导出至云端备份或 ADB 数据迁移。
5. **双生 UI 交互表现层 (`app/ui`)**
   - 支持 Material You 动态色彩与 Miuix (澎湃 OS 风格) 柔润圆角质感。
   - 任务历史回溯与量化指标对比（步骤数、工具分布、耗时）。
