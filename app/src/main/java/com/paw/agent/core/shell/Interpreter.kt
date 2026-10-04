package com.paw.agent.core.shell

import com.paw.agent.core.shell.command.CommandRegistry
import com.paw.agent.core.shell.command.SandboxCommand
import com.paw.agent.core.shell.parser.Arg
import com.paw.agent.core.shell.parser.JoinOperator
import com.paw.agent.core.shell.parser.Node
import com.paw.agent.core.shell.parser.Parser
import com.paw.agent.core.shell.parser.Redirection
import com.paw.agent.core.shell.token.ShellSyntaxException
import java.io.File

/** Limits that keep a runaway script from hanging or exhausting memory. */
data class SandboxLimits(
    /** Wall-clock budget for a whole script. */
    val timeoutMillis: Long = 10_000,
    /** Max characters kept per stream; the rest is dropped and flagged. */
    val maxOutputChars: Int = 64 * 1024,
    /** Max loop iterations across the whole script. */
    val maxSteps: Int = 200_000,
    /** Max pipeline depth, to stop `a | a | a | …` style blowups. */
    val maxPipelineDepth: Int = 16,
)

/** Everything one script run produced. */
data class SandboxResult(
    val result: CommandResult,
    /** Wall-clock time, measured by the caller. */
    val durationMillis: Long,
) {
    val exitCode: Int get() = result.exitCode
    val stdout: String get() = result.stdout
    val stderr: String get() = result.stderr
}

/**
 * Walks the AST and executes it against a [ShellEnvironment].
 *
 * The interpreter is single-threaded and cooperative: [stepBudget] is decremented
 * on every node visit, which is what stops `while true; do …; done` from
 * hanging the app.
 */
class Interpreter(
    private val registry: CommandRegistry,
    private val limits: SandboxLimits = SandboxLimits(),
) {

    private var stepBudget = limits.maxSteps
    private val deadlineNanos: Long

    init {
        deadlineNanos = System.nanoTime() + limits.timeoutMillis * 1_000_000
    }

    fun execute(
        source: String,
        env: ShellEnvironment,
    ): CommandResult {
        stepBudget = limits.maxSteps

        val ast = try {
            Parser.parse(source)
        } catch (e: ShellSyntaxException) {
            return CommandResult.error("syntax error: ${e.message}")
        }

        val io = ShellIO()
        val result = try {
            evaluate(ast, env, io)
        } catch (e: SandboxTimeoutException) {
            CommandResult.error("sandbox: ${e.message}")
        } catch (e: SandboxSecurityException) {
            CommandResult.error("sandbox: ${e.message}")
        } catch (e: CommandNotFoundException) {
            CommandResult.error("sandbox: ${e.message}", 127)
        }

        // An error raised mid-script is reported on stderr, unless the script
        // already wrote something more specific there.
        if (result.stderr.isNotEmpty() && io.stderr.isEmpty()) {
            io.stderr.append(result.stderr)
        }

        return result.copy(
            exitCode = result.exitCode,
            stdout = truncate(io.stdout.toString()),
            stderr = truncate(io.stderr.toString()),
            truncated = result.truncated || io.stdout.length > limits.maxOutputChars ||
                io.stderr.length > limits.maxOutputChars,
        )
    }

    // ---- evaluation -------------------------------------------------------

    private fun evaluate(node: Node, env: ShellEnvironment, io: ShellIO): CommandResult {
        tick()
        return when (node) {
            is Node.SimpleCommand -> runSimpleCommand(node, env, io)
            is Node.Pipeline -> runPipeline(node, env, io)
            is Node.Sequence -> runSequence(node, env, io)
            is Node.ForLoop -> runFor(node, env, io)
            is Node.WhileLoop -> runWhile(node, env, io)
            is Node.IfStatement -> runIf(node, env, io)
        }
    }

    private fun runSequence(node: Node.Sequence, env: ShellEnvironment, io: ShellIO): CommandResult {
        var last = CommandResult.ok()
        for (item in node.items) {
            tick()

            // Skip the node when a short-circuit operator says to.
            val shouldRun = when (item.operator) {
                JoinOperator.SEQUENCE -> true
                JoinOperator.AND -> last.isSuccess
                JoinOperator.OR -> !last.isSuccess
            }
            if (!shouldRun) continue

            last = evaluate(item.node, env, io)
        }
        return last
    }

    private fun runPipeline(
        node: Node.Pipeline,
        env: ShellEnvironment,
        io: ShellIO,
    ): CommandResult {
        if (node.stages.size > limits.maxPipelineDepth) {
            return CommandResult.error("sandbox: pipeline too deep")
        }

        var stdin = ""
        var result = CommandResult.ok()

        for (stage in node.stages) {
            tick()
            val stageIo = ShellIO(stdin = stdin)
            result = evaluate(stage, env, stageIo)
            // Each stage's stdout becomes the next stage's stdin.
            stdin = stageIo.stdout.toString()
        }

        // The final stage's output is what the pipeline produces, and it must
        // reach the enclosing scope so top-level scripts see it.
        io.stdout.append(stdin)
        return result.copy(stdout = "", stderr = "")
    }

    private fun runSimpleCommand(
        node: Node.SimpleCommand,
        env: ShellEnvironment,
        io: ShellIO,
    ): CommandResult {
        tick()

        // `NAME=value`, with or without a substitution on the right-hand side,
        // is a variable assignment rather than a command.
        ASSIGNMENT.matchEntire(node.name)?.let { match ->
            val extra = node.args
                .takeIf { it.isNotEmpty() }
                ?.joinToString("") { resolveArg(it, env) }
                .orEmpty()
            env.set(match.groupValues[1], match.groupValues[2] + extra)
            return CommandResult.ok()
        }

        val command = registry.find(node.name)
            ?: return CommandResult.error("$node.name: command not found", 127)

        // Redirections are applied around the command: input is read from a file
        // when `<` is present, and stdout/stderr are diverted when `>`/`>>` are.
        val stdin = node.redirections.filterIsInstance<Redirection.Input>()
            .firstOrNull()
            ?.let { redirection ->
                val path = resolveFilePath(resolveArg(redirection.file, env), env)
                runCatching { File(path).readText() }.getOrElse {
                    return CommandResult.error("$node.name: cannot read '$path'")
                }
            }
            ?: io.stdin

        val commandIo = ShellIO(stdin = stdin)
        val result = command.execute(resolveArgs(node.args, env), commandIo, env)

        // Commands report output either by returning it in the result or by
        // writing to the IO; normalise both into the IO so redirection and
        // pipelines see the same thing.
        if (commandIo.stdout.isEmpty() && result.stdout.isNotEmpty()) {
            commandIo.stdout.append(result.stdout)
        }
        if (commandIo.stderr.isEmpty() && result.stderr.isNotEmpty()) {
            commandIo.stderr.append(result.stderr)
        }

        node.redirections.filterIsInstance<Redirection.Output>().forEach { redirection ->
            val path = resolveFilePath(resolveArg(redirection.file, env), env)
            val text = if (redirection.stderr) commandIo.stderr.toString() else commandIo.stdout.toString()
            val file = File(path)
            file.parentFile?.let { if (!it.exists()) it.mkdirs() }
            runCatching {
                if (redirection.append) file.appendText(text) else file.writeText(text)
            }.onFailure {
                return CommandResult.error("$node.name: cannot write '$path'")
            }
        }

        // Output that was redirected to a file must not also reach the
        // enclosing scope, so each stream is forwarded only when undirected.
        val stdoutRedirected = node.redirections
            .filterIsInstance<Redirection.Output>()
            .any { !it.stderr }
        val stderrRedirected = node.redirections
            .filterIsInstance<Redirection.Output>()
            .any { it.stderr }

        if (!stdoutRedirected) io.stdout.append(commandIo.stdout)
        if (!stderrRedirected) io.stderr.append(commandIo.stderr)

        // The produced text has been handed to the enclosing scope, so clear it
        // from the returned result to keep exactly one source of truth.
        return result.copy(stdout = "", stderr = "")
    }

    private fun runFor(node: Node.ForLoop, env: ShellEnvironment, io: ShellIO): CommandResult {
        val values = node.iterable.map { resolveArg(it, env) }
        var last = CommandResult.ok()

        for (value in values) {
            tick()
            env.set(node.variable, value)
            last = evaluate(node.body, env, io)
        }
        return last
    }

    private fun runWhile(node: Node.WhileLoop, env: ShellEnvironment, io: ShellIO): CommandResult {
        var last = CommandResult.ok()
        while (true) {
            tick()
            val conditionIo = ShellIO()
            val condition = evaluate(node.condition, env, conditionIo)
            if (!condition.isSuccess) break
            last = evaluate(node.body, env, io)
        }
        return last
    }

    private fun runIf(node: Node.IfStatement, env: ShellEnvironment, io: ShellIO): CommandResult {
        tick()
        val conditionIo = ShellIO()
        val condition = evaluate(node.condition, env, conditionIo)
        return if (condition.isSuccess) {
            evaluate(node.thenBranch, env, io)
        } else {
            node.elseBranch?.let { evaluate(it, env, io) } ?: CommandResult.ok()
        }
    }

    // ---- arguments --------------------------------------------------------

    private fun resolveArgs(args: List<Arg>, env: ShellEnvironment): List<String> =
        args.map { resolveArg(it, env) }

    /**
     * Resolves a redirection target inside the sandbox and refuses anything that
     * would escape the root.
     */
    private fun resolveFilePath(path: String, env: ShellEnvironment): String {
        val resolved = env.resolvePath(path)
        if (!env.isInsideRoot(resolved)) {
            throw SandboxSecurityException("path escapes the sandbox: $path")
        }
        return resolved
    }

    private fun resolveArg(arg: Arg, env: ShellEnvironment): String = when (arg) {
        is Arg.Literal -> arg.value
        // Unknown variables expand to empty, like an unset shell variable.
        is Arg.Variable -> env.get(arg.name).orEmpty()
        is Arg.Subshell -> {
            val nestedIo = ShellIO()
            evaluate(arg.body, env, nestedIo)
            nestedIo.stdout.toString().trimEnd('\n')
        }
        // Concatenated pieces form one word, with no separator between them.
        is Arg.Template -> arg.parts.joinToString("") { resolveArg(it, env) }
    }

    // ---- guards -----------------------------------------------------------

    /** Enforces the step budget and the wall-clock deadline. */
    private fun tick() {
        if (--stepBudget <= 0) {
            throw SandboxTimeoutException("script exceeded ${limits.maxSteps} steps")
        }
        if (System.nanoTime() > deadlineNanos) {
            throw SandboxTimeoutException("script exceeded ${limits.timeoutMillis}ms")
        }
    }

    private fun truncate(text: String): String =
        if (text.length <= limits.maxOutputChars) {
            text
        } else {
            text.take(limits.maxOutputChars) + "\n… output truncated at ${limits.maxOutputChars} chars"
        }

    private companion object {
        /** Matches a bare `NAME=value` assignment. */
        val ASSIGNMENT = Regex("^([A-Za-z_][A-Za-z0-9_]*)=(.*)$")
    }
}
