package com.paw.agent.core.shell.command

import com.paw.agent.core.shell.CommandResult
import com.paw.agent.core.shell.ShellEnvironment
import com.paw.agent.core.shell.ShellIO

/**
 * A command available inside the sandbox.
 *
 * Implementations run in-process and must be side-effect free outside the sandbox
 * root. Anything that could touch the device (process spawning, raw file IO
 * outside [ShellEnvironment.rootDir], network) is deliberately not part of this
 * interface — that is the whole point of the sandbox.
 */
interface SandboxCommand {

    val name: String

    val usage: String

    /** One-line summary handed to the model so it can pick the right command. */
    val summary: String

    fun execute(
        args: List<String>,
        io: ShellIO,
        env: ShellEnvironment,
    ): CommandResult
}

/** Registry of the built-in commands. */
class CommandRegistry(commands: List<SandboxCommand> = defaultCommands()) {

    private val byName = commands.associateBy { it.name }

    fun find(name: String): SandboxCommand? = byName[name]

    val names: Set<String> get() = byName.keys

    /** A one-line catalogue used in error messages and by the agent tool. */
    fun catalogue(): String = byName.values
        .sortedBy { it.name }
        .joinToString("\n") { "${it.name.padEnd(10)} ${it.summary}" }

    companion object {
        /**
         * The standard command set.
         *
         * [HelpCommand] is appended last and gets its own registry, so `help`
         * can list everything else without recursing into itself.
         */
        fun defaultCommands(): List<SandboxCommand> {
            val commands = mutableListOf<SandboxCommand>(
                EchoCommand,
                PrintfCommand,
                CatCommand,
                HeadCommand,
                TailCommand,
                GrepCommand,
                WcCommand,
                SortCommand,
                UniqCommand,
                CutCommand,
                SeqCommand,
                ExprCommand,
                TestCommand,
                TrueCommand,
                FalseCommand,
                SleepCommand,
                EnvCommand,
                ExportCommand,
                CdCommand,
                PwdCommand,
                LsCommand,
                MkdirCommand,
                TouchCommand,
                RmCommand,
                CpCommand,
                MvCommand,
                WriteCommand,
                ReadCommand,
                AppendCommand,
                StatCommand,
                FindCommand,
                YesCommand,
            )
            commands += HelpCommand(CommandRegistry(commands))
            return commands
        }
    }
}

/** Shared helpers for argument handling. */
internal object Args {

    fun flag(args: List<String>, name: String): Boolean =
        args.any { it == "-$name" || it == "--$name" }

    /** Extracts `-n <value>` / `--name <value>` / `-n=<value>` pairs, plus the rest. */
    fun option(args: List<String>, vararg names: String): Pair<String?, List<String>> {
        var value: String? = null
        val rest = mutableListOf<String>()

        var i = 0
        while (i < args.size) {
            val arg = args[i]
            val matched = names.firstOrNull { name ->
                arg == "-$name" || arg.startsWith("-$name=")
            }

            // Only the `=` form can be detected from the matched name; the
            // space-separated form is handled by the generic branch below.
            val inlineValue = matched?.let { name ->
                arg.substringAfter("-$name=", "")
                    .takeIf { arg.startsWith("-$name=") }
            }

            when {
                inlineValue != null -> {
                    value = inlineValue
                    i++
                }

                matched != null -> {
                    value = args.getOrNull(i + 1)
                    i += 2
                }

                else -> {
                    rest += arg
                    i++
                }
            }
        }
        return value to rest
    }

    fun intOr(value: String?, fallback: Int): Int = value?.toIntOrNull() ?: fallback
}
