# AgentPaw 🐾

<p align="center">
  <img src="docs/icon-preview.png" alt="AgentPaw 萌爪 Logo" width="128" height="128" style="border-radius: 28px;" />
</p>

<p align="center">
  <strong>运行在 Android 手机上的自主智能体（Autonomous Agent）交互与执行框架</strong>
</p>

<p align="center">
  <a href="README_CN.md">🇨🇳 简体中文</a> | <a href="README.md">🇺🇸 English</a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/平台-Android_8.0+_(API_26+)-3DDC84?logo=android&logoColor=white" alt="Platform" />
  <img src="https://img.shields.io/badge/语言-Kotlin_2.2.21-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin" />
  <img src="https://img.shields.io/badge/界面-Material_3_&_Miuix-4285F4?logo=jetpackcompose&logoColor=white" alt="UI" />
  <img src="https://img.shields.io/badge/单元测试-174_全部通过-brightgreen" alt="Tests" />
  <img src="https://img.shields.io/badge/开源协议-Apache_2.0-blue.svg" alt="License" />
</p>

---

## 🌟 简介

**AgentPaw** 是一款专为 Android 移动平台设计的高性能自主智能体框架。基于现代大语言模型（LLM）的多轮推理与工具调用（Tool Calling）能力，结合 Android 设备级自动化通道，AgentPaw 能够自主感知屏幕内容、规划多步任务并模拟人手操作，驱动各类手机应用完成复杂工作流。

无论是日常高频重复操作的自动化、定制个人 AI 手机助手，还是移动端自动化脚本编排，AgentPaw 均提供了生产级稳定的底层引擎、丰富的控制中枢与极致流畅的双生交互体验。

---

## ✨ 核心特性

### ⏯️ 断点续操系统 (Task Breakpoint & Resume)
- **现场完整快照**：任务中途暂停或用户手动中止时，系统自动持久化断点现场 `TaskBreakpoint`（含任务总目标、已完成步骤清单、在途状态回滚及现场快照）。
- **智能意图理解**：`ResumeIntentDetector` 智能解析用户自然的续操口令，如“*继续*”、“*继续做*”、“*好的*”，甚至支持带修正指令的复杂续操（如“*继续，刚才那一步点第二个按钮*”）。
- **防重复上下文注入**：恢复时动态生成包含已执行清单的系统级防重 Prompt，确保大模型从断点精准继续，坚决避免重复点击或多余执行。
- **一键快捷唤醒**：聊天界面底部提供醒目的断点恢复卡片，配合系统悬浮胶囊一键即可让 Agent 满血恢复。

### 🤹 自定义 Skill 技能系统
- **可视化技能工坊**：应用内提供专属管理界面，支持新建、编辑、开关与删除自定义技能。
- **自定义 Schema 与模板**：自由配置技能参数的 JSON Schema 与 Prompt 模版。
- **热注入运行时**：新创建或启用的技能即时热注册进 LLM Function Calling 工具集，无需重启即刻投入使用。

### ⚡ 三级混合执行架构 (ROOT > Shizuku > 无障碍)
- **ROOT 模式 (`su`)**：第一优先级！针对已 Root 设备直接以纯 Root 模式进行全功能驱动、物理模拟、静默应用拉起与高帧率底层截屏，完全免无障碍服务。
- **Shizuku 模式**：第二优先级！未 Root 时借助 Shizuku (ADB privileges) 纯 Shizuku 模式执行高速静默模拟与系统指令。
- **无障碍服务 (Accessibility)**：最终兜底底座！免 Root / 免 Shizuku 环境下深度解析 UI 树层级，支持精准点击、滑动与节点定位。
- **阶梯式弹性调度**：`HybridPhoneController` 严格按照 `ROOT > Shizuku > 无障碍` 优先级运作，能通过 ROOT 则纯 ROOT 操作，以此类推，最终无障碍兜底，确保任务从不落空。

### ⌨️ 文字直接键入与键盘保底机制
- **输入框智能锚定**：优先智能定位当前界面的焦点输入框、可编辑文本节点或聊天框。
- **直接写入加速**：首选利用 `ACTION_SET_TEXT` 或剪贴板直接注入文本，兼顾超高输入速度与准确度。
- **软键盘智能保底**：当目标应用限制直接注入时，自动激活焦点并模拟软键盘输入与剪贴板粘贴保底。

### ⏱️ AI 智能步间节奏引擎 (AdaptivePaceEngine)
- 告别生硬死板的固定等待时间！系统基于操作类型（点击、长按、滑动、文字输入）和页面转场复杂度，自适应动态调节每一步骤间的等待间隔，有效杜绝页面未渲染完毕导致的误触。

### 📜 任务历史记录与回溯对比
- **持久化记录**：每次任务的完整对话轮次、各步骤耗时与操作详情永久留存，随时翻阅。
- **历史回溯**：支持一键将历史任务的上下文重新载入到当前主对话会话中继续交流。
- **双任务量化对比**：提供两两任务在步骤数、总耗时、各工具调用分布上的多维度量化对比视图。

### 🎨 双生主题（Material You & Miuix）
- **Material 3（默认）**：现代化 Material You 质感设计，在 Android 12+ 上原生支持根据系统壁纸动态取色。
- **Miuix 主题**：灵感源自澎湃 OS / MIUI 的精致组件设计风格，轻盈柔润。
- **即时热切换**：在设置中切换主题秒级生效，无需重启应用，且与大模型配置完全解耦。

### ℹ️ “关于”界面与资深（专家）模式隐藏机制
- 包含版本更新记录、开发者信息与架构说明。
- **隐藏资深模式**：如“视觉与语言分离显示模式”等极客进阶配置默认处于安全隐藏状态；在“关于”界面中长按萌爪大图标伴随触感振动反馈即可解锁资深模式！

### 🛡️ 纯 Kotlin 沙箱脚本 (`run_script`)
- 进程内安全 Shell 解析器（`Lexer` → `Parser` → `Interpreter`），内置 30+ 常用命令，支持管道 `|`、逻辑流控制与数学表达式计算，无本地二进制注入漏洞风险。

### 🔍 免 Key 网页搜索与子 Agent 委派
- **DuckDuckGo 即时搜索**：无需申请任何第三方 API Key，开箱即用。
- **子 Agent 委派 (`delegate_task`)**：针对需要长链条独立推理的任务，委派给具有独立 Prompt 与深度限制的子智能体执行，绝不污染主会话上下文。

---

## 📱 用户使用指南

### 1. 安装与系统要求
- 支持 Android 8.0 (API 26) 及更高版本。
- **系统版本与视觉能力说明**：由于 Android 系统限制，免 Root/Shizuku 模式下的无障碍原生截图仅在 Android 11 (API 30)+ 上可用。如果设备运行 Android 8–10，基础的 UI 树层级分析与控件点击正常工作；如需多模态视觉感知与屏幕截图能力，请开启 **Shizuku** 或 **Root** 模式。
- 前往 GitHub Releases 下载最新的 `app-debug.apk`，或从源码编译安装。

### 2. 权限开启（根据需要按需开启）
1. **无障碍服务**：进入手机「系统设置」→「辅助功能 / 无障碍」→ 开启「AgentPaw」（基础屏幕感知与点击必需）。
2. **悬浮窗权限**：允许 AgentPaw 显示在其他应用上层，便于跨应用执行时弹出停止与续操浮窗。
3. **Shizuku 授权（推荐）**：如果手机安装了 Shizuku 并已启动，授权 AgentPaw 即可获得高速 ADB 自动化及低版本系统全功能截图体验。
4. **ROOT 权限（可选）**：如果是玩机 Root 设备，可在 Magisk / KernelSU / APatch 中为本应用授权。

### 3. 配置大语言模型 (LLM)
AgentPaw 兼容标准 OpenAI 协议格式：
- **内置预设**：OpenAI、DeepSeek、Google Gemini、Moonshot (Kimi)、Ollama（本地运行无需外部 Key，支持 `http://10.0.2.2:11434/v1`、`http://localhost:11434/v1`、`http://127.0.0.1:11434/v1` 或私有局域网 IP 如 `http://192.168.x.x:11434/v1`）。
- **网络与通信安全**：私有局域网/本机模型支持明文 HTTP 快速调试；公网第三方 API 强制使用 HTTPS 连接以确保 API Key 与敏感指令传输安全。
- **自定义服务**：支持任何兼容 OpenAI 接口的自建模型服务（如 vLLM、LM Studio、OneAPI 等）。
- **隐私保护承诺**：您的所有 API Key 仅保存在本地设备内部的 Android Keystore 加密存储中，绝不回传任何第三方统计或埋点。

### 4. 开启任务与断点续操
1. 在聊天框向 AgentPaw 发送自然语言任务（例如：“*帮我打开设置并开启深色模式*”）。
2. 如果中途需要临时接管操作，点击屏幕红色的悬浮停止胶囊或聊天框 **Stop** 按钮即可暂停。
3. 随时可以在聊天卡片点击 **[▶ 继续执行]**，或输入“*继续刚才的任务*”，AgentPaw 会从当前断点继续平滑执行剩余步骤。

### 5. 解锁资深模式
进入「设置」→「关于」，对着居中的萌爪大图标长按数秒，触发振动后即可解锁隐藏的专家级高级设置项。

---

## 🏗️ 代码与模块架构

```
AgentPaw
├── agentpaw-core/                 ← 纯 Kotlin & 可独立复用的 Android 核心库模块
│   ├── src/main/java/com/paw/core/
│   │   ├── agent/                 # Agent 核心循环、多轮调度、事件总线
│   │   ├── controller/            # 无障碍、Shizuku、ROOT、混合弹性控制器
│   │   ├── executor/              # 断点状态机、续操意图识别器、自适应节奏引擎
│   │   ├── llm/                   # OpenAI 兼容 SSE 流式客户端、多模态视觉
│   │   ├── model/                 # TaskBreakpoint、Message、ToolCall、ToolDefinition
│   │   ├── search/                # DuckDuckGo 免 Key 搜索后端
│   │   ├── shell/                 # 纯 Kotlin 沙箱解析器、AST 解释器与命令集
│   │   ├── skill/                 # 自定义 Skill 模型与热注册管理器
│   │   └── tool/                  # 手机控制工具、脚本工具、子智能体委派
├── app/                           ← Jetpack Compose 应用主工程模块
│   ├── src/main/java/com/paw/agent/
│   │   ├── data/                  # AppSettings DataStore、任务历史数据库
│   │   ├── service/               # AgentAccessibilityService、AgentFloatingService 悬浮窗
│   │   └── ui/
│   │       ├── chat/              # 聊天主页面、断点续操卡片、气泡流式渲染
│   │       ├── history/           # 任务历史列表、任务详情、双任务对比页面
│   │       ├── skill/             # 技能工坊列表、技能新增与编辑界面
│   │       ├── settings/          # 大模型参数设置、外观主题设置
│   │       ├── about/             # 关于页面（长按触发资深专家模式）
│   │       └── theme/             # Material 3 与 Miuix 双生设计主题体系
```

---

## 🛠️ 本地编译与验证

### 环境要求
- JDK 17+
- Android SDK (compileSdk 36, minSdk 26)

### 编译与测试指令
```bash
# 克隆代码仓库
git clone https://github.com/YHLFurry/AgentPaw.git
cd AgentPaw

# 编译生成 Debug APK
./gradlew :app:assembleDebug

# 运行全量单元测试（无需连接手机或模拟器）
./gradlew testDebugUnitTest
```

编译产物位置：
`app/build/outputs/apk/debug/app-debug.apk`

### 自动化测试覆盖
工程包含 **139** 个自动化单元测试（核心模块 122 项 + UI 模块 17 项），在 JVM 环境毫秒级完成回归验证：
- `ResumeIntentDetectorTest`：覆盖完整断点意图识别、带补充修正条件意图解析、断点防重提示词构建。
- `AgentTest`：多轮工具调用、用户中途取消、工具执行失败回退与流式事件响应。
- `InterpreterTest`：沙箱环境变量隔离、防路径穿越穿透（如 `../../..`）、复杂管道流与数学运算。
- `AndroidPhoneToolsTest` & `ToolControlSignalTest`：手机各类点击/滑动/输入工具的 Schema 规范性、敏感密码/支付页面结构化安全暂停与二次授权信号拦截。
- `HybridPhoneControllerSecurityTest`：Root / Shizuku 模式下的包名白名单与严格格式正则校验，从根本上防止恶意 Shell 命令注入。
- `OpenAiCompatibleClientSecurityTest`：公网 API 强制 HTTPS 传输，局域网与本地私有 IP 范围合规校验。
- `AdaptiveScreenshotProcessorTest`：多模态截图自适应动态缩放与压降策略验证。
- `PersistentConversationRepositoryTest`：多轮会话持久化、图片异步解耦与数据安全保障。

---

## 📦 独立引用 `agentpaw-core` 库

`agentpaw-core` 已模块化解耦，可直接引入至您的 Android 项目中作为 Agent 底座：

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        maven {
            url = uri("https://maven.pkg.github.com/YHLFurry/AgentPaw")
            credentials {
                username = findProperty("gpr.user") as String?
                password = findProperty("gpr.key") as String?
            }
        }
    }
}

// build.gradle.kts
dependencies {
    implementation("com.paw.agent:agentpaw-core:0.1.5")
}
```

---

## 🗺️ 后续演进规划

- [x] 多轮 Agent 流式推理与工具调用闭环
- [x] 设备级三通道混合控制（无障碍、Shizuku、ROOT）
- [x] 断点续操与自然语言指令意图识别
- [x] 自定义 Skill 热注册工坊
- [x] 任务历史记录与多维量化对比
- [x] AI 智能步间自适应节奏引擎
- [x] 文字直接键入与智能软键盘保底
- [x] Material 3 与 Miuix 双生 UI 主题动态热切换
- [x] “关于”界面长按触感解锁资深专家模式
- [ ] 自动化任务工作流的导入、导出与社区分享
- [ ] 语音输入与轻量级离线 TTS 交互联动

---

## 🤝 参与贡献

欢迎提交 Issue 与 Pull Request！AgentPaw 是一个开放包容的开源项目，无论您是提出新想法、报告 Bug 还是贡献新功能，我们都非常欢迎。

1. Fork 本仓库
2. 创建您的特性分支 (`git checkout -b feat/my-feature`)
3. 提交您的修改 (`git commit -m 'feat: 增加某个酷炫的新功能'`)
4. 推送到远程分支 (`git push origin feat/my-feature`)
5. 创建 Pull Request

---

## 📄 开源许可证

本项目基于 [Apache License 2.0](LICENSE) 协议开源。
