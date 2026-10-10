# AgentPaw 系统架构与设计文档 (ARCHITECTURE)

> 本文档基于对当前代码库实际组件、调用链与数据流的真实调查建立。
> 更新时间：2026-10-10

---

## 1. 系统架构全景

```mermaid
graph TD
    subgraph UI_Layer["UI 表现层 (Jetpack Compose)"]
        Chat["ChatScreen (气泡流式渲染/断点恢复卡片/高风险授权卡片)"]
        Settings["SettingsScreens & ExpertSettingsPage"]
        History["HistoryScreen & TaskCompareScreen"]
        SkillsUI["CustomSkillsScreen (技能工坊)"]
    end

    subgraph Runner_Layer["任务调度与前台服务层"]
        Runner["AgentTaskRunner (独立于 Activity 生命周期)"]
        FloatService["AgentFloatingService (前台常驻服务 & 跨应用胶囊)"]
        ExecController["AgentExecutionController (全局状态总线)"]
    end

    subgraph Core_Agent["核心推理层 (agentpaw-core)"]
        AgentCore["Agent.kt (ReAct 感知-决策-行动驱动器)"]
        Guard["RiskActionGuard.kt (四级高危拦截)"]
        Pacing["AdaptivePacingEngine.kt (智能步间节奏)"]
        Breakpoint["TaskBreakpoint & ResumeIntentDetector"]
        LLM["OpenAiCompatibleClient (SSE 流式 + 结构化多模态)"]
    end

    subgraph Device_Control["三通道混合控制引擎 (HybridPhoneController)"]
        PriorityDispatch["加权调度决策: ROOT > Shizuku > Accessibility"]
        RootCtrl["RootController (su 提权/底层按键/免跳转授权)"]
        ShizukuCtrl["ShizukuController (ADB 特权 Binder 通道)"]
        A11yService["AgentAccessibilityService (UI 树分析/手势模拟/Android 11+ 原生截屏)"]
        Processor["AdaptiveScreenshotProcessor (分辨率自适应 & 384KB 上限)"]
    end

    subgraph Data_Storage["数据仓储与安全边界"]
        ConvRepo["PersistentConversationRepository (图片分离落盘/原子写入)"]
        SecStorage["KeystoreSecretStorage (AES-256-GCM 硬件加密)"]
        SettingRepo["DataStoreSettingsRepository (Preferences DataStore)"]
        SkillRepo["CustomSkillRepository (自定义技能持久化)"]
    end

    Chat --> Runner
    Runner --> AgentCore
    Runner --> ConvRepo
    Runner --> FloatService
    Runner --> ExecController
    AgentCore --> LLM
    AgentCore --> Guard
    AgentCore --> Pacing
    AgentCore --> Breakpoint
    AgentCore --> PriorityDispatch
    PriorityDispatch --> RootCtrl
    PriorityDispatch --> ShizukuCtrl
    PriorityDispatch --> A11yService
    PriorityDispatch --> Processor
    Settings --> SettingRepo
    SettingRepo --> SecStorage
    SkillsUI --> SkillRepo
    History --> ConvRepo
```

---

## 2. 关键调用链与数据流

### 2.1 任务启动与执行主循环 (Execution Loop)

```mermaid
sequenceDiagram
    autonumber
    actor User as 用户
    participant Chat as ChatScreen / ChatViewModel
    participant Runner as AgentTaskRunner
    participant Agent as Agent.kt
    participant LLM as OpenAiCompatibleClient
    participant Tools as AndroidPhoneTools
    participant Ctrl as HybridPhoneController

    User->>Chat: 发送任务指令 (e.g. "帮我打开设置开启深色模式")
    Chat->>Runner: startTask(config, promptText)
    Runner->>Runner: 启动 AgentFloatingService (前台服务保活)
    Runner->>Agent: agent.run(config, history, isCancelled)
    loop 推理与行动轮次 (最多 maxToolRounds 步)
        Agent->>LLM: chatStream(messages, tools, multimodal)
        LLM-->>Agent: SSE 流式返回 tokens & tool_calls
        Agent->>Tools: 调用目标工具 (e.g. tap, input_text, launch_app)
        Tools->>Ctrl: 执行动作
        Ctrl-->>Tools: 返回执行状态
        Tools-->>Agent: 包装 ToolResult (经 SensitiveDataMasker 脱敏)
        Agent-->>Runner: 发射 AgentEvent (ToolStarted / ToolFinished / AssistantDelta)
        Runner-->>Chat: 实时更新界面消息与状态
    end
    Agent-->>Runner: AgentEvent.Completed
    Runner->>Runner: 清理 running_task.json，通知完成
```

### 2.2 三通道混合控制调度策略 (Root > Shizuku > Accessibility)

当 `HybridPhoneController` 接收到交互指令（如 `tap(xNormalized, yNormalized)`）时，严格遵循以下阶梯式调度链路：

```text
[接收指令与归一化坐标]
       │
       ▼
 检查是否已被用户请求停止 (AgentAccessibilityService.isStopRequested)
       │ (否)
       ▼
 坐标还原: toPhysical() 基于最近一次截图物理分辨率映射为实际像素
       │
       ▼
【第 1 优先级】Root 引擎可用？(isRootAllowed: controlMode == ROOT/AUTO && root.isAvailable)
       ├── 是 ──> 执行 rootController.tap(x, y) ──> 成功 ──> 延时稳定 ──> 返回 true
       └── 否 / 失败
              │
              ▼
【第 2 优先级】Shizuku 引擎可用？(isShizukuAllowed: shizuku.isAvailable)
       ├── 是 ──> 执行 shizukuController.tap(x, y) ──> 成功 ──> 延时稳定 ──> 返回 true
       └── 否 / 失败
              │
              ▼
【第 3 优先级 (兜底)】无障碍服务可用？(AgentAccessibilityService.instance != null)
       ├── 是 ──> 执行 service.clickAt(x, y) ──> 成功 ──> 延时稳定 ──> 返回 true
       └── 否 / 失败 ──> 降级失败，返回 false
```

### 2.3 高风险操作拦截与二次确认 (Risk Action Guard)

```mermaid
sequenceDiagram
    autonumber
    participant Model as LLM 模型
    participant Agent as Agent.kt
    participant Tool as AndroidPhoneTools
    participant Guard as RiskActionGuard
    participant Runner as AgentTaskRunner
    actor User as 用户

    Model->>Agent: 发起工具调用 (e.g. tap 到支付按钮 / input_text 确认转账)
    Agent->>Tool: 执行前调用 Guard.assess(toolName, args, screenState)
    Guard-->>Tool: 返回 RiskLevel (CRITICAL / HIGH / MODERATE)
    alt 需要确认且参数未携带 confirmed: true
        Tool-->>Agent: 返回 ToolControlSignal.RequiresConfirmation
        Agent-->>Runner: ToolFinished 携带拦截信号
        Runner->>Runner: 创建 TaskBreakpoint (包含风险原因、目标动作、待确认参数)
        Runner->>Runner: 原子落盘 active_breakpoint.json 并暂停 Agent 协程
        Runner-->>User: UI 弹出专属高风险拦截授权卡片
        User->>Runner: 用户核验后点击【确认执行】(confirmAndExecuteRiskAction)
        Runner->>Agent: executeDirectTool(toolName, confirmedArgs[confirmed=true])
        Runner->>Runner: 记录执行结果并从当前断点平滑恢复后续流程
    else 普通安全动作或已显式授权
        Tool->>Tool: 正常驱动底层控制器执行
    end
```

### 2.4 断点续操与防重复执行机制

1. **断点捕获**：用户点击红色停止胶囊、或系统检测到高危操作、或进程意外终止时，自动抓取 `StepSnapshot` 列表，包含各步骤工具名、精简参数、执行结果与在途回滚状态。
2. **意图解析**：当用户输入“继续”、“继续执行刚才的操作”或“从第3步继续”时，`ResumeIntentDetector` 识别为续操意图。
3. **上下文动态注入**：生成系统级严格防重提示词 `【断点续操恢复指令（严格执行）】`，列出已完成的步骤清单，明确严禁大模型重复执行上一轮已完成的点击或输入操作。

---

## 3. 安全架构与隐私边界

1. **凭据隔离与硬件加密**：
   - 使用 Android KeyStore AES-256-GCM 进行密钥加密。
   - `KeystoreSecretStorage` 确保磁盘中只存储 `enc:gcm:<base64>` 密文。
   - 配置 `backup_rules.xml` 与 `data_extraction_rules.xml`，阻断 Android 云备份与 adb 提取。
2. **网络通信合规**：
   - 第三方公网 LLM 强制 HTTPS 通信。
   - 本地内网（`10.0.2.2`, `127.0.0.1`, `localhost`, `192.168.x.x`）允许 HTTP 明文调试。
3. **命令注入防御**：
   - `ShellEscape.quoteForSh()` 对 Root 与 Shizuku 的 Shell 参数使用强单引号包裹与单引号转义，杜绝 `; rm -rf` 等拼接式命令注入漏洞。
   - 进程内脚本沙箱限制在 `filesDir/sandbox` 根目录下，严格拒绝相对路径穿透（`../`）。
4. **视觉与日志脱敏**：
   - `SensitiveDataMasker` 在日志展示前剔除 API Key、Bearer Token 及超过 300 字符的 Base64 媒体串。
   - 截屏文件落盘采用单独的 `images/` 目录与 `file://` 协议引用，清空会话时进行物理级删除。
