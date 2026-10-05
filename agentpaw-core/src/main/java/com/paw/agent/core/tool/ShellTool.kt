package com.paw.agent.core.tool

import com.paw.agent.core.agent.AgentContext
import com.paw.agent.core.agent.AgentTool
import com.paw.agent.core.model.ToolDefinition
import com.paw.agent.core.shell.Interpreter
import com.paw.agent.core.shell.SandboxLimits
import com.paw.agent.core.shell.ShellEnvironment
import com.paw.agent.core.shell.command.CommandRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Runs a script inside the sandbox and returns its output.
 *
 * The script is interpreted in-process by [Interpreter] — no process is spawned
 * and no native code is executed, which is what lets this work on Android under
 * the W^X rules that forbid running binaries from the app's data directory.
 *
 * Everything the script can touch lives under a single root directory, and the
 * step/time/output budgets in [SandboxLimits] bound the damage a runaway script
 * could do.
 */
class ShellTool(
    private val sandboxRoot: File,
    private val limits: SandboxLimits = SandboxLimits(),
    private val registry: CommandRegistry = CommandRegistry(),
) : AgentTool {

    override val definition = ToolDefinition(
        name = "run_script",
        description = """
            Run a small shell-like script in a sandboxed environment and return
            its output. Useful for calculations, text processing, and working with
            files.

            Supported syntax: pipelines with '|', sequencing with ';', '&&' and
            '||', redirection with '>', '>>' and '<', 'for'/'if'/'while' blocks,
            variables via NAME=value and ${'$'}NAME, and command substitution
            with ${'$'}( ).

            Available commands: ${registry.names.sorted().joinToString(", ")}

            There is no network access, no process spawning, and no way to read
            or write outside the sandbox directory.
        """.trimIndent(),
        parametersSchema = """
            {
              "type": "object",
              "properties": {
                "script": {
                  "type": "string",
                  "description": "The script to run, e.g. \"seq 1 100 | grep 3 | wc -l\"."
                },
                "timeout_ms": {
                  "type": "integer",
                  "description": "Optional override for the time budget in milliseconds."
                }
              },
              "required": ["script"]
            }
        """.trimIndent(),
    )

    override suspend fun execute(arguments: String, context: AgentContext): String {
        val request = runCatching {
            Json.parseToJsonElement(arguments).jsonObject
        }.getOrElse {
            return "Error: could not parse the arguments (${it.message})."
        }

        val script = (request["script"] as? JsonPrimitive)?.content.orEmpty()
        if (script.isBlank()) return "Error: 'script' is required."

        val timeout = (request["timeout_ms"] as? JsonPrimitive)
            ?.content
            ?.toLongOrNull()
            ?.coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
            ?: limits.timeoutMillis

        return withContext(Dispatchers.Default) {
            val root = ensureSandboxRoot()
            val environment = ShellEnvironment(rootDir = root.absolutePath)
            val interpreter = Interpreter(registry, limits.copy(timeoutMillis = timeout))

            val started = System.currentTimeMillis()
            val result = interpreter.execute(script, environment)
            val elapsed = System.currentTimeMillis() - started

            buildString {
                append("exit=").append(result.exitCode)
                append("  (").append(elapsed).append("ms)")
                if (result.truncated) append("  [output truncated]")

                val stdout = result.stdout.trim()
                val stderr = result.stderr.trim()

                if (stdout.isNotEmpty()) {
                    append("\n--- stdout ---\n").append(stdout)
                }
                if (stderr.isNotEmpty()) {
                    append("\n--- stderr ---\n").append(stderr)
                }
                if (stdout.isEmpty() && stderr.isEmpty()) {
                    append("\n(no output)")
                }
            }
        }
    }

    private fun ensureSandboxRoot(): File =
        sandboxRoot.apply { if (!exists()) mkdirs() }

    private companion object {
        const val MIN_TIMEOUT_MS = 500L
        const val MAX_TIMEOUT_MS = 60_000L
    }
}
