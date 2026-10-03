# AgentPaw 🐾

> An agent on Android phones.

AgentPaw is a Jetpack Compose framework for building agent-style conversational  
apps on Android. This repository currently holds the **initial base framework**:  
the core agent loop, an LLM abstraction with streaming, a Material You chat UI,  
and a full LLM settings screen.

The app id is `com.paw.agent`; the launcher icon is a beast paw print.

---

## Status

| Area                                           | State                                     |
| ---------------------------------------------- | ----------------------------------------- |
| Agent loop (tool calling, multi-round)         | ✅ implemented, framework-level            |
| LLM client (OpenAI-compatible, SSE streaming)  | ✅ implemented                             |
| LLM settings (provider, key, model, sampling)  | ✅ implemented                             |
| Chat UI (Material You, streaming, stop/cancel) | ✅ implemented                             |
| Tool implementations                           | ⬜ none yet — the registry is wired, empty |
| Conversation persistence                       | ⬜ in-memory only                          |
| Release build / signing                        | ⬜ not configured                          |

## Architecture

```
com.paw.agent
├── core/                     ← pure Kotlin, no Android dependencies
│   ├── model/                Message, Conversation, ToolCall, ToolDefinition
│   ├── llm/                  LlmClient (the seam), LlmConfig, OpenAiCompatibleClient
│   │   └── dto/              Wire format for chat-completions
│   └── agent/                Agent (the loop), AgentTool, ToolRegistry
├── data/
│   ├── settings/             AppSettings + DataStore repository
│   └── conversation/         ConversationRepository (in-memory)
└── ui/
    ├── theme/                Material 3 scheme, type scale, shapes
    ├── chat/                 ChatScreen, MessageBubble, ChatViewModel
    ├── settings/             LlmSettingsScreen, LlmSettingsViewModel
    ├── components/           PawMark, EmptyChatState, ThinkingIndicator
    └── navigation/           AgentPawApp (NavHost)
```

Two design rules hold the framework together:

1. **`core/` is pure Kotlin.** No Android imports, so the agent loop is unit  
   testable on the JVM and could be lifted into a foreground service later.
2. **`LlmClient` is the only seam to a model.** The UI never sees a provider  
   type, and adding a non-OpenAI backend means writing one class.

## The agent loop

`Agent.run()` takes a config, the message history, and a cancellation check, and  
emits `AgentEvent`s:

```
AssistantDelta → ToolStarted → ToolFinished → AssistantDelta → … → Completed
```

It calls the model, and if the model requests tools it runs them through the  
`ToolRegistry`, appends the results, and calls again — up to `maxToolRounds`  
(8 by default) to stop a model from looping forever.

### Adding a tool

```kotlin
class CurrentTimeTool : AgentTool {
    override val definition = ToolDefinition(
        name = "current_time",
        description = "Returns the current device time in ISO-8601.",
        parametersSchema = """{"type":"object","properties":{}}""",
    )

    override suspend fun execute(arguments: String, context: AgentContext): String =
        Instant.now().toString()
}

// register it in AppContainer
toolRegistry = ToolRegistry(listOf(CurrentTimeTool()))
```

Register the tool and it is advertised to the provider automatically; the loop  
handles dispatch, result plumbing, and error recovery (a failed tool returns an  
error string to the model instead of aborting the turn).

## LLM support

`OpenAiCompatibleClient` speaks the OpenAI chat-completions protocol, so these  
work out of the box:

| Preset          | Base URL                                                  | Notes                                                       |
| --------------- | --------------------------------------------------------- | ----------------------------------------------------------- |
| OpenAI          | `https://api.openai.com/v1`                               |                                                             |
| DeepSeek        | `https://api.deepseek.com/v1`                             |                                                             |
| Google Gemini   | `https://generativelanguage.googleapis.com/v1beta/openai` | OpenAI-compatible endpoint                                  |
| Moonshot / Kimi | `https://api.moonshot.cn/v1`                              |                                                             |
| Ollama          | `http://10.0.2.2:11434/v1`                                | local, no key (`10.0.2.2` = host from the emulator)         |
| Custom          | —                                                         | any OpenAI-compatible endpoint (vLLM, LM Studio, OneAPI, …) |

Features: streaming via SSE, tool/function calling, per-field errors, a  
connection test button, and cancellation mid-stream.

**Your API key is stored only on-device** in the app's private DataStore  
preferences. It is sent solely as an auth header and is never logged.

## Material You

The app follows Material 3 and adopts the **dynamic colour** palette from the  
user's wallpaper on Android 12+ (toggleable in settings). Below Android 12 it  
falls back to the bundled indigo/violet brand seed that matches the launcher  
icon. Light and dark themes are both supported, and the app is edge-to-edge.

## Building

Requires JDK 17+ and the Android SDK (compileSdk 36, minSdk 26).

```bash
git clone https://github.com/YHLFurry/AgentPaw.git
cd AgentPaw
./gradlew :app:assembleDebug
```

The debug APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

## Tech stack

Kotlin 2.2.21 · AGP 8.13.2 · Gradle 8.13 · Compose BOM 2026.09.00 ·  
Material 3 · DataStore · OkHttp · kotlinx.serialization · Navigation Compose

## Roadmap

- [ ] Built-in tools (web search, time, calculator)
- [ ] Conversation history persisted with Room
- [ ] Multiple conversations with a drawer
- [ ] Vision / image input
- [ ] Release build config and CI

## Contributing

Issues and PRs are welcome — this is a collaborative repository. Please keep  
the `core/` layer free of Android dependencies so the framework stays portable  
and testable.

## License

See [LICENSE](LICENSE).
