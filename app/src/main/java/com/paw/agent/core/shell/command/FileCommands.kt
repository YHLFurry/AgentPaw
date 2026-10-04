package com.paw.agent.core.shell.command

import com.paw.agent.core.shell.CommandResult
import com.paw.agent.core.shell.SandboxSecurityException
import com.paw.agent.core.shell.ShellEnvironment
import com.paw.agent.core.shell.ShellIO
import com.paw.agent.core.shell.command.FileSupport.ensureParent
import com.paw.agent.core.shell.command.FileSupport.ensureTrailingNewline
import com.paw.agent.core.shell.command.FileSupport.readAll
import com.paw.agent.core.shell.command.FileSupport.resolveFile
import java.io.File

// ---------------------------------------------------------------------------
// Environment
// ---------------------------------------------------------------------------

object EnvCommand : SandboxCommand {
    override val name = "env"
    override val usage = "env [NAME]"
    override val summary = "List environment variables, or print one."

    override fun execute(args: List<String>, io: ShellIO, env: ShellEnvironment): CommandResult {
        if (args.isNotEmpty()) {
            val name = args.first()
            val value = env.get(name) ?: return CommandResult(1)
            return CommandResult.ok("$value\n")
        }
        val text = env.exportAll()
            .toSortedMap()
            .entries
            .joinToString("") { "${it.key}=${it.value}\n" }
        return CommandResult.ok(text)
    }
}

object ExportCommand : SandboxCommand {
    override val name = "export"
    override val usage = "export NAME=VALUE"
    override val summary = "Set an environment variable for later commands."

    override fun execute(args: List<String>, io: ShellIO, env: ShellEnvironment): CommandResult {
        if (args.isEmpty()) return CommandResult.error("export: missing assignment")
        for (assignment in args) {
            val index = assignment.indexOf('=')
            if (index <= 0) {
                return CommandResult.error("export: invalid assignment '$assignment'")
            }
            env.set(assignment.substring(0, index), assignment.substring(index + 1))
        }
        return CommandResult.ok()
    }
}

object CdCommand : SandboxCommand {
    override val name = "cd"
    override val usage = "cd [DIRECTORY]"
    override val summary = "Change the working directory (confined to the sandbox)."

    override fun execute(args: List<String>, io: ShellIO, env: ShellEnvironment): CommandResult =
        try {
            env.cd(args.firstOrNull().orEmpty())
            CommandResult.ok()
        } catch (e: SandboxSecurityException) {
            CommandResult.error("cd: ${e.message}")
        }
}

object PwdCommand : SandboxCommand {
    override val name = "pwd"
    override val usage = "pwd"
    override val summary = "Print the working directory."

    override fun execute(args: List<String>, io: ShellIO, env: ShellEnvironment): CommandResult =
        CommandResult.ok(env.workingDir + "\n")
}

// ---------------------------------------------------------------------------
// File system
// ---------------------------------------------------------------------------

object LsCommand : SandboxCommand {
    override val name = "ls"
    override val usage = "ls [-l] [-a] [PATH...]"
    override val summary = "List directory contents."

    override fun execute(args: List<String>, io: ShellIO, env: ShellEnvironment): CommandResult {
        val longFormat = Args.flag(args, "l")
        val showHidden = Args.flag(args, "a")
        val targets = args.filterNot { it.startsWith("-") && it.length > 1 }
            .ifEmpty { listOf(".") }

        val out = StringBuilder()
        for (target in targets) {
            val file = try {
                resolveFile(target, env)
            } catch (e: SandboxSecurityException) {
                return CommandResult.error("ls: ${e.message}")
            }

            if (!file.exists()) return CommandResult.error("ls: $target: No such file or directory")

            if (file.isDirectory) {
                if (targets.size > 1) out.append("$target:\n")
                val children = file.listFiles().orEmpty().sortedBy { it.name }
                for (child in children) {
                    if (!showHidden && child.name.startsWith(".")) continue
                    out.append(formatEntry(child, longFormat, env))
                }
            } else {
                out.append(formatEntry(file, longFormat, env))
            }
        }
        return CommandResult.ok(out.toString())
    }

    private fun formatEntry(file: File, longFormat: Boolean, env: ShellEnvironment): String {
        if (!longFormat) return "${file.name}\n"
        val kind = if (file.isDirectory) "d" else "-"
        val size = if (file.isFile) file.length() else 0L
        return String.format("%s %8d  %s%n", kind, size, file.name)
    }
}

object MkdirCommand : SandboxCommand {
    override val name = "mkdir"
    override val usage = "mkdir [-p] DIRECTORY..."
    override val summary = "Create directories."

    override fun execute(args: List<String>, io: ShellIO, env: ShellEnvironment): CommandResult {
        val parents = Args.flag(args, "p")
        val targets = args.filterNot { it.startsWith("-") && it.length > 1 }
        if (targets.isEmpty()) return CommandResult.error("mkdir: missing operand")

        for (target in targets) {
            val file = try {
                resolveFile(target, env)
            } catch (e: SandboxSecurityException) {
                return CommandResult.error("mkdir: ${e.message}")
            }
            val created = if (parents) file.mkdirs() else file.mkdir()
            if (!created && !file.isDirectory) {
                return CommandResult.error("mkdir: cannot create directory '$target'")
            }
        }
        return CommandResult.ok()
    }
}

object TouchCommand : SandboxCommand {
    override val name = "touch"
    override val usage = "touch FILE..."
    override val summary = "Create an empty file or update its timestamp."

    override fun execute(args: List<String>, io: ShellIO, env: ShellEnvironment): CommandResult {
        val targets = args.filterNot { it.startsWith("-") && it.length > 1 }
        if (targets.isEmpty()) return CommandResult.error("touch: missing operand")

        for (target in targets) {
            val file = try {
                resolveFile(target, env)
            } catch (e: SandboxSecurityException) {
                return CommandResult.error("touch: ${e.message}")
            }
            ensureParent(file)
            if (!file.exists() && !file.createNewFile()) {
                return CommandResult.error("touch: cannot create '$target'")
            }
            file.setLastModified(System.currentTimeMillis())
        }
        return CommandResult.ok()
    }
}

object RmCommand : SandboxCommand {
    override val name = "rm"
    override val usage = "rm [-r] FILE..."
    override val summary = "Remove files or, with -r, directories."

    override fun execute(args: List<String>, io: ShellIO, env: ShellEnvironment): CommandResult {
        val recursive = Args.flag(args, "r") || Args.flag(args, "R")
        val force = Args.flag(args, "f")
        val targets = args.filterNot { it.startsWith("-") && it.length > 1 }
        if (targets.isEmpty()) return CommandResult.error("rm: missing operand")

        for (target in targets) {
            val file = try {
                resolveFile(target, env)
            } catch (e: SandboxSecurityException) {
                return CommandResult.error("rm: ${e.message}")
            }

            if (!file.exists()) {
                if (force) continue
                return CommandResult.error("rm: cannot remove '$target': No such file or directory")
            }

            // Refuse to delete the sandbox root itself.
            if (env.normalizeSandboxRoot() == file.absolutePath) {
                return CommandResult.error("rm: refusing to remove the sandbox root")
            }

            val deleted = if (file.isDirectory) {
                if (recursive) file.deleteRecursively() else false
            } else {
                file.delete()
            }

            if (!deleted && !force) {
                return CommandResult.error("rm: cannot remove '$target'")
            }
        }
        return CommandResult.ok()
    }
}

object CpCommand : SandboxCommand {
    override val name = "cp"
    override val usage = "cp SOURCE... DEST"
    override val summary = "Copy files into the sandbox."

    override fun execute(args: List<String>, io: ShellIO, env: ShellEnvironment): CommandResult {
        if (args.size < 2) return CommandResult.error("cp: missing destination")
        val destination = try {
            resolveFile(args.last(), env)
        } catch (e: SandboxSecurityException) {
            return CommandResult.error("cp: ${e.message}")
        }

        val sources = args.dropLast(1)
        val target = if (sources.size == 1 && destination.isDirectory) {
            File(destination, sources.first().substringAfterLast('/'))
        } else {
            destination
        }

        for (source in sources) {
            val from = try {
                resolveFile(source, env)
            } catch (e: SandboxSecurityException) {
                return CommandResult.error("cp: ${e.message}")
            }
            if (!from.exists()) return CommandResult.error("cp: cannot stat '$source'")
            if (from.isDirectory) return CommandResult.error("cp: '$source' is a directory")

            ensureParent(target)
            val copied = runCatching { from.copyTo(target, overwrite = true) }
                .isSuccess
            if (!copied) return CommandResult.error("cp: cannot copy '$source'")
        }
        return CommandResult.ok()
    }
}

object MvCommand : SandboxCommand {
    override val name = "mv"
    override val usage = "mv SOURCE... DEST"
    override val summary = "Move or rename files inside the sandbox."

    override fun execute(args: List<String>, io: ShellIO, env: ShellEnvironment): CommandResult {
        if (args.size < 2) return CommandResult.error("mv: missing destination")
        val destination = try {
            resolveFile(args.last(), env)
        } catch (e: SandboxSecurityException) {
            return CommandResult.error("mv: ${e.message}")
        }

        val sources = args.dropLast(1)
        val target = if (sources.size == 1 && destination.isDirectory) {
            File(destination, sources.first().substringAfterLast('/'))
        } else {
            destination
        }

        for (source in sources) {
            val from = try {
                resolveFile(source, env)
            } catch (e: SandboxSecurityException) {
                return CommandResult.error("mv: ${e.message}")
            }
            if (!from.exists()) return CommandResult.error("mv: cannot stat '$source'")

            ensureParent(target)
            if (from.renameTo(target).not()) {
                // Fall back to copy + delete when rename fails across mounts.
                val copied = runCatching { from.copyTo(target, overwrite = true) }.isSuccess
                if (!copied || !from.delete()) {
                    return CommandResult.error("mv: cannot move '$source'")
                }
            }
        }
        return CommandResult.ok()
    }
}

object WriteCommand : SandboxCommand {
    override val name = "write"
    override val usage = "write FILE  (content comes from stdin)"
    override val summary = "Write stdin to a file, replacing it."

    override fun execute(args: List<String>, io: ShellIO, env: ShellEnvironment): CommandResult {
        val target = args.firstOrNull() ?: return CommandResult.error("write: missing file")
        val file = try {
            resolveFile(target, env)
        } catch (e: SandboxSecurityException) {
            return CommandResult.error("write: ${e.message}")
        }
        ensureParent(file)
        return runCatching { file.writeText(io.stdin) }
            .fold(
                onSuccess = { CommandResult.ok() },
                onFailure = { CommandResult.error("write: cannot write '$target'") },
            )
    }
}

object AppendCommand : SandboxCommand {
    override val name = "append"
    override val usage = "append FILE  (content comes from stdin)"
    override val summary = "Append stdin to a file."

    override fun execute(args: List<String>, io: ShellIO, env: ShellEnvironment): CommandResult {
        val target = args.firstOrNull() ?: return CommandResult.error("append: missing file")
        val file = try {
            resolveFile(target, env)
        } catch (e: SandboxSecurityException) {
            return CommandResult.error("append: ${e.message}")
        }
        ensureParent(file)
        return runCatching { file.appendText(io.stdin) }
            .fold(
                onSuccess = { CommandResult.ok() },
                onFailure = { CommandResult.error("append: cannot append to '$target'") },
            )
    }
}

object ReadCommand : SandboxCommand {
    override val name = "read"
    override val usage = "read FILE"
    override val summary = "Print a file's contents (alias of cat)."

    override fun execute(args: List<String>, io: ShellIO, env: ShellEnvironment): CommandResult {
        if (args.isEmpty()) return CommandResult.error("read: missing file")
        return CommandResult.ok(readAll(args, env, io.stdin))
    }
}

object StatCommand : SandboxCommand {
    override val name = "stat"
    override val usage = "stat FILE"
    override val summary = "Show size, type, and modification time of a file."

    override fun execute(args: List<String>, io: ShellIO, env: ShellEnvironment): CommandResult {
        val target = args.firstOrNull() ?: return CommandResult.error("stat: missing file")
        val file = try {
            resolveFile(target, env)
        } catch (e: SandboxSecurityException) {
            return CommandResult.error("stat: ${e.message}")
        }
        if (!file.exists()) return CommandResult.error("stat: cannot stat '$target'")

        val type = if (file.isDirectory) "directory" else "file"
        val out = buildString {
            append("  File: ${file.name}\n")
            append("  Size: ${file.length()}\n")
            append("  Type: $type\n")
            append("  Modified: ${file.lastModified()}\n")
        }
        return CommandResult.ok(out)
    }
}

object FindCommand : SandboxCommand {
    override val name = "find"
    override val usage = "find [PATH] [-name PATTERN] [-type f|d]"
    override val summary = "Walk the sandbox tree and list matching paths."

    override fun execute(args: List<String>, io: ShellIO, env: ShellEnvironment): CommandResult {
        val start = args.firstOrNull { !it.startsWith("-") } ?: "."
        val root = try {
            resolveFile(start, env)
        } catch (e: SandboxSecurityException) {
            return CommandResult.error("find: ${e.message}")
        }
        if (!root.exists()) return CommandResult.error("find: '$start': No such file or directory")

        val pattern = args.valueAfter("-name")
        val wantedType = args.valueAfter("-type")

        val matches = root.walkTopDown()
            .filter { file -> env.isInsideRoot(file.absolutePath) }
            .filter { file ->
                when (wantedType) {
                    "f" -> file.isFile
                    "d" -> file.isDirectory
                    else -> true
                }
            }
            .filter { file ->
                pattern == null || file.name.contains(pattern, ignoreCase = true)
            }
            .take(MAX_RESULTS)
            .toList()

        val out = StringBuilder()
        val base = File(env.resolvePath(start))
        for (file in matches) {
            val relative = file.toRelativeString(base)
            out.append(if (relative.isEmpty()) file.name else relative).append('\n')
        }
        return CommandResult.ok(out.toString())
    }

    /** Returns the argument following [flag], or null when absent. */
    private fun List<String>.valueAfter(flag: String): String? {
        val index = indexOf(flag)
        return if (index >= 0) getOrNull(index + 1) else null
    }

    private const val MAX_RESULTS = 1_000
}

class HelpCommand(private val registry: CommandRegistry) : SandboxCommand {
    override val name = "help"
    override val usage = "help [COMMAND]"
    override val summary = "List available commands, or explain one."

    override fun execute(args: List<String>, io: ShellIO, env: ShellEnvironment): CommandResult {
        if (args.isEmpty()) return CommandResult.ok(registry.catalogue() + "\n")

        val command = registry.find(args.first())
            ?: return CommandResult.error("help: no such command '${args.first()}'")

        return CommandResult.ok("${command.usage}\n\n  ${command.summary}\n")
    }
}
