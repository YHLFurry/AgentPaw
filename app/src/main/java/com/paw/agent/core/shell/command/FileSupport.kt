package com.paw.agent.core.shell.command

import com.paw.agent.core.shell.CommandResult
import com.paw.agent.core.shell.SandboxSecurityException
import com.paw.agent.core.shell.ShellEnvironment
import java.io.File

/**
 * File helpers shared by the built-in commands.
 *
 * Every path goes through [ShellEnvironment.resolvePath], which refuses to leave
 * the sandbox root — that is what keeps generated code from reading the user's
 * photos or app data.
 */
internal object FileSupport {

    /**
     * Reads [files], or [stdin] when the list is empty. A single missing file is
     * reported as an error rather than aborting, matching `cat` closely enough
     * for generated scripts.
     */
    fun readAll(
        files: List<String>,
        env: ShellEnvironment,
        stdin: String,
    ): String {
        if (files.isEmpty()) return stdin

        val out = StringBuilder()
        for (path in files) {
            val file = resolveFile(path, env)
            if (!file.exists()) return "cat: $path: No such file or directory\n"
            if (file.isDirectory) {
                out.append("cat: $path: Is a directory\n")
                continue
            }
            val content = runCatching { file.readText() }
                .getOrElse { return "cat: $path: ${it.message}\n" }
            out.append(content)
        }
        return out.toString()
    }

    /** Resolves a path inside the sandbox, throwing if it would escape. */
    fun resolveFile(path: String, env: ShellEnvironment): File {
        val resolved = env.resolvePath(path)
        if (!env.isInsideRoot(resolved)) {
            throw SandboxSecurityException("path escapes the sandbox: $path")
        }
        return File(resolved)
    }

    /** Creates the parent directory of [file] if it is missing. */
    fun ensureParent(file: File) {
        file.parentFile?.let { if (!it.exists()) it.mkdirs() }
    }

    /** Appends a trailing newline unless the text is empty or already has one. */
    fun String.ensureTrailingNewline(): String =
        if (isEmpty() || endsWith("\n")) this else "$this\n"

    /**
     * Splits text into lines, dropping the empty trailing element that
     * `String.lines()` produces for text ending in a newline. Without this,
     * `printf 'b\na\n' | sort` would emit a spurious blank first line.
     */
    fun String.toLines(): List<String> {
        if (isEmpty()) return emptyList()
        val parts = lines()
        return if (parts.isNotEmpty() && parts.last().isEmpty()) parts.dropLast(1) else parts
    }
}
