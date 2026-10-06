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
| Sub-agent delegation (depth-limited)           | ✅ implemented                             |
| Script sandbox (built-in shell)               | ✅ implemented                             |
| Web search (keyless)                          | ✅ implemented                             |
| Conversation persistence                       | ⬜ in-memory only                          |
| Release build / signing                        | ⬜ not configured                          |

## Theming

The app ships with **two coexisting UI themes** selectable from
**Settings → Appearance**:

- **Material** (default) — the original Material 3 / Material You look. Existing
  installs stay on this theme, so upgrading never changes the visual style.
- **Miuix** — a HyperOS-style component set (Miuix) for a different look & feel.

Both themes read the same `AppSettings` and drive the same ViewModels; switching
themes never touches LLM configuration. The choice is persisted in DataStore and
applied **live** the moment you tap it — no app restart — and is restored on next
launch. The LLM settings section is fully independent and keeps its own draft/save
flow.

Implementation lives behind a single `AgentPawAppTheme` root and a theme-agnostic
`AppTheme` token layer (`com.paw.agent.ui.theme`); screens are written once against
`App*` adaptive components (`com.paw.agent.ui.components.adaptive`).

## Architecture

```
com.paw.agent
├── core/                     ← pure Kotlin, no Android dependencies
│   ├── model/                Message, Conversation, ToolCall, ToolDefinition
│   ├── llm/                  LlmClient (the seam), LlmConfig, OpenAiCompatibleClient
│   │   └── dto/              Wire format for chat-completions
│   ├── agent/                Agent (the loop), AgentTool, ToolRegistry
│   ├── shell/                Script sandbox: Lexer, Parser, Interpreter, commands
│   ├── search/               SearchBackend + DuckDuckGo implementation
│   └── tool/                 The built-in AgentTool implementations
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

## Built-in tools

Three tools ship in the box, wired in `AppContainer.toolRegistry`.

### `run_script` — the script sandbox

A shell-like interpreter written from scratch in pure Kotlin: `Lexer` → `Parser`
→ `Interpreter`, plus ~30 built-in commands. It runs **in-process** — no process
is spawned and no native binary is executed.

That last point is the reason it is hand-written rather than a real shell.
Since Android 10, `execve()` on anything in the app's data directory is a W^X
violation (the fix is to ship binaries in `jniLibs` as `lib___.so` and run them
from the native library dir, as Termux does). A bootstrap zip is the right answer
for a *terminal*; for an agent that just needs arithmetic and text processing, an
interpreter is smaller, safer, and needs no per-ABI binaries.

```bash
seq 1 100 | grep 3 | wc -l
expr (2 + 3) * 4
printf 'b\na\nb\n' | sort | uniq
i=0; while test $i -lt 3; do echo $i; i=$(expr $i + 1); done
```

What it supports:

- pipelines `|`, sequencing `;` `&&` `||`, redirection `>` `>>` `<` `2>`
- `for` / `if` / `while` blocks
- variables (`NAME=value`, `$NAME`), command substitution (`$( … )`)
- arithmetic via a shunting-yard evaluator with `+ - * / % ^`, parentheses, and
  `sqrt abs floor ceil round min max pow` — no `eval`, so a generated expression
  can never escape into host code

What it will not do, by design: no process spawning, no network, no globbing, no
command substitution into a shell, and every path is resolved through
`ShellEnvironment.resolvePath`, which refuses to leave the sandbox root.

Runaway scripts are bounded by `SandboxLimits`: wall-clock timeout, max AST steps
(so `while true` dies instead of hanging the app), max pipeline depth, and output
truncation.

### `web_search` — keyless lookup

`DuckDuckGoSearchBackend` uses the Instant Answer API, which needs **no API key
and no account**, so the feature works on a fresh install.

The honest trade-off: it answers entity-style questions ("rust", "kotlin") well
and open-ended ones poorly. When it has nothing, the tool says so and tells the
model to answer from its own knowledge and flag that it could not verify — rather
than leaving a gap the model quietly fills. `SearchBackend` is an interface, so
adding Tavily or Brave later means one new class, no UI change.

### `delegate_task` — sub-agents

Delegates a self-contained sub-task to a nested agent with its own system prompt
and **no tools**, returning its final answer to the caller.

```
main agent ──delegate_task──▶ sub agent (own prompt, no tools, no delegation)
         ◀── final answer ───┘
```

This keeps a long multi-step chain out of the main context and lets one prompt own
a whole sub-task end to end. Recursion is bounded twice: by `maxDepth` (2) on
`AgentContext`, and by the sub-agent getting an empty `ToolRegistry`, so
delegation is always a leaf and cannot loop.


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

## `agentpaw-core` library

Everything that makes AgentPaw an agent — the conversation engine and the
signature capabilities — lives in a reusable Android Library module,
`agentpaw-core` (`:agentpaw-core`). The `app` module is just the Compose UI
shell on top of it.

| Package | What you get |
| ------- | ------------ |
| `com.paw.agent.core.agent` | the agent loop, tool registry, cancellation, events |
| `com.paw.agent.core.llm` | OpenAI-compatible client, streaming, multimodal (images) |
| `com.paw.agent.core.model` | message / conversation model |
| `com.paw.agent.core.shell` | the sandboxed shell interpreter (`run_script`) |
| `com.paw.agent.core.tool` | built-in tools: `run_script`, `web_search`, `delegate_task`, Android phone tools |
| `com.paw.agent.core.skill` | agent skills |
| `com.paw.agent.core.search` | keyless DuckDuckGo search backend |
| `com.paw.agent.device` | on-device control: Accessibility, Shizuku, adaptive screenshots |

### Publishing

`agentpaw-core` is published to GitHub Packages
([packages](https://github.com/YHLFurry/AgentPaw/packages)). The
*Publish agentpaw-core* workflow runs on every `v*` tag — pushing `v0.1.0`
publishes version `0.1.0` — and can also be triggered manually from the
Actions tab.

### Consuming

Add GitHub Packages as a repository (a GitHub token with `read:packages` is
required to fetch):

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        maven {
            url = uri("https://maven.pkg.github.com/YHLFurry/AgentPaw")
            credentials {
                username = findProperty("gpr.user") as String? // your GitHub username
                password = findProperty("gpr.key") as String?  // a PAT with read:packages
            }
        }
    }
}
```

Then add the dependency:

```kotlin
dependencies {
    implementation("com.paw.agent:agentpaw-core:0.1.0")
}
```

### Shizuku integration

`agentpaw-core` ships everything needed for Shizuku — the
`moe.shizuku.manager.permission.API_V23` permission and the
`rikka.shizuku.ShizukuProvider` (`${applicationId}.shizuku`) are merged into your
manifest automatically from the library manifest. Initialize once at startup
(`Application.onCreate`):

```kotlin
ShizukuInitializer.initialize() // registers binder + permission-result listeners

// request authorization (pops the Shizuku Manager dialog when the service is running);
// returns false when Shizuku is not running, e.g. to guide the user to open the app:
val dispatched = DevicePermissionManager.requestShizukuPermission()
```

The current state is observable via `DevicePermissionManager.observeShizukuState()`
(`NOT_RUNNING` / `RUNNING_NO_PERMISSION` / `GRANTED`) — it updates live when the
binder arrives and when the user responds to the authorization dialog.

### Stop floating button (accessibility operations)

While the agent drives the device through the accessibility service, host apps
request a floating red stop pill:

```kotlin
AgentStopFloatingButton.show(context) {
    // optional: also cancel your agent loop here
}
```

**The button never covers your own app.** `show()` only registers a request; the
pill actually appears once the host app is no longer in front, so it can't block
the UI you are looking at. `hide()` cancels the request.

| Host app state | Pill |
| --- | --- |
| Foreground (user is inside the app) | hidden |
| Background (user left to drive other apps) | shown |

Show/hide triggers:

- **Shown** — the host app transitions foreground → background, or `show()` is
  called while the host app is already in the background.
- **Hidden** — the host app transitions background → foreground, `hide()` is
  called, the user taps the pill, or the pending request is cancelled.

#### Foreground detection

`AgentAppForegroundMonitor` resolves "is the host app in front" in two tiers
(`AgentForegroundDecider`):

1. **Host process has activities** (the normal case) — Activity
   `resume`/`pause` is the single source of truth: `resumedActivityCount > 0`
   means foreground. Resume (not start) is deliberate: in split-screen the host
   activity can stay *started* while already out of focus, and there the user is
   really in another app, so the pill must be visible.
2. **Host process has no activities at all** (service-only integration) — falls
   back to the accessibility service's reported foreground package: host package
   == foreground package means foreground. Driving the device requires that
   service anyway, so this signal is always available where it matters.

When neither signal is available the state is treated as **background** — better
to show a redundant pill than to leave the user without a stop entry.

Register early so the first Activity's `onResume` isn't missed:

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AgentAppForegroundMonitor.install(this)
    }
}
```

`AgentStopFloatingButton.show()` also installs it lazily as a fallback, but a
late registration can misjudge the very first launch and flash the pill. Needs
the `SYSTEM_ALERT_WINDOW` permission (also merged from the library manifest —
grant the overlay permission before showing). Reset per turn with
`AgentAccessibilityService.instance?.clearUserStop()`.

Opt out of the background-only policy if you want the pill always on screen:

```kotlin
AgentStopFloatingButton.setVisibilityMode(AgentFloatingVisibilityMode.ALWAYS)
```

## Building

Requires JDK 17+ and the Android SDK (compileSdk 36, minSdk 26).

```bash
git clone https://github.com/YHLFurry/AgentPaw.git
cd AgentPaw
./gradlew :app:assembleDebug      # debug APK
./gradlew testDebugUnitTest       # all modules, 75 unit tests
```

The debug APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

## Tests

`./gradlew testDebugUnitTest` runs all 75 JVM tests across both modules, no
device needed. 74 of them live in `agentpaw-core`:

| Suite                   | Covers                                                        |
| ----------------------- | ------------------------------------------------------------- |
| `AgentTest`             | tool dispatch, multi-round loop, cancellation, failure paths  |
| `InterpreterTest`       | the shell: pipelines, control flow, redirection, and escapes  |
| `AndroidPhoneToolsTest` | phone tool schemas and controller delegation                  |
| `BuiltInToolsTest`      | the tools, including sub-agent depth limiting                 |
| `DuckDuckGoSearchBackendTest` | response parsing against recorded payloads            |
| `DuckDuckGoSearchBackendLiveTest` | one live call, auto-skipped when offline        |
| `MultimodalDtoTest` / `AdaptiveScreenshotProcessorTest` | image payloads, screenshot downscaling |
| `AgentExecutionControllerTest` (app) | floating-window execution lifecycle       |

The sandbox tests use a real temp directory rather than mocks, so the path
guards are genuinely exercised — including attempts to read `/etc/passwd` and
`../../..`.

## Tech stack

Kotlin 2.2.21 · AGP 8.13.2 · Gradle 8.13 · Compose BOM 2026.06.01 ·  
Material 3 · DataStore · OkHttp · kotlinx.serialization · Navigation Compose

> The Compose BOM is pinned to `2026.06.01` (Compose 1.11.4). `2026.08.00` and
> later require AGP 9.1+ and compileSdk 37; revisit once AGP 9 settles.

## Roadmap

- [x] Built-in tools: script sandbox, web search, sub-agent delegation
- [ ] Conversation history persisted with Room
- [ ] Multiple conversations with a drawer
- [ ] A real Termux-style bootstrap (per-ABI binaries in `jniLibs`) as an
      alternative sandbox backend, for when a full shell is actually needed
- [ ] More sandbox commands (`sed`, `awk`, `jq`-style filters)
- [ ] Vision / image input
- [ ] Release build config and CI

## Contributing

Issues and PRs are welcome — this is a collaborative repository. Please keep  
the `core/` layer free of Android dependencies so the framework stays portable  
and testable.

## License

See [LICENSE](LICENSE).
