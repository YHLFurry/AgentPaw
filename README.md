# AgentPaw 🐾

<p align="center">
  <img src="docs/icon-preview.png" alt="AgentPaw Logo" width="128" height="128" style="border-radius: 28px;" />
</p>

<p align="center">
  <strong>An Intelligent, Autonomous Agent Framework on Android Phones</strong>
</p>

<p align="center">
  <a href="README_CN.md">🇨🇳 简体中文</a> | <a href="README.md">🇺🇸 English</a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android_8.0+_(API_26+)-3DDC84?logo=android&logoColor=white" alt="Platform" />
  <img src="https://img.shields.io/badge/Kotlin-2.2.21-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin" />
  <img src="https://img.shields.io/badge/Jetpack_Compose-Material_3_&_Miuix-4285F4?logo=jetpackcompose&logoColor=white" alt="UI" />
  <img src="https://img.shields.io/badge/Tests-109_Passed-brightgreen" alt="Tests" />
  <img src="https://img.shields.io/badge/License-Apache_2.0-blue.svg" alt="License" />
</p>

---

## 🌟 Overview

**AgentPaw** is an advanced open-source autonomous agent framework tailored for Android. Powered by large language models (LLMs) and multi-tiered on-device controllers, AgentPaw can perceive screens, understand intent, plan multi-step workflows, and autonomously drive apps via Accessibility services, Shizuku (ADB privileges), or native ROOT.

Whether you want to automate repetitive device operations, build custom AI mobile assistants, or develop autonomous workflows, AgentPaw offers a production-grade core engine, modular tool registries, and delightful user experience.

---

## ✨ Key Features

### ⏯️ Task Breakpoint & Resume (断点续操)
- **Automatic Execution Snapshot**: When a task is paused or stopped, AgentPaw captures a complete `TaskBreakpoint` snapshot, including completed steps, pending goals, and UI state.
- **Natural Language Resume Intent**: Understands instructions like *"continue"*, *"go on"*, *"next step"*, or *"continue, but click the second button this time"*.
- **Anti-Duplication Prompt Injection**: Seamlessly synthesizes prior progress into the system context to prevent repetitive actions on restart.
- **One-Tap UI Recovery**: Dedicated resume card in the chat interface and floating capsule on the desktop for immediate continuation.

### 🤹 Custom Skills System (自定义 Skill)
- **Visual Skill Studio**: Create, edit, test, and toggle custom skills with user-defined JSON Schema parameters and Prompt templates directly inside the app.
- **Hot Tool Registration**: Custom skills are automatically registered into the LLM function calling catalog without app restarts.

### ⚡ Triple-Tier Controller Architecture
- **Accessibility Service**: Standard zero-root perception and touch interaction via Android Accessibility APIs.
- **Shizuku (ADB privileges)**: High-performance tap, swipe, and shell commands without requiring root.
- **Native ROOT (`su`)**: Unrestricted root access for instantaneous shell dispatch and low-latency screencaps.
- **Hybrid Controller**: Automatic fallback between Root, Shizuku, and Accessibility depending on available permissions.

### ⌨️ Direct Text Injection with Keyboard Fallback
- **Intelligent Input Targeting**: Automatically identifies active editable nodes, text boxes, and chat inputs.
- **Direct Insertion**: Injects text directly via `ACTION_SET_TEXT` or clipboard bridge for maximum speed.
- **Soft Keyboard Fallback**: Gracefully falls back to activating focus and emulating keyboard paste when direct insertion is restricted by target apps.

### ⏱️ AI Adaptive Pace Engine
- Dynamically predicts and adjusts inter-step delay based on operation complexity, page transitions, and text inputs to prevent misclicks before rendering finishes.

### 📜 Task History & Comparison
- Complete persistent task runs with step breakdowns, durations, and status tracking.
- **Context Rollback**: Rehydrate past task context directly back into the live chat conversation.
- **Dual-Task Quantitative Diff**: Compare execution metrics (step count, elapsed time, tool distribution) side by side.

### 🎨 Dual Coexisting Themes (Material You & Miuix)
- **Material 3 (Default)**: Modern Material You palette with Android 12+ wallpaper dynamic color adaptation.
- **Miuix**: Sleek HyperOS-inspired component set.
- **Hot-Switching**: Instant theme switching on the fly without restarting the app or resetting configurations.

### ℹ️ About Screen & Hidden Expert Mode
- Developer info, version status, and architecture notes.
- **Expert Mode**: Advanced options (such as Vision & Language Separated Display) are safely hidden by default. Long-press the paw icon in the About screen to unlock with haptic feedback.

### 🛡️ Pure-Kotlin Script Sandbox (`run_script`)
- In-process safe shell interpreter (`Lexer` → `Parser` → `Interpreter`) with 30+ built-in commands (pipelines `|`, redirection, control flows, arithmetic) running inside JVM boundaries without external binary vulnerabilities.

### 🔍 Keyless Web Search & Sub-Agents
- **DuckDuckGo Instant Search**: Fast entity search requiring zero API keys or user accounts.
- **Task Delegation (`delegate_task`)**: Isolated sub-agent execution with depth limits to resolve complex sub-tasks without polluting the main conversation history.

---

## 📱 User Guide

### 1. Installation & Prerequisites
- Android 8.0 (API level 26) or higher.
- Download the latest `app-debug.apk` from GitHub Releases or build from source.

### 2. Granting Permissions
To enable AgentPaw to control your phone, grant the required permissions according to your needs:
1. **Accessibility Service**: Go to *System Settings → Accessibility → AgentPaw* and enable the service (Required for standard on-screen control).
2. **Display over other apps**: Enable the floating overlay permission so the stop/resume capsule can display over third-party apps.
3. **Shizuku (Optional)**: If you have Shizuku installed, grant permission in the Shizuku Manager for ADB-level execution.
4. **Root (Optional)**: For rooted devices, grant `su` access when prompted or in settings.

### 3. Configuring LLM Provider
AgentPaw supports any OpenAI-compatible API endpoint:
- **Preset Providers**: OpenAI, DeepSeek, Google Gemini, Moonshot / Kimi, Ollama (local on `http://10.0.2.2:11434/v1` or LAN).
- **Custom Endpoints**: Any vLLM, LM Studio, OneAPI, or self-hosted endpoint.
- **Privacy First**: Your API keys are strictly stored on-device in encrypted private DataStore preferences and are never uploaded to any third-party telemetry.

### 4. Running and Managing Tasks
1. Send a natural instruction in the chat (e.g., *"Open Settings and turn on Dark Mode"*).
2. If you need to pause or take over, tap the floating stop capsule or chat **Stop** button.
3. To resume, tap **[▶ Continue]** on the breakpoint card or type *"continue from where we left off"*.

---

## 🏗️ Architecture

```
AgentPaw
├── agentpaw-core/                 ← Pure Kotlin & Reusable Android Library Module
│   ├── src/main/java/com/paw/core/
│   │   ├── agent/                 # Agent loop, multi-round tool dispatch, events
│   │   ├── controller/            # Accessibility, Shizuku, Root, Hybrid controllers
│   │   ├── executor/              # Breakpoint manager, ResumeIntentDetector, AdaptivePaceEngine
│   │   ├── llm/                   # OpenAI-compatible SSE client, Multimodal vision
│   │   ├── model/                 # TaskBreakpoint, Message, ToolCall, ToolDefinition
│   │   ├── search/                # DuckDuckGo search backend
│   │   ├── shell/                 # Sandboxed Lexer, Parser, AST Interpreter
│   │   ├── skill/                 # Custom skill definitions and registry
│   │   └── tool/                  # Built-in phone tools, script runner, delegation
├── app/                           ← Jetpack Compose Application Module
│   ├── src/main/java/com/paw/agent/
│   │   ├── data/                  # AppSettings DataStore, TaskHistoryRepository
│   │   ├── service/               # AgentAccessibilityService, AgentFloatingService
│   │   └── ui/
│   │       ├── chat/              # ChatScreen, BreakpointCard, MessageBubble
│   │       ├── history/           # TaskHistoryScreen, TaskDetailScreen, TaskDiffScreen
│   │       ├── skill/             # SkillListScreen, SkillEditorScreen
│   │       ├── settings/          # LlmSettingsScreen, AppearanceSettingsScreen
│   │       ├── about/             # AboutScreen (Long-press to unlock Expert Mode)
│   │       └── theme/             # Material 3 & Miuix dual themes
```

---

## 🛠️ Building & Testing

### Requirements
- JDK 17 or higher
- Android SDK (compileSdk 36, minSdk 26)

### Build Commands
```bash
# Clone the repository
git clone https://github.com/YHLFurry/AgentPaw.git
cd AgentPaw

# Compile Debug APK
./gradlew :app:assembleDebug

# Run all 109 unit tests across all modules
./gradlew testDebugUnitTest
```

The compiled APK will be located at:
`app/build/outputs/apk/debug/app-debug.apk`

### Test Coverage Highlights
All **109** unit tests (101 core + 8 app) pass out-of-the-box on the JVM without requiring an emulator:
- `ResumeIntentDetectorTest`: Breakpoint intent parsing, follow-up instructions, resume prompt generation.
- `AgentTest`: Multi-round loop, cancellation, tool recovery, and event emission.
- `InterpreterTest`: Sandboxed shell execution, security path boundaries, pipelines, and arithmetic logic.
- `AndroidPhoneToolsTest`: Phone tool schemas, device perception, and hybrid delegation.
- `AdaptiveScreenshotProcessorTest`: Multimodal image downscaling and compression.

---

## 📦 Using `agentpaw-core` in Your App

`agentpaw-core` is modularized and published to GitHub Packages.

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

## 🗺️ Roadmap

- [x] Multi-round agent execution loop with streaming SSE
- [x] On-device multi-tier control (Accessibility, Shizuku, ROOT)
- [x] Task Breakpoint & Natural Language Resume (断点续操)
- [x] Custom Skills studio and runtime registration
- [x] Persistent task history and quantitative comparison
- [x] AI Adaptive pace engine for inter-step wait times
- [x] Direct text injection with soft keyboard fallback
- [x] Dual themes (Material 3 + Miuix) with instant switching
- [x] Secret Expert Mode unlocked via About screen
- [ ] Task execution workflow export & sharing
- [ ] Voice input and TTS interaction

---

## 🤝 Contributing

Contributions, issues, and feature requests are very welcome! Feel free to check the [issues page](https://github.com/YHLFurry/AgentPaw/issues).

1. Fork the Project
2. Create your Feature Branch (`git checkout -b feat/amazing-feature`)
3. Commit your Changes (`git commit -m 'feat: Add amazing feature'`)
4. Push to the Branch (`git push origin feat/amazing-feature`)
5. Open a Pull Request

---

## 📄 License

This project is licensed under the Apache License 2.0. See the [LICENSE](LICENSE) file for details.
